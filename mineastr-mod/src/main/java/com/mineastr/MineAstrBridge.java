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
import java.util.Base64;
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
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

public final class MineAstrBridge implements WebSocket.Listener {
    private static final Gson GSON = new Gson();
    private static final int PROTOCOL_VERSION = 1;
    private static final int SCREENSHOT_TIMEOUT_SECONDS = 30;
    private static final int SCREENSHOT_MAX_CHUNKS = 64;

    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final StringBuilder inboundBuffer = new StringBuilder();
    private final ConcurrentMap<UUID, ClientCapability> clientCapabilities = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingScreenshot> pendingScreenshots = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScreenshotAssembly> screenshotAssemblies = new ConcurrentHashMap<>();

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
        connectNow();
    }

    public void stop() {
        stopping = true;
        cancelReconnect();
        clearScreenshotState("Minecraft 服务器正在停止。");
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

    public boolean isConnecting() {
        return connecting.get();
    }

    public void reconnect() {
        if (stopping || !MineAstrConfig.ENABLED.getAsBoolean()) {
            return;
        }
        cancelReconnect();
        WebSocket socket = webSocket.getAndSet(null);
        if (socket != null) {
            socket.abort();
        }
        connectNow();
    }

    public void forwardChat(ServerPlayer player, String rawText) {
        if (!MineAstrConfig.ENABLED.getAsBoolean()) {
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
        payload.addProperty("player_uuid", player.getUUID().toString());
        payload.addProperty("player_name", player.getGameProfile().getName());
        payload.addProperty("content", content);
        socket.sendText(GSON.toJson(payload), true);
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
            sendQueryError(pending.socket, pending.messageId, "screenshot", "目标玩家已离开服务器。");
            screenshotAssemblies.remove(pending.messageId);
            return true;
        });
    }

    private void connectNow() {
        if (stopping || !MineAstrConfig.ENABLED.getAsBoolean() || isConnected() || !connecting.compareAndSet(false, true)) {
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

        HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .newWebSocketBuilder()
                .header("Authorization", "Bearer " + MineAstrConfig.TOKEN.get())
                .buildAsync(uri, this)
                .whenComplete((socket, throwable) -> {
                    connecting.set(false);
                    if (stopping) {
                        if (socket != null) {
                            socket.abort();
                        }
                        return;
                    }
                    if (throwable != null) {
                        MineAstr.LOGGER.warn("MineAstr 连接 AstrBot 失败：{}", throwable.getMessage());
                        scheduleReconnect();
                    } else {
                        webSocket.set(socket);
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
        socket.sendText(GSON.toJson(payload), true);
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
            scheduleReconnect();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
        boolean activeSocketFailed = webSocket.compareAndSet(socket, null);
        MineAstr.LOGGER.warn("MineAstr WebSocket 出错：{}", error.getMessage());
        if (activeSocketFailed) {
            scheduleReconnect();
        }
    }

    private void handleIncoming(WebSocket socket, String message) {
        JsonObject payload;
        try {
            JsonElement element = JsonParser.parseString(message);
            if (!element.isJsonObject()) {
                MineAstr.LOGGER.warn("MineAstr 已忽略来自 AstrBot 的非对象 JSON：{}", message);
                return;
            }
            payload = element.getAsJsonObject();
        } catch (RuntimeException exc) {
            MineAstr.LOGGER.warn("MineAstr 已忽略来自 AstrBot 的无效 JSON：{}", message);
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
        String senderName = getString(payload, "sender_name", MineAstrConfig.BOT_DISPLAY_NAME.get());
        String content = getString(payload, "content", "");
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
        String query = getString(payload, "query", "");
        String messageId = getString(payload, "message_id", UUID.randomUUID().toString());
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            sendQueryError(socket, messageId, query, "Minecraft 服务器尚未启动。");
            return;
        }
        currentServer.execute(() -> {
            if ("status".equals(query)) {
                sendQueryResult(socket, messageId, query, buildStatusData(currentServer));
            } else if ("players".equals(query)) {
                sendQueryResult(socket, messageId, query, buildPlayersData(currentServer));
            } else if ("screenshot".equals(query)) {
                handleScreenshotQuery(socket, messageId, payload, currentServer);
            } else {
                sendQueryError(socket, messageId, query, "不支持的查询类型：" + query);
            }
        });
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
        if (pendingScreenshots.containsKey(messageId)) {
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
            sendQueryError(socket, messageId, "screenshot", "同一个截图请求正在处理中。");
            return;
        }
        pending.timeout = scheduleScreenshotTimeout(messageId);
        PacketDistributor.sendToPlayer(player, new MineAstrPayloads.ScreenshotRequest(
                messageId,
                reason,
                maxWidth,
                maxHeight,
                maxBytes,
                format));
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
            imageBytes = assembly.join();
        }

        PendingScreenshot completed = pendingScreenshots.remove(chunk.requestId());
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
        socket.sendText(GSON.toJson(payload), true);
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
        pending.cancelTimeout();
        sendQueryError(pending.socket, pending.messageId, "screenshot", error);
    }

    private void clearScreenshotState(String error) {
        for (PendingScreenshot pending : pendingScreenshots.values()) {
            pending.cancelTimeout();
            sendQueryError(pending.socket, pending.messageId, "screenshot", error);
        }
        pendingScreenshots.clear();
        screenshotAssemblies.clear();
        clientCapabilities.clear();
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
                System.arraycopy(chunk, 0, output, offset, chunk.length);
                offset += chunk.length;
            }
            return output;
        }
    }
}
