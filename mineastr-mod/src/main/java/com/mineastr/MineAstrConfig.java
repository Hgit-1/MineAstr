package com.mineastr;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class MineAstrConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment(
                    "是否启用 MineAstr 并连接到 AstrBot。",
                    "不想转发聊天时改成 false。")
            .define("enabled", true);

    public static final ModConfigSpec.ConfigValue<String> WEBSOCKET_URL = BUILDER
            .comment(
                    "AstrBot minecraft 平台适配器的 WebSocket 地址。",
                    "本机运行 AstrBot 时通常保持 ws://127.0.0.1:8765/ws。",
                    "AstrBot 在另一台机器时，把 127.0.0.1 改成那台机器的 IP 或域名。")
            .define("websocketUrl", "ws://127.0.0.1:8765/ws");

    public static final ModConfigSpec.ConfigValue<String> TOKEN = BUILDER
            .comment(
                    "AstrBot 插件校验的 Bearer Token。",
                    "这里的值必须与 AstrBot minecraft 平台适配器中的 token 完全一致。",
                    "建议把 change-me 改成较长随机字符串，并同时填到 AstrBot 侧。")
            .define("token", "change-me");

    public static final ModConfigSpec.ConfigValue<String> SERVER_ID = BUILDER
            .comment(
                    "发送给 AstrBot 的稳定服务器 ID。",
                    "只有接入多个 Minecraft 服务器时才需要改；单服通常保持 minecraft。")
            .define("serverId", "minecraft");

    public static final ModConfigSpec.ConfigValue<String> SERVER_NAME = BUILDER
            .comment(
                    "发送给 AstrBot 的服务器显示名称。",
                    "用于日志和识别，可以写成你的服务器名称。")
            .define("serverName", "Minecraft 服务器");

    public static final ModConfigSpec.ConfigValue<String> BOT_DISPLAY_NAME = BUILDER
            .comment(
                    "AstrBot 消息广播到 Minecraft 时显示的名称。",
                    "游戏内会显示为 [名称] 回复内容。")
            .define("botDisplayName", "AstrBot");

    public static final ModConfigSpec.IntValue RECONNECT_SECONDS = BUILDER
            .comment(
                    "WebSocket 断开后的重连间隔，单位为秒。",
                    "网络不稳定时可以适当调大。")
            .defineInRange("reconnectSeconds", 5, 1, 300);

    public static final ModConfigSpec.IntValue MAX_MESSAGE_LENGTH = BUILDER
            .comment(
                    "转发到 AstrBot 的单条玩家聊天最大长度。",
                    "超过这个长度的消息会被截断。")
            .defineInRange("maxMessageLength", 1000, 1, 4096);

    static final ModConfigSpec SPEC = BUILDER.build();

    private MineAstrConfig() {
    }
}
