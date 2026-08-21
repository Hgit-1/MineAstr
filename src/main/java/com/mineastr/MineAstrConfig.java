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

    public static final ModConfigSpec.ConfigValue<String> SERVER_INTRODUCTION_URL = BUILDER
            .comment(
                    "服务器官网或介绍页。仅独立服务器会把此地址交给 AstrBot 建立服务器介绍知识库。",
                    "留空表示禁用；建议使用无登录信息的公网 HTTPS 地址。此项不会显示在单人模式配置界面。")
            .define("serverIntroductionUrl", "");

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

    public static final ModConfigSpec.BooleanValue ENABLE_PLAYER_PRESENCE_PUSH = BUILDER
            .comment(
                    "是否把玩家加入和离开服务器的公开事件推送给 AstrBot。",
                    "推送包含玩家名和 UUID，但不包含 IP 地址或精确位置。")
            .define("enablePlayerPresencePush", true);

    public static final ModConfigSpec.BooleanValue ENABLE_PLAYER_DEATH_PUSH = BUILDER
            .comment(
                    "是否把玩家死亡消息推送给 AstrBot。",
                    "推送的是服务器已经生成的公开死亡消息，不额外发送坐标。")
            .define("enablePlayerDeathPush", true);

    public static final ModConfigSpec.BooleanValue ENABLE_ADVANCEMENT_PUSH = BUILDER
            .comment(
                    "是否把玩家完成的公开进度、目标和挑战推送给 AstrBot。",
                    "隐藏的配方解锁等技术进度不会推送。")
            .define("enableAdvancementPush", true);

    public static final ModConfigSpec.BooleanValue ENABLE_KNOWLEDGE_SCAN = BUILDER
            .comment(
                    "是否允许 MineAstr 扫描服务器 Mod、注册表、标签和运行时配方，供 AstrBot 按需检索。",
                    "扫描结果只包含公开注册数据和配方，不读取世界存档、玩家数据或方块实体 NBT。")
            .define("enableKnowledgeScan", true);

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

    public static final ModConfigSpec.BooleanValue ENABLE_ACTIVITY_TRACKING = BUILDER
            .comment(
                    "是否统计玩家在各区块的活动时长，以周期性生成服务器地区摘要。",
                    "服务器提供者负责提供适用的隐私与合规告知；玩家可用 /mineastr tracking optout 退出。")
            .define("enableActivityTracking", true);

    public static final ModConfigSpec.IntValue ACTIVITY_SAMPLE_SECONDS = BUILDER
            .comment("玩家活动区块采样间隔（秒）。")
            .defineInRange("activitySampleSeconds", 60, 10, 3600);

    public static final ModConfigSpec.IntValue ENVIRONMENT_SAMPLE_MINUTES = BUILDER
            .comment("活动区块环境摘要采样间隔（分钟）；不会强制加载区块。")
            .defineInRange("environmentSampleMinutes", 30, 5, 1440);

    public static final ModConfigSpec.BooleanValue ENABLE_AUTOMATIC_REGION_FEATURE_SCAN = BUILDER
            .comment(
                    "是否在环境摘要采样时统计玩家周边的建筑与机器特征。",
                    "只扫描已加载区块，不读取容器、告示牌、方块实体 NBT 或精确建筑形状。")
            .define("enableAutomaticRegionFeatureScan", true);

    public static final ModConfigSpec.IntValue AUTOMATIC_REGION_SCAN_HORIZONTAL_RADIUS = BUILDER
            .comment("自动地区特征采样的水平半径（格）。")
            .defineInRange("automaticRegionScanHorizontalRadius", 8, 2, 24);

    public static final ModConfigSpec.IntValue AUTOMATIC_REGION_SCAN_VERTICAL_RADIUS = BUILDER
            .comment("自动地区特征采样的垂直半径（格）。")
            .defineInRange("automaticRegionScanVerticalRadius", 6, 2, 16);

    public static final ModConfigSpec.IntValue ACTIVITY_ANALYSIS_DAYS = BUILDER
            .comment("地区活动分析周期（天）。")
            .defineInRange("activityAnalysisDays", 28, 1, 365);

    public static final ModConfigSpec.IntValue ACTIVITY_RETENTION_DAYS = BUILDER
            .comment("可归属于玩家的原始活动数据保存天数。")
            .defineInRange("activityRetentionDays", 84, 7, 730);

    public static final ModConfigSpec.IntValue MINIMUM_REGION_MINUTES = BUILDER
            .comment("一组相连区块成为活动地区所需的最少累计活动分钟数。")
            .defineInRange("minimumRegionMinutes", 30, 1, 10080);

    public static final ModConfigSpec.IntValue MINIMUM_REGION_CHUNK_MINUTES = BUILDER
            .comment("单个区块进入地区聚类所需的最少累计活动分钟数。")
            .defineInRange("minimumRegionChunkMinutes", 2, 1, 1440);

    public static final ModConfigSpec.BooleanValue ENABLE_AGENT = BUILDER
            .comment(
                    "是否由 MineAstr 服务端 Mod 启动内置 Mineflayer Agent。默认关闭，配置账号后再启用。",
                    "Agent 使用独立 Node 子进程；缺少兼容 Node 时会安全禁用，不影响其他功能。")
            .define("enableAgent", false);

    public static final ModConfigSpec.ConfigValue<String> AGENT_NODE_EXECUTABLE = BUILDER
            .comment("Node 可执行文件路径；留空或 node 表示从 PATH 查找。要求 Node.js 22 或更高版本。")
            .define("agentNodeExecutable", "node", MineAstrConfig::isNonBlankString);

    public static final ModConfigSpec.BooleanValue AGENT_AUTO_DOWNLOAD_NODE = BUILDER
            .comment(
                    "找不到兼容 Node 时是否下载 MineAstr 固定版本并校验 SHA-256。默认关闭。",
                    "自动下载只支持已列入 MineAstr 校验表的平台，不执行未知文件。")
            .define("agentAutoDownloadNode", false);

    public static final ModConfigSpec.ConfigValue<String> AGENT_ACCOUNT_MODE = BUILDER
            .comment("Bot 登录模式：offline 或 microsoft。离线模式存在身份冒用风险。")
            .define("agentAccountMode", "offline", value -> value instanceof String text
                    && ("offline".equalsIgnoreCase(text) || "microsoft".equalsIgnoreCase(text)));

    public static final ModConfigSpec.ConfigValue<String> AGENT_USERNAME = BUILDER
            .comment("Agent 专用玩家名；建议加入白名单，不要与真人账号共用。")
            .define("agentUsername", "MineAstrBot", value -> value instanceof String text
                    && text.matches("[A-Za-z0-9_]{3,16}"));

    public static final ModConfigSpec.ConfigValue<String> AGENT_SERVER_HOST = BUILDER
            .comment("Mineflayer 连接地址。服务端同机运行时保持 127.0.0.1。")
            .define("agentServerHost", "127.0.0.1", value -> value instanceof String text
                    && !text.isBlank() && text.length() <= 253 && text.indexOf('\n') < 0);

    public static final ModConfigSpec.IntValue AGENT_SERVER_PORT = BUILDER
            .comment("Mineflayer 连接的 Minecraft 端口。")
            .defineInRange("agentServerPort", 25565, 1, 65535);

    public static final ModConfigSpec.BooleanValue AGENT_PROXY_PROTOCOL = BUILDER
            .comment(
                    "Mineflayer 连接时是否先发送 HAProxy PROXY protocol v1 头。默认关闭。",
                    "仅当服务端的 Proxy Protocol Mod/代理对 Agent 连接地址启用了该协议时打开，",
                    "否则 Minecraft 会把该头当成无效握手。为避免伪造来源，MineAstr 只允许同机地址使用。")
            .define("agentProxyProtocol", false);

    public static final ModConfigSpec.BooleanValue AGENT_FULL_AUTONOMY = BUILDER
            .comment(
                    "是否允许 Agent 在服务端安全边界内自主执行任务。默认开启。",
                    "AstrBot 可额外启用分级审批；服务端禁区、预算和紧急停止始终生效。")
            .define("agentFullAutonomy", true);

    public static final ModConfigSpec.BooleanValue AGENT_NEOFORGE_COMPATIBILITY = BUILDER
            .comment(
                    "是否允许同机 Mineflayer Bot 使用 MineAstr 限定的 NeoForge 协议兼容层。默认开启。",
                    "它只向 127.0.0.1/localhost/::1 上的 Bot 提供本服务器必需频道清单，不关闭 NeoForge 的全局校验。",
                    "Bot 会忽略无法解析的 Mod 自定义数据，因此兼容模式下仍应通过观察结果确认任务。")
            .define("agentNeoForgeCompatibility", true);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> AGENT_FORBIDDEN_REGIONS = BUILDER
            .comment("Agent 禁止进入或改变的区域，格式 dimension,minX,minY,minZ,maxX,maxY,maxZ。")
            .defineListAllowEmpty("agentForbiddenRegions", List.of(), MineAstrConfig::isNonBlankString);

    public static final ModConfigSpec.ConfigValue<String> AGENT_CLIENT_INSTANCE_PATH = BUILDER
            .comment("独立、客户端可用的 NeoForge 实例目录；留空表示禁用临时 with-mod 渲染。")
            .define("agentClientInstancePath", "", value -> value instanceof String text && text.length() <= 1024);

    public static final ModConfigSpec.IntValue AGENT_RENDERER_MIN_FREE_MEMORY_MB = BUILDER
            .comment("启动临时模组客户端前要求的最小可用内存，默认 3072MB。")
            .defineInRange("agentRendererMinFreeMemoryMb", 3072, 1024, 65536);

    public static final ModConfigSpec.IntValue AGENT_RENDERER_MAX_MINUTES = BUILDER
            .comment("单次模组客户端观察会话最长分钟数。")
            .defineInRange("agentRendererMaxMinutes", 10, 1, 120);

    public static final ModConfigSpec.BooleanValue ENABLE_PASSIVE_SKILL_LEARNING = BUILDER
            .comment(
                    "是否观察全服高信息量 Mod 设备交互以形成候选技能。默认关闭。",
                    "启用前应更新告知；普通行走和挖矿不会进入技能学习。")
            .define("enablePassiveSkillLearning", false);

    public static final ModConfigSpec.ConfigValue<String> SKILL_SANDBOX_REGION = BUILDER
            .comment("候选技能验证沙盒，格式 dimension,minX,minY,minZ,maxX,maxY,maxZ；留空时禁止自动发布技能。")
            .define("skillSandboxRegion", "", value -> value instanceof String text && text.length() <= 256);

    public static final ModConfigSpec.BooleanValue ENABLE_PRIVACY_NOTICE = BUILDER
            .comment(
                    "是否在玩家首次加入以及告知版本变化时显示简要数据告知。",
                    "关闭内置告知不免除服务器提供者自行履行适用规则的责任。")
            .define("enablePrivacyNotice", true);

    public static final ModConfigSpec.ConfigValue<String> PRIVACY_NOTICE_TEXT = BUILDER
            .comment(
                    "加入服务器时显示的简要告知；可使用 \\n 换行，最长 2000 字符。",
                    "支持 {server_name} 和 {retention_days} 占位符。")
            .define(
                    "privacyNoticeText",
                    "本服使用 MineAstr 按区块统计活动，并摘要采集玩家周边已加载方块与建筑特征；服务器还可运行 AI 玩家 Bot，并在服主显式启用后观察高信息量设备交互及非 NBT 的容器差量以学习设备用法。普通聊天和公开服务器事件也可转发给 AstrBot。不保存完整容器快照、告示牌文字或完整建筑蓝图。原始活动最多保存 {retention_days} 天。使用 /mineastr privacy 查看详情，/mineastr tracking optout 与 /mineastr learning optout 可分别退出。",
                    value -> value instanceof String text && text.length() <= 2000);

    public static final ModConfigSpec.ConfigValue<String> PRIVACY_NOTICE_VERSION = BUILDER
            .comment("简要告知版本。修改后，所有玩家下次加入时会重新看到告知。")
            .define("privacyNoticeVersion", "4", value -> value instanceof String text && !text.isBlank() && text.length() <= 64);

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

    public static String renderPrivacyNotice() {
        return PRIVACY_NOTICE_TEXT.get()
                .replace("\\n", "\n")
                .replace("{retention_days}", Integer.toString(ACTIVITY_RETENTION_DAYS.getAsInt()))
                .replace("{server_name}", SERVER_NAME.get())
                .strip();
    }

    public static void migrateDefaultPrivacyNotice() {
        String oldText = "本服使用 MineAstr 按区块统计活动并由 AI 生成地区介绍；普通聊天以及玩家上下线、死亡和公开成就事件也可转发给 AstrBot。原始活动最多保存 {retention_days} 天。使用 /mineastr privacy 查看详情，/mineastr tracking optout 可退出并删除可识别活动数据。";
        if ("2".equals(PRIVACY_NOTICE_VERSION.get()) && oldText.equals(PRIVACY_NOTICE_TEXT.get())) {
            PRIVACY_NOTICE_TEXT.set(PRIVACY_NOTICE_TEXT.getDefault());
            PRIVACY_NOTICE_VERSION.set("4");
            SPEC.save();
            MineAstr.LOGGER.info("MineAstr 已将未修改的默认数据告知升级为版本 4。");
            return;
        }
        String version3Text = "本服使用 MineAstr 按区块统计活动，并摘要采集玩家周边已加载方块的类型与建筑特征，由 AI 生成地区介绍；普通聊天以及玩家上下线、死亡和公开成就事件也可转发给 AstrBot。不读取容器内容、告示牌文字或完整建筑蓝图。原始活动最多保存 {retention_days} 天。使用 /mineastr privacy 查看详情，/mineastr tracking optout 可退出并删除可识别活动数据。";
        if ("3".equals(PRIVACY_NOTICE_VERSION.get()) && version3Text.equals(PRIVACY_NOTICE_TEXT.get())) {
            PRIVACY_NOTICE_TEXT.set(PRIVACY_NOTICE_TEXT.getDefault());
            PRIVACY_NOTICE_VERSION.set("4");
            SPEC.save();
            MineAstr.LOGGER.info("MineAstr 已将未修改的默认数据告知升级为版本 4。");
        }
    }

    private static boolean isNonBlankString(Object value) {
        return value instanceof String text && !text.isBlank() && text.length() <= 256;
    }
}
