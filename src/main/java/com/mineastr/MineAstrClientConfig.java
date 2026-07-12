package com.mineastr;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class MineAstrClientConfig {
    public enum ScreenshotMode {
        ASK,
        AUTO,
        DISABLED
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOCAL_WORLD_SERVER_ENABLED = BUILDER
            .comment(
                    "是否在本地单人世界中启动 MineAstr 集成服务器桥接。",
                    "默认关闭；独立服务器不受此客户端选项影响。")
            .define("localWorldServerEnabled", false);

    public static final ModConfigSpec.EnumValue<ScreenshotMode> SCREENSHOT_MODE = BUILDER
            .comment(
                    "AstrBot 请求截图时客户端如何处理。",
                    "ASK：弹出确认界面，玩家同意后发送；AUTO：自动发送；DISABLED：始终拒绝发送。")
            .defineEnum("screenshotMode", ScreenshotMode.ASK);

    public static final ModConfigSpec.IntValue SCREENSHOT_MAX_WIDTH = BUILDER
            .comment(
                    "发送给 AstrBot 的截图最大宽度。",
                    "数值越大越清晰，但会占用更多网络和模型上下文。")
            .defineInRange("screenshotMaxWidth", 240, 64, 1024);

    public static final ModConfigSpec.IntValue SCREENSHOT_MAX_HEIGHT = BUILDER
            .comment(
                    "发送给 AstrBot 的截图最大高度。",
                    "建议保持较低数值，避免暴露过多画面细节。")
            .defineInRange("screenshotMaxHeight", 135, 36, 1024);

    public static final ModConfigSpec.DoubleValue SCREENSHOT_JPEG_QUALITY = BUILDER
            .comment(
                    "截图 JPEG 质量，范围 0.10 到 0.95。",
                    "数值越高越清晰，文件也越大；默认 0.35 适合低清晰度问答。")
            .defineInRange("screenshotJpegQuality", 0.35, 0.10, 0.95);

    public static final ModConfigSpec.IntValue SCREENSHOT_MAX_BYTES = BUILDER
            .comment(
                    "单张截图编码后的最大字节数。",
                    "超过限制时客户端会自动降低质量和尺寸后再发送。")
            .defineInRange("screenshotMaxBytes", 131072, 8192, 524288);

    static final ModConfigSpec SPEC = BUILDER.build();

    private MineAstrClientConfig() {
    }
}
