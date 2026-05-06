package com.mineastr;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class MineAstrConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Whether MineAstr should connect to AstrBot.")
            .define("enabled", true);

    public static final ModConfigSpec.ConfigValue<String> WEBSOCKET_URL = BUILDER
            .comment("AstrBot minecraft platform adapter WebSocket URL.")
            .define("websocketUrl", "ws://127.0.0.1:8765/ws");

    public static final ModConfigSpec.ConfigValue<String> TOKEN = BUILDER
            .comment("Bearer token expected by the AstrBot plugin.")
            .define("token", "change-me");

    public static final ModConfigSpec.ConfigValue<String> SERVER_ID = BUILDER
            .comment("Stable server id sent to AstrBot.")
            .define("serverId", "minecraft");

    public static final ModConfigSpec.ConfigValue<String> SERVER_NAME = BUILDER
            .comment("Human-readable server name sent to AstrBot.")
            .define("serverName", "Minecraft Server");

    public static final ModConfigSpec.ConfigValue<String> BOT_DISPLAY_NAME = BUILDER
            .comment("Name displayed before AstrBot messages in Minecraft.")
            .define("botDisplayName", "AstrBot");

    public static final ModConfigSpec.IntValue RECONNECT_SECONDS = BUILDER
            .comment("Reconnect delay after WebSocket disconnects.")
            .defineInRange("reconnectSeconds", 5, 1, 300);

    public static final ModConfigSpec.IntValue MAX_MESSAGE_LENGTH = BUILDER
            .comment("Maximum player chat length forwarded to AstrBot.")
            .defineInRange("maxMessageLength", 1000, 1, 4096);

    static final ModConfigSpec SPEC = BUILDER.build();

    private MineAstrConfig() {
    }
}
