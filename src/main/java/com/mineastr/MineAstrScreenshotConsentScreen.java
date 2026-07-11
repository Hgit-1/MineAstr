package com.mineastr;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public final class MineAstrScreenshotConsentScreen extends Screen {
    private static final long DECISION_TIMEOUT_MS = 25_000L;
    private static final int PANEL_WIDTH = 420;
    private final Screen previous;
    private final String reason;
    private final Consumer<Boolean> decision;
    private final long deadline = System.currentTimeMillis() + DECISION_TIMEOUT_MS;
    private boolean decided;

    public MineAstrScreenshotConsentScreen(Screen previous, String reason, Consumer<Boolean> decision) {
        super(Component.translatable("screen.mineastr.screenshot.title"));
        this.previous = previous;
        this.reason = reason;
        this.decision = decision;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        int left = (width - panelWidth) / 2;
        int top = Math.max(36, (height - 190) / 2);
        int buttonWidth = Math.min(150, (panelWidth - 44) / 2);
        int buttonY = top + 150;
        addRenderableWidget(Button.builder(Component.translatable("screen.mineastr.screenshot.deny"), button -> finish(false))
                .bounds(left + 16, buttonY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mineastr.screenshot.allow"), button -> finish(true))
                .bounds(left + panelWidth - 16 - buttonWidth, buttonY, buttonWidth, 20).build());
    }

    @Override
    public void tick() {
        if (!decided && System.currentTimeMillis() >= deadline) {
            finish(false);
        }
    }

    @Override
    public void onClose() {
        finish(false);
    }

    private void finish(boolean allowed) {
        if (decided) {
            return;
        }
        decided = true;
        minecraft.setScreen(previous);
        decision.accept(allowed);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, 0xDD07111F, 0xEE12243A);
        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        int left = (width - panelWidth) / 2;
        int top = Math.max(36, (height - 190) / 2);
        graphics.fill(left - 2, top - 2, left + panelWidth + 2, top + 182, 0x6656E0BC);
        graphics.fill(left, top, left + panelWidth, top + 180, 0xF0101929);
        graphics.fill(left, top, left + 5, top + 180, 0xFF72E6C1);
        graphics.drawString(font, Component.literal("●").withColor(0x72E6C1), left + 18, top + 17, 0xFF72E6C1, false);
        graphics.drawString(font, title, left + 34, top + 17, 0xFFF4F8FF, false);
        graphics.drawString(font, Component.translatable("screen.mineastr.screenshot.privacy_badge"), left + 18, top + 38, 0xFF9FB1C9, false);

        List<FormattedCharSequence> explanation = font.split(
                Component.translatable("screen.mineastr.screenshot.explanation", reason), panelWidth - 36);
        int lineY = top + 60;
        for (FormattedCharSequence line : explanation) {
            if (lineY > top + 126) {
                break;
            }
            graphics.drawString(font, line, left + 18, lineY, 0xFFDCE6F5, false);
            lineY += font.lineHeight + 2;
        }
        long seconds = Math.max(0L, (deadline - System.currentTimeMillis() + 999L) / 1000L);
        graphics.drawString(font, Component.translatable("screen.mineastr.screenshot.timeout", seconds), left + 18, top + 133, 0xFF8EA0B9, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
