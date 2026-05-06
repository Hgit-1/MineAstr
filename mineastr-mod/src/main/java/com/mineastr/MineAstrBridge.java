package com.mineastr;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class MineAstrBridge implements WebSocket.Listener {
    private static final Gson GSON = new Gson();
    private static final int PROTOCOL_VERSION = 1;

    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final StringBuilder inboundBuffer = new StringBuilder();

    private volatile MinecraftServer server;
    private volatile ScheduledExecutorService reconnectExecutor = createReconnectExecutor();
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile boolean stopping;

    public void start(MinecraftServer server) {
        this.server = server;
        this.stopping = false;
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

    private void connectNow() {
        if (stopping || !MineAstrConfig.ENABLED.getAsBoolean() || !connecting.compareAndSet(false, true)) {
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
            handleIncoming(message);
        }
        socket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
        webSocket.compareAndSet(socket, null);
        MineAstr.LOGGER.info("MineAstr WebSocket 已关闭：{} {}", statusCode, reason);
        scheduleReconnect();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
        webSocket.compareAndSet(socket, null);
        MineAstr.LOGGER.warn("MineAstr WebSocket 出错：{}", error.getMessage());
        scheduleReconnect();
    }

    private void handleIncoming(String message) {
        JsonObject payload;
        try {
            payload = JsonParser.parseString(message).getAsJsonObject();
        } catch (RuntimeException exc) {
            MineAstr.LOGGER.warn("MineAstr 已忽略来自 AstrBot 的无效 JSON：{}", message);
            return;
        }

        String type = payload.has("type") ? payload.get("type").getAsString() : "";
        if ("chat".equals(type)) {
            handleChat(payload);
        } else if ("pong".equals(type)) {
            MineAstr.LOGGER.debug("MineAstr 已收到 AstrBot 的 pong。");
        } else if ("error".equals(type)) {
            String error = payload.has("message") ? payload.get("message").getAsString() : "unknown";
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
        String senderName = payload.has("sender_name") ? payload.get("sender_name").getAsString() : MineAstrConfig.BOT_DISPLAY_NAME.get();
        String content = payload.has("content") ? payload.get("content").getAsString() : "";
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
}
