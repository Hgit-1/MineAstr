package com.mineastr;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

public final class MineAstrBridge implements WebSocket.Listener {
    private static final Gson GSON = new Gson();
    private static final int PROTOCOL_VERSION = 1;
    private static final int MAX_INBOUND_WS_CHARS = 2 * 1024 * 1024;
    private static final int MAX_LOG_MESSAGE_CHARS = 200;
    private static final int MAX_BROADCAST_CONTENT_LENGTH = 2000;
    private static final int MAX_BROADCAST_SENDER_LENGTH = 64;
    private static final int SCREENSHOT_TIMEOUT_SECONDS = 30;
    private static final int SCREENSHOT_MAX_CHUNKS = 64;

    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicLong connectionGeneration = new AtomicLong();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final StringBuilder inboundBuffer = new StringBuilder();
    private final ConcurrentMap<UUID, ClientCapability> clientCapabilities = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingScreenshot> pendingScreenshots = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, String> pendingScreenshotByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScreenshotAssembly> screenshotAssemblies = new ConcurrentHashMap<>();
    private final MineAstrKnowledgeSnapshot knowledgeSnapshot = new MineAstrKnowledgeSnapshot();

    private volatile MineAstrActivityData activityData;
    private volatile long nextActivitySampleMs;
    private volatile long nextEnvironmentSampleMs;
    private volatile int environmentPlayerCursor;

    private volatile MinecraftServer server;
    private volatile ScheduledExecutorService reconnectExecutor = createReconnectExecutor();
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile boolean stopping;
    private volatile long startedAtMs;

    public void start(MinecraftServer server) {
        this.server = server;
        this.stopping = false;
        this.startedAtMs = System.currentTimeMillis();
        ensureReconnectExecutor();
        if (!MineAstrConfig.ENABLED.getAsBoolean()) {
            MineAstr.LOGGER.info("MineAstr 已被配置禁用。");
            return;
        }
        knowledgeSnapshot.refresh(server);
        activityData = MineAstrActivityData.get(server);
        nextActivitySampleMs = 0;
        nextEnvironmentSampleMs = 0;
        if (!MineAstrConfig.ENABLE_PRIVACY_NOTICE.getAsBoolean()) {
            MineAstr.LOGGER.warn("MineAstr 内置玩家数据告知已关闭；服务器提供者仍需自行承担适用的隐私与合规责任。");
        }
        connectNow();
    }

    public void stop() {
        stopping = true;
        connectionGeneration.incrementAndGet();
        connecting.set(false);
        cancelReconnect();
        clearScreenshotState("Minecraft 服务器正在停止。");
        knowledgeSnapshot.close();
        activityData = null;
        WebSocket socket = webSocket.getAndSet(null);
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping");
        }
        server = null;
        ScheduledExecutorService executor = reconnectExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }
        reconnectExecutor = null;
    }

    public boolean isConnected() {
        WebSocket socket = webSocket.get();
        return socket != null && !socket.isInputClosed() && !socket.isOutputClosed();
    }

    public boolean isStarted() {
        return server != null && !stopping;
    }

    public boolean isConnecting() {
        return connecting.get();
    }

    public boolean reconnect() {
        if (server == null || stopping || !MineAstrConfig.ENABLED.getAsBoolean()) {
            return false;
        }
        cancelReconnect();
        connectionGeneration.incrementAndGet();
        connecting.set(false);
        WebSocket socket = webSocket.getAndSet(null);
        if (socket != null) {
            socket.abort();
        }
        connectNow();
        return true;
    }

    public void forwardChat(ServerPlayer player, String rawText) {
        if (server == null || !MineAstrConfig.ENABLED.getAsBoolean()) {
            return;
        }
        WebSocket socket = webSocket.get();
        if (socket == null || socket.isOutputClosed()) {
            MineAstr.LOGGER.debug("MineAstr 未连接，已丢弃本条 Minecraft 聊天。");
            return;
        }
        String content = trimContent(rawText, MineAstrConfig.MAX_MESSAGE_LENGTH.getAsInt());
        if (content.isEmpty()) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "chat");
        payload.addProperty("message_id", UUID.randomUUID().toString());
        payload.addProperty("time_ms", System.currentTimeMillis());
        payload.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        payload.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        payload.addProperty("player_uuid", player.getUUID().toString());
        payload.addProperty("player_name", player.getGameProfile().getName());
        payload.addProperty("content", content);
        sendJson(socket, payload);
    }

    public void registerClientCapability(ServerPlayer player, boolean screenshotSupported, String clientModVersion) {
        if (!screenshotSupported) {
            clientCapabilities.remove(player.getUUID());
            return;
        }
        clientCapabilities.put(player.getUUID(), new ClientCapability(clientModVersion, System.currentTimeMillis()));
        MineAstr.LOGGER.debug("MineAstr 已记录客户端能力：{} {}", player.getGameProfile().getName(), clientModVersion);
    }

    public void unregisterClientCapability(ServerPlayer player) {
        clientCapabilities.remove(player.getUUID());
        pendingScreenshots.values().removeIf(pending -> {
            if (!pending.playerUuid.equals(player.getUUID())) {
                return false;
            }
            pending.cancelTimeout();
            pendingScreenshotByPlayer.remove(pending.playerUuid, pending.messageId);
            sendQueryError(pending.socket, pending.messageId, "screenshot", "目标玩家已离开服务器。");
            screenshotAssemblies.remove(pending.messageId);
            return true;
        });
    }

    public void onPlayerLogin(ServerPlayer player) {
        if (!MineAstrConfig.ENABLE_PRIVACY_NOTICE.getAsBoolean()) return;
        String version = MineAstrConfig.PRIVACY_NOTICE_VERSION.get().strip();
        String key = "mineastr_privacy_notice_version";
        CompoundTag persistent = player.getPersistentData();
        if (version.equals(persistent.getString(key))) return;
        String notice = MineAstrConfig.renderPrivacyNotice();
        if (!notice.isEmpty()) {
            for (String line : notice.split("\\n")) player.sendSystemMessage(Component.literal("[MineAstr] " + line));
        }
        persistent.putString(key, version);
    }

    public void tickActivity(MinecraftServer currentServer) {
        MineAstrActivityData data = activityData;
        if (data == null || !MineAstrConfig.ENABLE_ACTIVITY_TRACKING.getAsBoolean()) return;
        long now = System.currentTimeMillis();
        if (now >= nextActivitySampleMs) {
            for (ServerPlayer player : currentServer.getPlayerList().getPlayers()) data.sample(player, now, false);
            data.prune(now, MineAstrConfig.ACTIVITY_RETENTION_DAYS.getAsInt());
            nextActivitySampleMs = now + MineAstrConfig.ACTIVITY_SAMPLE_SECONDS.getAsInt() * 1000L;
        }
        List<ServerPlayer> players = currentServer.getPlayerList().getPlayers();
        if (now >= nextEnvironmentSampleMs && !players.isEmpty()) {
            data.sampleEnvironment(players.get(Math.floorMod(environmentPlayerCursor++, players.size())), now);
            if (environmentPlayerCursor >= players.size()) {
                environmentPlayerCursor = 0;
                nextEnvironmentSampleMs = now + MineAstrConfig.ENVIRONMENT_SAMPLE_MINUTES.getAsInt() * 60_000L;
            }
        }
        if (data.analysisDue(now, MineAstrConfig.ACTIVITY_ANALYSIS_DAYS.getAsInt())) analyzeActivityNow();
    }

    public void analyzeActivityNow() {
        MineAstrActivityData data = activityData;
        if (data == null) return;
        data.analyze(System.currentTimeMillis(), MineAstrConfig.ACTIVITY_ANALYSIS_DAYS.getAsInt(),
                MineAstrConfig.ACTIVITY_SAMPLE_SECONDS.getAsInt(), MineAstrConfig.MINIMUM_REGION_MINUTES.getAsInt());
    }

    public boolean isActivityOptedOut(UUID playerUuid) {
        MineAstrActivityData data = activityData;
        return data != null && data.isOptedOut(playerUuid);
    }

    public void setActivityOptedOut(UUID playerUuid, boolean optedOut) {
        MineAstrActivityData data = activityData;
        if (data != null) data.setOptedOut(playerUuid, optedOut);
    }

    private void connectNow() {
        if (server == null || stopping || !MineAstrConfig.ENABLED.getAsBoolean() || isConnected() || !connecting.compareAndSet(false, true)) {
            return;
        }

        URI uri;
        try {
            uri = URI.create(MineAstrConfig.WEBSOCKET_URL.get());
        } catch (IllegalArgumentException exc) {
            connecting.set(false);
            MineAstr.LOGGER.error("MineAstr websocketUrl 无效：{}", MineAstrConfig.WEBSOCKET_URL.get(), exc);
            scheduleReconnect();
            return;
        }

        long generation = connectionGeneration.get();
        httpClient
                .newWebSocketBuilder()
                .header("Authorization", "Bearer " + MineAstrConfig.TOKEN.get())
                .buildAsync(uri, this)
                .whenComplete((socket, throwable) -> {
                    if (stopping || generation != connectionGeneration.get()) {
                        if (socket != null) {
                            socket.abort();
                        }
                        return;
                    }
                    connecting.set(false);
                    if (throwable != null) {
                        MineAstr.LOGGER.warn("MineAstr 连接 AstrBot 失败：{}", throwable.getMessage());
                        scheduleReconnect();
                    } else if (socket.isInputClosed() || socket.isOutputClosed()) {
                        socket.abort();
                        MineAstr.LOGGER.warn("MineAstr 与 AstrBot 的 WebSocket 在连接完成前已关闭。");
                        scheduleReconnect();
                    } else {
                        WebSocket previous = webSocket.getAndSet(socket);
                        if (previous != null && previous != socket) {
                            previous.abort();
                        }
                        sendHello(socket);
                        MineAstr.LOGGER.info("MineAstr 已连接到 AstrBot：{}", uri);
                    }
                });
    }

    private void sendHello(WebSocket socket) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "hello");
        payload.addProperty("protocol", PROTOCOL_VERSION);
        payload.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        payload.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        payload.addProperty("mod_version", MineAstr.MOD_VERSION);
        payload.addProperty("minecraft_version", SharedConstants.getCurrentVersion().getName());
        String introductionUrl = server != null && server.isDedicatedServer()
                ? trimFlatContent(MineAstrConfig.SERVER_INTRODUCTION_URL.get(), 2048) : "";
        payload.addProperty("server_introduction_url", introductionUrl);
        JsonArray capabilities = new JsonArray();
        capabilities.add("status");
        capabilities.add("players");
        capabilities.add("player_state");
        capabilities.add("inventory");
        capabilities.add("nearby_entities");
        capabilities.add("region_features");
        capabilities.add("command");
        capabilities.add("screenshot");
        if (MineAstrConfig.ENABLE_KNOWLEDGE_SCAN.getAsBoolean()) {
            capabilities.add("knowledge_manifest");
            capabilities.add("knowledge_page");
            capabilities.add("knowledge_status");
            capabilities.add("knowledge_rescan");
        }
        if (MineAstrConfig.ENABLE_ACTIVITY_TRACKING.getAsBoolean()) {
            capabilities.add("activity_regions_manifest");
            capabilities.add("activity_regions_page");
        }
        payload.add("query_capabilities", capabilities);
        sendJson(socket, payload);
    }

    private void scheduleReconnect() {
        if (stopping || !MineAstrConfig.ENABLED.getAsBoolean()) {
            return;
        }
        cancelReconnect();
        ScheduledExecutorService executor = reconnectExecutor;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        int seconds = MineAstrConfig.RECONNECT_SECONDS.getAsInt();
        reconnectTask = executor.schedule(this::connectNow, seconds, TimeUnit.SECONDS);
    }

    private void cancelReconnect() {
        ScheduledFuture<?> task = reconnectTask;
        if (task != null) {
            task.cancel(false);
            reconnectTask = null;
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        WebSocket.Listener.super.onOpen(webSocket);
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
        if (inboundBuffer.length() + data.length() > MAX_INBOUND_WS_CHARS) {
            inboundBuffer.setLength(0);
            MineAstr.LOGGER.warn("MineAstr 已关闭过大的 AstrBot WebSocket 消息：{} chars", data.length());
            abortActiveSocket(socket, "AstrBot WebSocket 消息超过大小上限。", true);
            return CompletableFuture.completedFuture(null);
        }
        inboundBuffer.append(data);
        if (last) {
            String message = inboundBuffer.toString();
            inboundBuffer.setLength(0);
            handleIncoming(socket, message);
        }
        socket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
        boolean activeSocketClosed = webSocket.compareAndSet(socket, null);
        MineAstr.LOGGER.info("MineAstr WebSocket 已关闭：{} {}", statusCode, reason);
        if (activeSocketClosed) {
            inboundBuffer.setLength(0);
            clearPendingScreenshots("AstrBot WebSocket 已断开。");
            scheduleReconnect();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
        boolean activeSocketFailed = webSocket.compareAndSet(socket, null);
        MineAstr.LOGGER.warn("MineAstr WebSocket 出错：{}", error.getMessage());
        if (activeSocketFailed) {
            inboundBuffer.setLength(0);
            clearPendingScreenshots("AstrBot WebSocket 出错。");
            scheduleReconnect();
        }
    }

    private void handleIncoming(WebSocket socket, String message) {
        JsonObject payload;
        try {
            JsonElement element = JsonParser.parseString(message);
            if (!element.isJsonObject()) {
                MineAstr.LOGGER.warn("MineAstr 已忽略来自 AstrBot 的非对象 JSON：{}", shortenForLog(message));
                return;
            }
            payload = element.getAsJsonObject();
        } catch (RuntimeException exc) {
            MineAstr.LOGGER.warn("MineAstr 已忽略来自 AstrBot 的无效 JSON：{}", shortenForLog(message));
            return;
        }

        String type = getString(payload, "type", "");
        if ("chat".equals(type)) {
            handleChat(payload);
        } else if ("query".equals(type)) {
            handleQuery(socket, payload);
        } else if ("pong".equals(type)) {
            MineAstr.LOGGER.debug("MineAstr 已收到 AstrBot 的 pong。");
        } else if ("error".equals(type)) {
            String error = getString(payload, "message", "unknown");
            MineAstr.LOGGER.warn("MineAstr 收到 AstrBot 错误：{}", error);
        } else {
            MineAstr.LOGGER.debug("MineAstr 已忽略不支持的 AstrBot 消息类型：{}", type);
        }
    }

    private static ScheduledExecutorService createReconnectExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "MineAstr-Reconnect");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void ensureReconnectExecutor() {
        ScheduledExecutorService executor = reconnectExecutor;
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            reconnectExecutor = createReconnectExecutor();
        }
    }

    private void handleChat(JsonObject payload) {
        String senderName = trimFlatContent(getString(payload, "sender_name", MineAstrConfig.BOT_DISPLAY_NAME.get()), MAX_BROADCAST_SENDER_LENGTH);
        String content = trimFlatContent(getString(payload, "content", ""), MAX_BROADCAST_CONTENT_LENGTH);
        if (senderName.isEmpty()) {
            senderName = trimFlatContent(MineAstrConfig.BOT_DISPLAY_NAME.get(), MAX_BROADCAST_SENDER_LENGTH);
        }
        if (content.isBlank()) {
            return;
        }
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            return;
        }
        String rendered = "[" + senderName + "] " + content;
        currentServer.execute(() -> currentServer.getPlayerList().broadcastSystemMessage(Component.literal(rendered), false));
    }

    private void handleQuery(WebSocket socket, JsonObject payload) {
        String query = trimFlatContent(getString(payload, "query", ""), 64).toLowerCase(Locale.ROOT);
        String messageId = trimFlatContent(getString(payload, "message_id", UUID.randomUUID().toString()), 64);
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            sendQueryError(socket, messageId, query, "Minecraft 服务器尚未启动。");
            return;
        }
        currentServer.execute(() -> {
            try {
                switch (query) {
                    case "status" -> sendQueryResult(socket, messageId, query, buildStatusData(currentServer));
                    case "players" -> sendQueryResult(socket, messageId, query, buildPlayersData(currentServer));
                    case "player_state" -> handlePlayerStateQuery(socket, messageId, payload, currentServer);
                    case "inventory" -> handleInventoryQuery(socket, messageId, payload, currentServer);
                    case "nearby_entities" -> handleNearbyEntitiesQuery(socket, messageId, payload, currentServer);
                    case "region_features" -> handleRegionQuery(socket, messageId, payload, currentServer);
                    case "command" -> handleCommandQuery(socket, messageId, payload, currentServer);
                    case "screenshot" -> handleScreenshotQuery(socket, messageId, payload, currentServer);
                    case "knowledge_manifest" -> handleKnowledgeManifestQuery(socket, messageId);
                    case "knowledge_page" -> handleKnowledgePageQuery(socket, messageId, payload);
                    case "knowledge_status" -> sendQueryResult(socket, messageId, query, knowledgeSnapshot.status());
                    case "knowledge_rescan" -> handleKnowledgeRescanQuery(socket, messageId, payload, currentServer);
                    case "activity_regions_manifest" -> handleActivityRegionsManifest(socket, messageId);
                    case "activity_regions_page" -> handleActivityRegionsPage(socket, messageId, payload);
                    default -> sendQueryError(socket, messageId, query, "不支持的查询类型：" + query);
                }
            } catch (RuntimeException exc) {
                MineAstr.LOGGER.warn("MineAstr 查询 {} 处理失败：{}", query, exc.getMessage());
                sendQueryError(socket, messageId, query, "查询处理失败：" + safeErrorMessage(exc));
            }
        });
    }

    public void refreshKnowledgeSnapshot(MinecraftServer currentServer) {
        knowledgeSnapshot.refresh(currentServer);
    }

    public JsonObject knowledgeStatus() {
        return knowledgeSnapshot.status();
    }

    public String rescanKnowledge(MinecraftServer currentServer) {
        return knowledgeSnapshot.refresh(currentServer);
    }

    private void handleKnowledgeRescanQuery(
            WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        if (!MineAstrConfig.ENABLE_KNOWLEDGE_SCAN.getAsBoolean()) {
            sendQueryError(socket, messageId, "knowledge_rescan", "服务端已禁用 Mod 知识扫描。");
            return;
        }
        String scope = trimFlatContent(getString(payload, "scope", "local"), 16).toLowerCase(Locale.ROOT);
        if (!scope.equals("local") && !scope.equals("all")) {
            sendQueryError(socket, messageId, "knowledge_rescan", "Minecraft 端仅支持 local 扫描。");
            return;
        }
        boolean alreadyRunning = knowledgeSnapshot.isScanning();
        String taskId = knowledgeSnapshot.refresh(currentServer);
        JsonObject result = knowledgeSnapshot.status();
        result.addProperty("task_id", taskId);
        result.addProperty("accepted", !alreadyRunning && !"disabled".equals(taskId));
        if (alreadyRunning) result.addProperty("reason", "already_running");
        sendQueryResult(socket, messageId, "knowledge_rescan", result);
    }

    private void handleKnowledgeManifestQuery(WebSocket socket, String messageId) {
        if (!MineAstrConfig.ENABLE_KNOWLEDGE_SCAN.getAsBoolean()) {
            sendQueryError(socket, messageId, "knowledge_manifest", "服务端已禁用 Mod 知识扫描。");
            return;
        }
        sendQueryResult(socket, messageId, "knowledge_manifest", knowledgeSnapshot.manifest());
    }

    private void handleKnowledgePageQuery(WebSocket socket, String messageId, JsonObject payload) {
        if (!MineAstrConfig.ENABLE_KNOWLEDGE_SCAN.getAsBoolean()) {
            sendQueryError(socket, messageId, "knowledge_page", "服务端已禁用 Mod 知识扫描。");
            return;
        }
        String snapshotId = trimFlatContent(getString(payload, "snapshot_id", ""), 128);
        String category = trimFlatContent(getString(payload, "category", ""), 32).toLowerCase(Locale.ROOT);
        int cursor = getInt(payload, "cursor", 0, 0, Integer.MAX_VALUE);
        int pageSize = getInt(
                payload,
                "page_size",
                MineAstrKnowledgeSnapshot.DEFAULT_PAGE_SIZE,
                1,
                MineAstrKnowledgeSnapshot.MAX_PAGE_SIZE);
        try {
            sendQueryResult(
                    socket,
                    messageId,
                    "knowledge_page",
                    knowledgeSnapshot.page(snapshotId, category, cursor, pageSize));
        } catch (IllegalArgumentException | IllegalStateException exc) {
            sendQueryError(socket, messageId, "knowledge_page", exc.getMessage());
        }
    }

    private void handleActivityRegionsManifest(WebSocket socket, String messageId) {
        MineAstrActivityData data = activityData;
        if (!MineAstrConfig.ENABLE_ACTIVITY_TRACKING.getAsBoolean() || data == null) {
            sendQueryError(socket, messageId, "activity_regions_manifest", "服务端已禁用玩家活动地区分析。");
            return;
        }
        sendQueryResult(socket, messageId, "activity_regions_manifest", data.manifest());
    }

    private void handleActivityRegionsPage(WebSocket socket, String messageId, JsonObject payload) {
        MineAstrActivityData data = activityData;
        if (!MineAstrConfig.ENABLE_ACTIVITY_TRACKING.getAsBoolean() || data == null) {
            sendQueryError(socket, messageId, "activity_regions_page", "服务端已禁用玩家活动地区分析。");
            return;
        }
        String snapshot = trimFlatContent(getString(payload, "snapshot_id", ""), 128);
        int cursor = getInt(payload, "cursor", 0, 0, Integer.MAX_VALUE);
        int pageSize = getInt(payload, "page_size", 50, 1, 100);
        try {
            sendQueryResult(socket, messageId, "activity_regions_page", data.page(snapshot, cursor, pageSize));
        } catch (IllegalStateException exc) {
            sendQueryError(socket, messageId, "activity_regions_page", exc.getMessage());
        }
    }

    private void handlePlayerStateQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        if (!MineAstrConfig.ENABLE_PLAYER_STATE_TOOL.getAsBoolean()) {
            sendQueryError(socket, messageId, "player_state", "服务端已禁用玩家状态工具。");
            return;
        }
        ServerPlayer player = findTargetPlayer(currentServer, payload);
        if (player == null) {
            sendQueryError(socket, messageId, "player_state", "未找到目标在线玩家。");
            return;
        }
        sendQueryResult(socket, messageId, "player_state", MineAstrTools.buildPlayerState(player));
    }

    private void handleInventoryQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        if (!MineAstrConfig.ENABLE_INVENTORY_TOOL.getAsBoolean()) {
            sendQueryError(socket, messageId, "inventory", "服务端已禁用背包查询工具。");
            return;
        }
        ServerPlayer player = findTargetPlayer(currentServer, payload);
        if (player == null) {
            sendQueryError(socket, messageId, "inventory", "未找到目标在线玩家。");
            return;
        }
        boolean includeEnderChest = getBoolean(payload, "include_ender_chest", false);
        sendQueryResult(socket, messageId, "inventory", MineAstrTools.buildInventory(player, includeEnderChest));
    }

    private void handleNearbyEntitiesQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        if (!MineAstrConfig.ENABLE_NEARBY_ENTITIES_TOOL.getAsBoolean()) {
            sendQueryError(socket, messageId, "nearby_entities", "服务端已禁用附近实体工具。");
            return;
        }
        ServerPlayer player = findTargetPlayer(currentServer, payload);
        if (player == null) {
            sendQueryError(socket, messageId, "nearby_entities", "未找到目标在线玩家。");
            return;
        }
        double radius = getDouble(payload, "radius", 12.0, 1.0, 32.0);
        sendQueryResult(socket, messageId, "nearby_entities", MineAstrTools.buildNearbyEntities(player, radius));
    }

    private void handleRegionQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        if (!MineAstrConfig.ENABLE_REGION_TOOL.getAsBoolean()) {
            sendQueryError(socket, messageId, "region_features", "服务端已禁用区域特征工具。");
            return;
        }
        boolean coordinateMode = hasCoordinates(payload);
        ServerPlayer player = coordinateMode ? null : findTargetPlayer(currentServer, payload);
        ServerLevel level;
        BlockPos fallbackCenter;
        if (player != null) {
            level = player.serverLevel();
            fallbackCenter = player.blockPosition();
        } else {
            level = findTargetLevel(currentServer, payload);
            if (level == null || !coordinateMode) {
                sendQueryError(socket, messageId, "region_features", "请指定在线玩家，或提供有效的 dimension、x、y、z。");
                return;
            }
            fallbackCenter = new BlockPos(0, level.getSeaLevel(), 0);
        }

        int x = getInt(payload, "x", fallbackCenter.getX(), -30_000_000, 30_000_000);
        int y = getInt(payload, "y", fallbackCenter.getY(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        int z = getInt(payload, "z", fallbackCenter.getZ(), -30_000_000, 30_000_000);
        int horizontalRadius = getInt(payload, "horizontal_radius", 8, 1, 24);
        int verticalRadius = getInt(payload, "vertical_radius", 6, 1, 16);
        long volume = (horizontalRadius * 2L + 1L) * (verticalRadius * 2L + 1L) * (horizontalRadius * 2L + 1L);
        int maxBlocks = MineAstrConfig.REGION_MAX_BLOCKS.getAsInt();
        if (volume > maxBlocks) {
            sendQueryError(socket, messageId, "region_features", "请求区域过大：" + volume + " 方块，服务端上限为 " + maxBlocks + "。");
            return;
        }
        BlockPos center = new BlockPos(x, y, z);
        if (!level.hasChunk(x >> 4, z >> 4)) {
            sendQueryError(socket, messageId, "region_features", "目标中心所在区块尚未加载；为避免卡服，MineAstr 不会强制加载新区块。");
            return;
        }
        sendQueryResult(socket, messageId, "region_features", MineAstrTools.analyzeRegion(level, center, horizontalRadius, verticalRadius));
    }

    private void handleCommandQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        if (!MineAstrConfig.ENABLE_COMMAND_TOOL.getAsBoolean()) {
            sendQueryError(socket, messageId, "command", "服务端命令工具默认关闭；请由服务器管理员在配置中显式启用。");
            return;
        }
        String bridgeToken = MineAstrConfig.TOKEN.get().strip();
        if (bridgeToken.isEmpty() || "change-me".equalsIgnoreCase(bridgeToken)) {
            sendQueryError(socket, messageId, "command", "命令工具要求先把默认 token 改为安全随机字符串。");
            return;
        }
        Requester requester = Requester.from(payload);
        if (!isTrustedRequester(requester)) {
            MineAstr.LOGGER.warn("MineAstr 已拒绝不可信命令请求：requester={} command={}", requester.auditName(), shortenForLog(getString(payload, "command", "")));
            sendQueryError(socket, messageId, "command", "当前请求者不在 trustedCommandUsers 可信名单中。");
            return;
        }
        String command = normalizeCommand(getString(payload, "command", ""));
        if (command.isEmpty()) {
            sendQueryError(socket, messageId, "command", "命令不能为空。");
            return;
        }
        if (command.length() > MineAstrConfig.COMMAND_MAX_LENGTH.getAsInt()) {
            sendQueryError(socket, messageId, "command", "命令长度超过服务端限制。");
            return;
        }
        if (!isAllowedCommand(command)) {
            MineAstr.LOGGER.warn("MineAstr 已拒绝白名单外命令：requester={} command={}", requester.auditName(), command);
            sendQueryError(socket, messageId, "command", "命令未命中 allowedCommandRules 白名单。");
            return;
        }

        CommandCapture capture = new CommandCapture();
        CommandSourceStack source = currentServer.createCommandSourceStack()
                .withSource(capture)
                .withPermission(MineAstrConfig.COMMAND_PERMISSION_LEVEL.getAsInt())
                .withCallback(capture::onResult);
        MineAstr.LOGGER.warn("MineAstr 正在执行受控 LLM 命令：requester={} command={}", requester.auditName(), command);
        currentServer.getCommands().performPrefixedCommand(source, command);

        JsonObject data = new JsonObject();
        data.addProperty("command", command);
        data.addProperty("requester", requester.auditName());
        data.addProperty("success", capture.success);
        data.addProperty("result", capture.result);
        JsonArray output = new JsonArray();
        capture.messages.forEach(output::add);
        data.add("output", output);
        sendQueryResult(socket, messageId, "command", data);
    }

    private void handleScreenshotQuery(WebSocket socket, String messageId, JsonObject payload, MinecraftServer currentServer) {
        ServerPlayer player = findTargetPlayer(currentServer, payload);
        if (player == null) {
            sendQueryError(socket, messageId, "screenshot", "未找到要截图的在线玩家。");
            return;
        }
        if (!clientCapabilities.containsKey(player.getUUID())) {
            sendQueryError(socket, messageId, "screenshot", "目标玩家未安装 MineAstr 客户端 Mod，或客户端尚未声明支持截图。");
            return;
        }
        String existingRequestId = pendingScreenshotByPlayer.putIfAbsent(player.getUUID(), messageId);
        if (existingRequestId != null) {
            sendQueryError(socket, messageId, "screenshot", "目标玩家已有一个截图请求正在处理中。");
            return;
        }
        if (pendingScreenshots.containsKey(messageId)) {
            pendingScreenshotByPlayer.remove(player.getUUID(), messageId);
            sendQueryError(socket, messageId, "screenshot", "同一个截图请求正在处理中。");
            return;
        }

        String reason = trimContent(getString(payload, "reason", "AstrBot 请求查看当前 Minecraft 画面。"), MineAstrPayloads.MAX_REASON_LENGTH);
        int maxWidth = getInt(payload, "max_width", 240, 64, 1024);
        int maxHeight = getInt(payload, "max_height", 135, 36, 1024);
        int maxBytes = getInt(payload, "max_bytes", 131072, 8192, 524288);
        String format = getString(payload, "format", "jpeg");
        if (!"jpeg".equalsIgnoreCase(format) && !"jpg".equalsIgnoreCase(format)) {
            format = "jpeg";
        }

        PendingScreenshot pending = new PendingScreenshot(socket, messageId, player.getUUID(), player.getGameProfile().getName(), maxBytes);
        PendingScreenshot previous = pendingScreenshots.putIfAbsent(messageId, pending);
        if (previous != null) {
            pendingScreenshotByPlayer.remove(player.getUUID(), messageId);
            sendQueryError(socket, messageId, "screenshot", "同一个截图请求正在处理中。");
            return;
        }
        pending.timeout = scheduleScreenshotTimeout(messageId);
        try {
            PacketDistributor.sendToPlayer(player, new MineAstrPayloads.ScreenshotRequest(
                    messageId,
                    reason,
                    maxWidth,
                    maxHeight,
                    maxBytes,
                    format));
        } catch (RuntimeException exc) {
            failScreenshot(messageId, "向玩家客户端发送截图请求失败：" + exc.getMessage());
        }
    }

    private JsonObject buildStatusData(MinecraftServer currentServer) {
        PlayerList playerList = currentServer.getPlayerList();
        JsonObject data = new JsonObject();
        data.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        data.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        data.addProperty("mod_version", MineAstr.MOD_VERSION);
        data.addProperty("minecraft_version", SharedConstants.getCurrentVersion().getName());
        data.addProperty("dedicated", currentServer.isDedicatedServer());
        data.addProperty("player_count", playerList.getPlayerCount());
        data.addProperty("max_players", playerList.getMaxPlayers());
        data.addProperty("uptime_ms", Math.max(0L, System.currentTimeMillis() - startedAtMs));
        BlockPos spawn = currentServer.overworld().getSharedSpawnPos();
        JsonObject spawnData = new JsonObject();
        spawnData.addProperty("dimension", Level.OVERWORLD.location().toString());
        spawnData.addProperty("x", spawn.getX());
        spawnData.addProperty("y", spawn.getY());
        spawnData.addProperty("z", spawn.getZ());
        data.add("world_spawn", spawnData);
        JsonArray names = new JsonArray();
        JsonArray screenshotCapableNames = new JsonArray();
        for (ServerPlayer player : playerList.getPlayers()) {
            names.add(player.getGameProfile().getName());
            if (clientCapabilities.containsKey(player.getUUID())) {
                screenshotCapableNames.add(player.getGameProfile().getName());
            }
        }
        data.add("online_player_names", names);
        data.add("screenshot_capable_player_names", screenshotCapableNames);
        return data;
    }

    private JsonObject buildPlayersData(MinecraftServer currentServer) {
        PlayerList playerList = currentServer.getPlayerList();
        JsonObject data = new JsonObject();
        data.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        data.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        data.addProperty("player_count", playerList.getPlayerCount());
        data.addProperty("max_players", playerList.getMaxPlayers());
        JsonArray players = new JsonArray();
        for (ServerPlayer player : playerList.getPlayers()) {
            JsonObject playerData = new JsonObject();
            playerData.addProperty("uuid", player.getUUID().toString());
            playerData.addProperty("name", player.getGameProfile().getName());
            playerData.addProperty("display_name", player.getDisplayName().getString());
            playerData.addProperty("screenshot_supported", clientCapabilities.containsKey(player.getUUID()));
            players.add(playerData);
        }
        data.add("players", players);
        return data;
    }

    public void receiveScreenshotChunk(ServerPlayer player, MineAstrPayloads.ScreenshotChunk chunk) {
        PendingScreenshot pending = pendingScreenshots.get(chunk.requestId());
        if (pending == null || !pending.playerUuid.equals(player.getUUID())) {
            MineAstr.LOGGER.debug("MineAstr 已忽略未知截图分片：{}", chunk.requestId());
            return;
        }
        if (chunk.totalChunks() <= 0 || chunk.totalChunks() > SCREENSHOT_MAX_CHUNKS) {
            failScreenshot(chunk.requestId(), "截图分片数量无效。");
            return;
        }
        if (chunk.totalBytes() <= 0 || chunk.totalBytes() > pending.maxBytes) {
            failScreenshot(chunk.requestId(), "截图大小超过服务端允许的限制。");
            return;
        }
        if (chunk.width() <= 0 || chunk.height() <= 0) {
            failScreenshot(chunk.requestId(), "截图尺寸无效。");
            return;
        }
        if (!"image/jpeg".equalsIgnoreCase(chunk.mimeType())) {
            failScreenshot(chunk.requestId(), "截图 MIME 类型无效。");
            return;
        }
        if (chunk.bytes() == null || chunk.bytes().length == 0 || chunk.bytes().length > MineAstrPayloads.MAX_CHUNK_BYTES) {
            failScreenshot(chunk.requestId(), "截图分片内容无效。");
            return;
        }
        if (chunk.index() < 0 || chunk.index() >= chunk.totalChunks()) {
            failScreenshot(chunk.requestId(), "截图分片序号无效。");
            return;
        }

        ScreenshotAssembly assembly = screenshotAssemblies.computeIfAbsent(
                chunk.requestId(),
                id -> new ScreenshotAssembly(chunk.totalChunks(), chunk.totalBytes(), chunk.width(), chunk.height(), chunk.mimeType(), chunk.capturedAtMs()));
        byte[] imageBytes;
        synchronized (assembly) {
            if (!assembly.accept(chunk)) {
                failScreenshot(chunk.requestId(), "截图分片元数据不一致。");
                return;
            }
            if (!assembly.isComplete()) {
                return;
            }
            try {
                imageBytes = assembly.join();
            } catch (RuntimeException exc) {
                failScreenshot(chunk.requestId(), "截图分片重组失败。");
                return;
            }
        }
        if (!looksLikeJpeg(imageBytes)) {
            failScreenshot(chunk.requestId(), "截图数据不是有效的 JPEG 图片。");
            return;
        }

        PendingScreenshot completed = pendingScreenshots.remove(chunk.requestId());
        pendingScreenshotByPlayer.remove(player.getUUID(), chunk.requestId());
        screenshotAssemblies.remove(chunk.requestId());
        if (completed == null) {
            return;
        }
        completed.cancelTimeout();

        JsonObject data = new JsonObject();
        data.addProperty("player_uuid", player.getUUID().toString());
        data.addProperty("player_name", player.getGameProfile().getName());
        data.addProperty("status", "ok");
        data.addProperty("mime_type", assembly.mimeType);
        data.addProperty("width", assembly.width);
        data.addProperty("height", assembly.height);
        data.addProperty("bytes", imageBytes.length);
        data.addProperty("image_base64", Base64.getEncoder().encodeToString(imageBytes));
        data.addProperty("captured_at_ms", assembly.capturedAtMs);
        sendQueryResult(completed.socket, completed.messageId, "screenshot", data);
    }

    public void receiveScreenshotError(ServerPlayer player, String code, String message, String requestId) {
        PendingScreenshot pending = pendingScreenshots.get(requestId);
        if (pending == null || !pending.playerUuid.equals(player.getUUID())) {
            return;
        }
        PendingScreenshot removed = pendingScreenshots.remove(requestId);
        pendingScreenshotByPlayer.remove(player.getUUID(), requestId);
        screenshotAssemblies.remove(requestId);
        if (removed != null) {
            removed.cancelTimeout();
            sendQueryError(removed.socket, removed.messageId, "screenshot", screenshotErrorMessage(code, message));
        }
    }

    private void sendQueryResult(WebSocket socket, String messageId, String query, JsonObject data) {
        JsonObject payload = queryEnvelope(messageId, query, true);
        payload.add("data", data);
        sendJson(socket, payload);
    }

    private void sendQueryError(WebSocket socket, String messageId, String query, String error) {
        JsonObject payload = queryEnvelope(messageId, query, false);
        payload.addProperty("error", error);
        sendJson(socket, payload);
    }

    private JsonObject queryEnvelope(String messageId, String query, boolean ok) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "query_result");
        payload.addProperty("message_id", messageId);
        payload.addProperty("query", query);
        payload.addProperty("ok", ok);
        payload.addProperty("time_ms", System.currentTimeMillis());
        payload.addProperty("server_id", MineAstrConfig.SERVER_ID.get());
        payload.addProperty("server_name", MineAstrConfig.SERVER_NAME.get());
        return payload;
    }

    private void sendJson(WebSocket socket, JsonObject payload) {
        if (socket == null || socket.isOutputClosed()) {
            return;
        }
        try {
            socket.sendText(GSON.toJson(payload), true).whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    handleSendFailure(socket, throwable);
                }
            });
        } catch (RuntimeException exc) {
            handleSendFailure(socket, exc);
        }
    }

    private ScheduledFuture<?> scheduleScreenshotTimeout(String requestId) {
        ScheduledExecutorService executor = reconnectExecutor;
        if (executor == null || executor.isShutdown()) {
            return null;
        }
        return executor.schedule(() -> failScreenshot(requestId, "等待玩家客户端截图超时。"), SCREENSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void failScreenshot(String requestId, String error) {
        PendingScreenshot pending = pendingScreenshots.remove(requestId);
        screenshotAssemblies.remove(requestId);
        if (pending == null) {
            return;
        }
        pendingScreenshotByPlayer.remove(pending.playerUuid, requestId);
        pending.cancelTimeout();
        sendQueryError(pending.socket, pending.messageId, "screenshot", error);
    }

    private void clearScreenshotState(String error) {
        clearPendingScreenshots(error);
        clientCapabilities.clear();
    }

    private void clearPendingScreenshots(String error) {
        for (PendingScreenshot pending : pendingScreenshots.values()) {
            pending.cancelTimeout();
            sendQueryError(pending.socket, pending.messageId, "screenshot", error);
        }
        pendingScreenshots.clear();
        pendingScreenshotByPlayer.clear();
        screenshotAssemblies.clear();
    }

    private ServerPlayer findTargetPlayer(MinecraftServer currentServer, JsonObject payload) {
        String playerUuid = getString(payload, "player_uuid", "").strip();
        String playerName = getString(payload, "player_name", "").strip();
        PlayerList playerList = currentServer.getPlayerList();
        if (!playerUuid.isEmpty()) {
            try {
                ServerPlayer player = playerList.getPlayer(UUID.fromString(playerUuid));
                if (player != null) {
                    return player;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (!playerName.isEmpty()) {
            for (ServerPlayer player : playerList.getPlayers()) {
                if (player.getGameProfile().getName().equalsIgnoreCase(playerName)) {
                    return player;
                }
            }
        }
        if (playerUuid.isEmpty() && playerName.isEmpty() && playerList.getPlayerCount() == 1) {
            return playerList.getPlayers().getFirst();
        }
        return null;
    }

    private static ServerLevel findTargetLevel(MinecraftServer currentServer, JsonObject payload) {
        String dimensionText = trimFlatContent(getString(payload, "dimension", "minecraft:overworld"), 128);
        ResourceLocation location = ResourceLocation.tryParse(dimensionText);
        if (location == null) {
            return null;
        }
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, location);
        return currentServer.getLevel(key);
    }

    private static boolean hasCoordinates(JsonObject payload) {
        return payload.has("x") && payload.has("y") && payload.has("z");
    }

    private static boolean isTrustedRequester(Requester requester) {
        if (requester.identities().isEmpty()) {
            return false;
        }
        for (String configured : MineAstrConfig.TRUSTED_COMMAND_USERS.get()) {
            String trusted = configured.strip().toLowerCase(Locale.ROOT);
            if (!trusted.isEmpty() && requester.identities().contains(trusted)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllowedCommand(String command) {
        String normalized = command.toLowerCase(Locale.ROOT);
        for (String configured : MineAstrConfig.ALLOWED_COMMAND_RULES.get()) {
            String rule = normalizeCommand(configured).toLowerCase(Locale.ROOT);
            if (rule.equals("*")) {
                return true;
            }
            if (rule.endsWith(" *")) {
                String prefix = rule.substring(0, rule.length() - 2).strip();
                if (!prefix.isEmpty() && (normalized.equals(prefix) || normalized.startsWith(prefix + " "))) {
                    return true;
                }
            } else if (normalized.equals(rule)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeCommand(String rawCommand) {
        if (rawCommand == null) {
            return "";
        }
        String command = rawCommand.strip();
        while (command.startsWith("/")) {
            command = command.substring(1).stripLeading();
        }
        for (int index = 0; index < command.length(); index++) {
            if (Character.isISOControl(command.charAt(index))) {
                return "";
            }
        }
        return command;
    }

    private static String screenshotErrorMessage(String code, String message) {
        String detail = message == null || message.isBlank() ? "客户端未提供详细原因。" : message;
        if ("denied".equals(code)) {
            return "玩家拒绝发送截图。";
        }
        if ("disabled".equals(code)) {
            return "客户端已在 MineAstr 配置中禁用截图发送。";
        }
        if ("not_in_game".equals(code)) {
            return "客户端尚未进入游戏，无法截图。";
        }
        return detail;
    }

    private static String getString(JsonObject payload, String key, String defaultValue) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return payload.get(key).getAsString();
        } catch (RuntimeException exc) {
            return defaultValue;
        }
    }

    private static int getInt(JsonObject payload, String key, int defaultValue, int min, int max) {
        int value = defaultValue;
        if (payload.has(key) && !payload.get(key).isJsonNull()) {
            try {
                value = payload.get(key).getAsInt();
            } catch (RuntimeException ignored) {
                value = defaultValue;
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double getDouble(JsonObject payload, String key, double defaultValue, double min, double max) {
        double value = defaultValue;
        if (payload.has(key) && !payload.get(key).isJsonNull()) {
            try {
                value = payload.get(key).getAsDouble();
            } catch (RuntimeException ignored) {
                value = defaultValue;
            }
        }
        if (!Double.isFinite(value)) {
            value = defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static boolean getBoolean(JsonObject payload, String key, boolean defaultValue) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return payload.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    private static String trimContent(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.replace("\r", "").strip();
        if (trimmed.length() > maxLength) {
            return trimmed.substring(0, maxLength);
        }
        return trimmed;
    }

    private static String trimFlatContent(String text, int maxLength) {
        String trimmed = trimContent(text, maxLength).replace('\n', ' ').strip();
        if (trimmed.length() > maxLength) {
            return trimmed.substring(0, maxLength);
        }
        return trimmed;
    }

    private void handleSendFailure(WebSocket socket, Throwable throwable) {
        MineAstr.LOGGER.warn("MineAstr 发送 WebSocket 数据失败：{}", throwable.getMessage());
        abortActiveSocket(socket, "WebSocket 发送失败。", true);
    }

    private void abortActiveSocket(WebSocket socket, String error, boolean reconnect) {
        boolean activeSocketFailed = webSocket.compareAndSet(socket, null);
        try {
            socket.abort();
        } catch (RuntimeException ignored) {
        }
        if (activeSocketFailed) {
            clearPendingScreenshots(error);
            if (reconnect) {
                scheduleReconnect();
            }
        }
    }

    private static String shortenForLog(String message) {
        if (message == null) {
            return "";
        }
        String flattened = message.replace('\r', ' ').replace('\n', ' ');
        if (flattened.length() <= MAX_LOG_MESSAGE_CHARS) {
            return flattened;
        }
        return flattened.substring(0, MAX_LOG_MESSAGE_CHARS) + "...";
    }

    private static boolean looksLikeJpeg(byte[] imageBytes) {
        return imageBytes != null
                && imageBytes.length >= 4
                && (imageBytes[0] & 0xFF) == 0xFF
                && (imageBytes[1] & 0xFF) == 0xD8
                && (imageBytes[imageBytes.length - 2] & 0xFF) == 0xFF
                && (imageBytes[imageBytes.length - 1] & 0xFF) == 0xD9;
    }

    private static String safeErrorMessage(Throwable throwable) {
        String message = throwable == null ? "未知错误" : throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        return trimFlatContent(message, 256);
    }

    private record Requester(List<String> identities, String auditName) {
        private static Requester from(JsonObject payload) {
            List<String> identities = new ArrayList<>();
            addIdentity(identities, getString(payload, "requester_uuid", ""));
            addIdentity(identities, getString(payload, "requester_id", ""));
            addIdentity(identities, getString(payload, "requester_name", ""));
            String platform = trimFlatContent(getString(payload, "requester_platform", "unknown"), 64);
            String best = identities.isEmpty() ? "unknown@" + platform : identities.getFirst() + "@" + platform;
            return new Requester(List.copyOf(identities), best);
        }

        private static void addIdentity(List<String> identities, String value) {
            String normalized = trimFlatContent(value, 128).toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && !identities.contains(normalized)) {
                identities.add(normalized);
            }
        }
    }

    private static final class CommandCapture implements CommandSource {
        private static final int MAX_MESSAGES = 20;
        private final List<String> messages = new ArrayList<>();
        private boolean success;
        private int result;

        @Override
        public void sendSystemMessage(Component component) {
            if (messages.size() < MAX_MESSAGES) {
                messages.add(trimFlatContent(component.getString(), 512));
            }
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        private void onResult(boolean success, int result) {
            this.success = success;
            this.result = result;
        }
    }

    private record ClientCapability(String modVersion, long seenAtMs) {
    }

    private static final class PendingScreenshot {
        private final WebSocket socket;
        private final String messageId;
        private final UUID playerUuid;
        private final String playerName;
        private final int maxBytes;
        private volatile ScheduledFuture<?> timeout;

        private PendingScreenshot(WebSocket socket, String messageId, UUID playerUuid, String playerName, int maxBytes) {
            this.socket = socket;
            this.messageId = messageId;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.maxBytes = maxBytes;
        }

        private void cancelTimeout() {
            ScheduledFuture<?> task = timeout;
            if (task != null) {
                task.cancel(false);
                timeout = null;
            }
        }
    }

    private static final class ScreenshotAssembly {
        private final int totalChunks;
        private final int totalBytes;
        private final int width;
        private final int height;
        private final String mimeType;
        private final long capturedAtMs;
        private final byte[][] chunks;
        private int received;
        private int receivedBytes;

        private ScreenshotAssembly(int totalChunks, int totalBytes, int width, int height, String mimeType, long capturedAtMs) {
            this.totalChunks = totalChunks;
            this.totalBytes = totalBytes;
            this.width = width;
            this.height = height;
            this.mimeType = mimeType == null || mimeType.isBlank() ? "image/jpeg" : mimeType;
            this.capturedAtMs = capturedAtMs;
            this.chunks = new byte[totalChunks][];
        }

        private boolean accept(MineAstrPayloads.ScreenshotChunk chunk) {
            if (chunk.totalChunks() != totalChunks || chunk.totalBytes() != totalBytes || chunk.width() != width || chunk.height() != height) {
                return false;
            }
            if (!mimeType.equalsIgnoreCase(chunk.mimeType())) {
                return false;
            }
            if (chunk.bytes().length == 0 || chunk.bytes().length > MineAstrPayloads.MAX_CHUNK_BYTES) {
                return false;
            }
            if (chunks[chunk.index()] == null) {
                if (receivedBytes + chunk.bytes().length > totalBytes) {
                    return false;
                }
                chunks[chunk.index()] = chunk.bytes();
                received++;
                receivedBytes += chunk.bytes().length;
            }
            return true;
        }

        private boolean isComplete() {
            return received == totalChunks && receivedBytes == totalBytes;
        }

        private byte[] join() {
            byte[] output = new byte[totalBytes];
            int offset = 0;
            for (byte[] chunk : chunks) {
                if (chunk == null) {
                    throw new IllegalStateException("截图分片缺失。");
                }
                System.arraycopy(chunk, 0, output, offset, chunk.length);
                offset += chunk.length;
            }
            return output;
        }
    }
}
