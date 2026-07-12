package com.mineastr;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

public final class MineAstrConfigScreen extends Screen {
    private static final int PANEL_WIDTH = 430;
    private static final int PANEL_HEIGHT = 224;
    private static final int ACCENT = 0xFF72E6C1;
    private static final int TEXT = 0xFFF3F7FF;
    private static final int MUTED = 0xFFA8B4C8;

    private final Screen parent;
    private MineAstrClientConfig.ScreenshotMode screenshotMode;
    private int maxWidth;
    private int maxHeight;
    private double jpegQuality;
    private int maxBytes;

    public MineAstrConfigScreen(Screen parent) {
        super(Component.translatable("screen.mineastr.config.title"));
        this.parent = parent;
        loadValues();
    }

    private void loadValues() {
        screenshotMode = MineAstrClientConfig.SCREENSHOT_MODE.get();
        maxWidth = MineAstrClientConfig.SCREENSHOT_MAX_WIDTH.getAsInt();
        maxHeight = MineAstrClientConfig.SCREENSHOT_MAX_HEIGHT.getAsInt();
        jpegQuality = MineAstrClientConfig.SCREENSHOT_JPEG_QUALITY.getAsDouble();
        maxBytes = MineAstrClientConfig.SCREENSHOT_MAX_BYTES.getAsInt();
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        int left = (width - panelWidth) / 2;
        int controlLeft = left + 150;
        int controlWidth = panelWidth - 174;
        int top = Math.max(8, (height - PANEL_HEIGHT) / 2);
        int row = top + 48;

        CycleButton<MineAstrClientConfig.ScreenshotMode> modeButton = CycleButton
                .<MineAstrClientConfig.ScreenshotMode>builder(
                        mode -> Component.translatable("screen.mineastr.config.mode." + mode.name().toLowerCase()))
                .withValues(List.of(MineAstrClientConfig.ScreenshotMode.values()))
                .withInitialValue(screenshotMode)
                .create(controlLeft, row, controlWidth, 20, Component.empty(), (button, value) -> screenshotMode = value);
        addRenderableWidget(modeButton);

        row += 28;
        addRenderableWidget(new ValueSlider(
                controlLeft, row, controlWidth,
                "screen.mineastr.config.width", 64, 1024, maxWidth,
                value -> maxWidth = (int) Math.round(value), value -> Integer.toString((int) Math.round(value))));
        row += 28;
        addRenderableWidget(new ValueSlider(
                controlLeft, row, controlWidth,
                "screen.mineastr.config.height", 36, 1024, maxHeight,
                value -> maxHeight = (int) Math.round(value), value -> Integer.toString((int) Math.round(value))));
        row += 28;
        addRenderableWidget(new ValueSlider(
                controlLeft, row, controlWidth,
                "screen.mineastr.config.quality", 0.10, 0.95, jpegQuality,
                value -> jpegQuality = value, value -> Math.round(value * 100) + "%"));
        row += 28;
        addRenderableWidget(new ValueSlider(
                controlLeft, row, controlWidth,
                "screen.mineastr.config.bytes", 8192, 524288, maxBytes,
                value -> maxBytes = roundToStep((int) Math.round(value), 1024),
                value -> (roundToStep((int) Math.round(value), 1024) / 1024) + " KiB"));

        int buttonY = top + PANEL_HEIGHT - 30;
        int buttonGap = 6;
        int buttonWidth = (panelWidth - 24 - buttonGap * 3) / 4;
        addRenderableWidget(Button.builder(Component.translatable("screen.mineastr.config.reset"), button -> {
            resetDefaults();
            rebuildWidgets();
        }).bounds(left + 12, buttonY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mineastr.config.local_server"), button ->
                        minecraft.setScreen(new MineAstrLocalServerConfigScreen(this)))
                .bounds(left + 12 + buttonWidth + buttonGap, buttonY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + 12 + (buttonWidth + buttonGap) * 2, buttonY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mineastr.config.save"), button -> saveAndClose())
                .bounds(left + 12 + (buttonWidth + buttonGap) * 3, buttonY, buttonWidth, 20).build());
    }

    private void resetDefaults() {
        screenshotMode = MineAstrClientConfig.SCREENSHOT_MODE.getDefault();
        maxWidth = MineAstrClientConfig.SCREENSHOT_MAX_WIDTH.getDefault();
        maxHeight = MineAstrClientConfig.SCREENSHOT_MAX_HEIGHT.getDefault();
        jpegQuality = MineAstrClientConfig.SCREENSHOT_JPEG_QUALITY.getDefault();
        maxBytes = MineAstrClientConfig.SCREENSHOT_MAX_BYTES.getDefault();
    }

    private void saveAndClose() {
        MineAstrClientConfig.SCREENSHOT_MODE.set(screenshotMode);
        MineAstrClientConfig.SCREENSHOT_MAX_WIDTH.set(maxWidth);
        MineAstrClientConfig.SCREENSHOT_MAX_HEIGHT.set(maxHeight);
        MineAstrClientConfig.SCREENSHOT_JPEG_QUALITY.set(jpegQuality);
        MineAstrClientConfig.SCREENSHOT_MAX_BYTES.set(maxBytes);
        MineAstrClientConfig.SPEC.save();
        onClose();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, 0xFF08111F, 0xFF101D31);
        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        int left = (width - panelWidth) / 2;
        int top = Math.max(8, (height - PANEL_HEIGHT) / 2);
        graphics.fill(left - 2, top - 2, left + panelWidth + 2, top + PANEL_HEIGHT + 2, 0x553DE0B4);
        graphics.fill(left, top, left + panelWidth, top + PANEL_HEIGHT, 0xEE101827);
        graphics.fill(left, top, left + 4, top + PANEL_HEIGHT, ACCENT);

        graphics.drawString(font, title, left + 16, top + 12, TEXT, false);
        List<FormattedCharSequence> subtitle = font.split(Component.translatable("screen.mineastr.config.subtitle"), panelWidth - 32);
        if (!subtitle.isEmpty()) {
            graphics.drawString(font, subtitle.getFirst(), left + 16, top + 27, MUTED, false);
        }

        int labelY = top + 54;
        String[] labels = {
                "screen.mineastr.config.mode.label",
                "screen.mineastr.config.width.label",
                "screen.mineastr.config.height.label",
                "screen.mineastr.config.quality.label",
                "screen.mineastr.config.bytes.label"
        };
        for (String label : labels) {
            graphics.drawString(font, Component.translatable(label), left + 16, labelY, TEXT, false);
            labelY += 28;
        }
        graphics.drawString(font, Component.translatable("screen.mineastr.config.privacy"), left + 16, top + PANEL_HEIGHT - 43, MUTED, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int roundToStep(int value, int step) {
        return Math.max(step, Math.round(value / (float) step) * step);
    }

    @FunctionalInterface
    private interface ValueConsumer {
        void accept(double value);
    }

    @FunctionalInterface
    private interface ValueFormatter {
        String format(double value);
    }

    private static final class ValueSlider extends AbstractSliderButton {
        private final String translationKey;
        private final double min;
        private final double max;
        private final ValueConsumer consumer;
        private final ValueFormatter formatter;

        private ValueSlider(
                int x,
                int y,
                int width,
                String translationKey,
                double min,
                double max,
                double initial,
                ValueConsumer consumer,
                ValueFormatter formatter) {
            super(x, y, width, 20, Component.empty(), Mth.clamp((initial - min) / (max - min), 0.0, 1.0));
            this.translationKey = translationKey;
            this.min = min;
            this.max = max;
            this.consumer = consumer;
            this.formatter = formatter;
            updateMessage();
        }

        private double actualValue() {
            return Mth.lerp(value, min, max);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(translationKey, formatter.format(actualValue())));
        }

        @Override
        protected void applyValue() {
            consumer.accept(actualValue());
            updateMessage();
        }
    }
}
