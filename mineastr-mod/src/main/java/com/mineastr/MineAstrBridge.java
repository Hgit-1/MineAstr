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

    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "MineAstr-Reconnect");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final StringBuilder inboundBuffer = new StringBuilder();

    private volatile MinecraftServer server;
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile boolean stopping;

    public void start(MinecraftServer server) {
        this.server = server;
        this.stopping = false;
        if (!MineAstrConfig.ENABLED.getAsBoolean()) {
            MineAstr.LOGGER.info("MineAstr is disabled by config.");
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
        reconnectExecutor.shutdownNow();
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
            MineAstr.LOGGER.debug("Dropping Minecraft chat because MineAstr is disconnected.");
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
            MineAstr.LOGGER.error("Invalid MineAstr websocketUrl: {}", MineAstrConfig.WEBSOCKET_URL.get(), exc);
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
                    if (throwable != null) {
                        MineAstr.LOGGER.warn("MineAstr failed to connect to AstrBot: {}", throwable.getMessage());
                        scheduleReconnect();
                    } else {
                        webSocket.set(socket);
                        sendHello(socket);
                        MineAstr.LOGGER.info("MineAstr connected to AstrBot at {}", uri);
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
        int seconds = MineAstrConfig.RECONNECT_SECONDS.getAsInt();
        reconnectTask = reconnectExecutor.schedule(this::connectNow, seconds, TimeUnit.SECONDS);
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
        MineAstr.LOGGER.info("MineAstr websocket closed: {} {}", statusCode, reason);
        scheduleReconnect();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
        webSocket.compareAndSet(socket, null);
        MineAstr.LOGGER.warn("MineAstr websocket error: {}", error.getMessage());
        scheduleReconnect();
    }

    private void handleIncoming(String message) {
        JsonObject payload;
        try {
            payload = JsonParser.parseString(message).getAsJsonObject();
        } catch (RuntimeException exc) {
            MineAstr.LOGGER.warn("MineAstr ignored invalid JSON from AstrBot: {}", message);
            return;
        }

        String type = payload.has("type") ? payload.get("type").getAsString() : "";
        if ("chat".equals(type)) {
            handleChat(payload);
        } else if ("pong".equals(type)) {
            MineAstr.LOGGER.debug("MineAstr received pong from AstrBot.");
        } else if ("error".equals(type)) {
            String error = payload.has("message") ? payload.get("message").getAsString() : "unknown";
            MineAstr.LOGGER.warn("MineAstr received error from AstrBot: {}", error);
        } else {
            MineAstr.LOGGER.debug("MineAstr ignored unsupported AstrBot payload type: {}", type);
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
