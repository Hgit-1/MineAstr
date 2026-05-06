package com.mineastr;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class MineAstrConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("是否启用 MineAstr 并连接到 AstrBot。")
            .define("enabled", true);

    public static final ModConfigSpec.ConfigValue<String> WEBSOCKET_URL = BUILDER
            .comment("AstrBot minecraft 平台适配器的 WebSocket 地址。")
            .define("websocketUrl", "ws://127.0.0.1:8765/ws");

    public static final ModConfigSpec.ConfigValue<String> TOKEN = BUILDER
            .comment("AstrBot 插件校验的 Bearer Token，需要与 AstrBot 侧 token 一致。")
            .define("token", "change-me");

    public static final ModConfigSpec.ConfigValue<String> SERVER_ID = BUILDER
            .comment("发送给 AstrBot 的稳定服务器 ID。")
            .define("serverId", "minecraft");

    public static final ModConfigSpec.ConfigValue<String> SERVER_NAME = BUILDER
            .comment("发送给 AstrBot 的服务器显示名称。")
            .define("serverName", "Minecraft 服务器");

    public static final ModConfigSpec.ConfigValue<String> BOT_DISPLAY_NAME = BUILDER
            .comment("AstrBot 消息广播到 Minecraft 时显示的名称。")
            .define("botDisplayName", "AstrBot");

    public static final ModConfigSpec.IntValue RECONNECT_SECONDS = BUILDER
            .comment("WebSocket 断开后的重连间隔，单位为秒。")
            .defineInRange("reconnectSeconds", 5, 1, 300);

    public static final ModConfigSpec.IntValue MAX_MESSAGE_LENGTH = BUILDER
            .comment("转发到 AstrBot 的单条玩家聊天最大长度。")
            .defineInRange("maxMessageLength", 1000, 1, 4096);

    static final ModConfigSpec SPEC = BUILDER.build();

    private MineAstrConfig() {
    }
}
