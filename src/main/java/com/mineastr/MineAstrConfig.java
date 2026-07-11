package com.mineastr;

import java.util.List;
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

    public static final ModConfigSpec.BooleanValue ENABLE_PLAYER_STATE_TOOL = BUILDER
            .comment("是否允许 AstrBot 查询在线玩家的生命、位置、维度、经验和状态效果。")
            .define("enablePlayerStateTool", true);

    public static final ModConfigSpec.BooleanValue ENABLE_INVENTORY_TOOL = BUILDER
            .comment(
                    "是否允许 AstrBot 查询在线玩家的背包、快捷栏、护甲和副手。",
                    "查询只返回物品 ID、显示名、数量和耐久，不返回完整 NBT。")
            .define("enableInventoryTool", true);

    public static final ModConfigSpec.BooleanValue ENABLE_NEARBY_ENTITIES_TOOL = BUILDER
            .comment("是否允许 AstrBot 查询玩家附近的实体摘要。")
            .define("enableNearbyEntitiesTool", true);

    public static final ModConfigSpec.BooleanValue ENABLE_REGION_TOOL = BUILDER
            .comment(
                    "是否允许 AstrBot 分析已加载区域的方块与建筑特征。",
                    "分析不会强制加载新区块，也不会读取容器内容或方块实体 NBT。")
            .define("enableRegionTool", true);

    public static final ModConfigSpec.IntValue REGION_MAX_BLOCKS = BUILDER
            .comment("单次区域特征分析最多扫描多少个方块。")
            .defineInRange("regionMaxBlocks", 32768, 4096, 131072);

    public static final ModConfigSpec.BooleanValue ENABLE_COMMAND_TOOL = BUILDER
            .comment(
                    "是否允许 AstrBot 请求执行服务器命令。默认关闭。",
                    "即使启用，仍必须同时通过 trustedCommandUsers 与 allowedCommandRules 检查。")
            .define("enableCommandTool", false);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> TRUSTED_COMMAND_USERS = BUILDER
            .comment(
                    "允许通过 AstrBot 命令工具发起请求的可信用户 ID、Minecraft UUID 或玩家名。",
                    "匹配不区分大小写；建议优先填写稳定 UUID。空列表表示没有任何可信人员。")
            .defineListAllowEmpty("trustedCommandUsers", List.of(), MineAstrConfig::isNonBlankString);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOWED_COMMAND_RULES = BUILDER
            .comment(
                    "命令工具允许执行的规则。普通条目只允许精确命令；以 ' *' 结尾表示允许该前缀及参数。",
                    "例如 'time query daytime' 只允许这一条；'say *' 允许 say 的任意参数；'*' 明确允许所有命令。",
                    "默认只开放少量只读命令。")
            .defineListAllowEmpty(
                    "allowedCommandRules",
                    List.of("list", "seed", "time query day", "time query daytime", "time query gametime"),
                    MineAstrConfig::isNonBlankString);

    public static final ModConfigSpec.IntValue COMMAND_PERMISSION_LEVEL = BUILDER
            .comment("命令工具执行命令时使用的权限等级。白名单检查始终优先执行。")
            .defineInRange("commandPermissionLevel", 4, 0, 4);

    public static final ModConfigSpec.IntValue COMMAND_MAX_LENGTH = BUILDER
            .comment("命令工具允许的最大命令长度。")
            .defineInRange("commandMaxLength", 256, 1, 1024);

    static final ModConfigSpec SPEC = BUILDER.build();

    private MineAstrConfig() {
    }

    private static boolean isNonBlankString(Object value) {
        return value instanceof String text && !text.isBlank() && text.length() <= 256;
    }
}
