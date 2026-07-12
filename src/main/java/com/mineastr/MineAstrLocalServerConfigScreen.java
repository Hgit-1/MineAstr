package com.mineastr;

import java.net.URI;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

public final class MineAstrLocalServerConfigScreen extends Screen {
    private static final int PANEL_WIDTH = 460;
    private static final int PANEL_HEIGHT = 236;
    private static final int ACCENT = 0xFF72E6C1;
    private static final int TEXT = 0xFFF3F7FF;
    private static final int MUTED = 0xFFA8B4C8;
    private static final int ERROR = 0xFFFF8E8E;

    private final Screen parent;
    private boolean localWorldEnabled;
    private String websocketUrl;
    private String token;
    private String serverId;
    private String serverName;
    private SwitchButton localEnabledSwitch;
    private EditBox websocketUrlField;
    private EditBox tokenField;
    private EditBox serverIdField;
    private EditBox serverNameField;
    private Component validationMessage = Component.empty();

    public MineAstrLocalServerConfigScreen(Screen parent) {
        super(Component.translatable("screen.mineastr.local_server.title"));
        this.parent = parent;
        this.localWorldEnabled = MineAstrClientConfig.LOCAL_WORLD_SERVER_ENABLED.getAsBoolean();
        this.websocketUrl = MineAstrConfig.WEBSOCKET_URL.get();
        this.token = MineAstrConfig.TOKEN.get();
        this.serverId = MineAstrConfig.SERVER_ID.get();
        this.serverName = MineAstrConfig.SERVER_NAME.get();
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        int left = (width - panelWidth) / 2;
        int top = Math.max(2, (height - PANEL_HEIGHT) / 2);
        int controlLeft = left + 158;
        int controlWidth = panelWidth - 182;
        int row = top + 48;

        localEnabledSwitch = new SwitchButton(
                controlLeft,
                row,
                controlWidth,
                localWorldEnabled,
                value -> localWorldEnabled = value);
        addRenderableWidget(localEnabledSwitch);

        row += 28;
        websocketUrlField = editBox(controlLeft, row, controlWidth, 256, websocketUrl);
        websocketUrlField.setResponder(value -> websocketUrl = value);
        websocketUrlField.setHint(Component.literal("ws://127.0.0.1:8765/ws"));
        addRenderableWidget(websocketUrlField);

        row += 28;
        tokenField = editBox(controlLeft, row, controlWidth, 256, token);
        tokenField.setResponder(value -> token = value);
        tokenField.setFormatter((value, index) -> FormattedCharSequence.forward("•".repeat(value.length()), Style.EMPTY));
        tokenField.setHint(Component.translatable("screen.mineastr.local_server.token_hint"));
        addRenderableWidget(tokenField);

        row += 28;
        serverIdField = editBox(controlLeft, row, controlWidth, 64, serverId);
        serverIdField.setResponder(value -> serverId = value);
        addRenderableWidget(serverIdField);

        row += 28;
        serverNameField = editBox(controlLeft, row, controlWidth, 64, serverName);
        serverNameField.setResponder(value -> serverName = value);
        addRenderableWidget(serverNameField);

        int buttonY = top + PANEL_HEIGHT - 30;
        int buttonWidth = Math.min(126, (panelWidth - 36) / 3);
        addRenderableWidget(Button.builder(Component.translatable("screen.mineastr.config.reset"), button -> resetDefaults())
                .bounds(left + 12, buttonY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + panelWidth - 12 - buttonWidth * 2 - 8, buttonY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mineastr.config.save"), button -> saveAndClose())
                .bounds(left + panelWidth - 12 - buttonWidth, buttonY, buttonWidth, 20).build());
    }

    private EditBox editBox(int x, int y, int width, int maxLength, String value) {
        EditBox field = new EditBox(font, x, y, width, 20, Component.empty());
        field.setMaxLength(maxLength);
        field.setValue(value == null ? "" : value);
        field.setTextShadow(false);
        return field;
    }

    private void resetDefaults() {
        localWorldEnabled = MineAstrClientConfig.LOCAL_WORLD_SERVER_ENABLED.getDefault();
        websocketUrl = MineAstrConfig.WEBSOCKET_URL.getDefault();
        token = MineAstrConfig.TOKEN.getDefault();
        serverId = MineAstrConfig.SERVER_ID.getDefault();
        serverName = MineAstrConfig.SERVER_NAME.getDefault();
        websocketUrlField.setValue(websocketUrl);
        tokenField.setValue(token);
        serverIdField.setValue(serverId);
        serverNameField.setValue(serverName);
        localEnabledSwitch.setValue(localWorldEnabled);
        validationMessage = Component.empty();
    }

    private void saveAndClose() {
        websocketUrl = websocketUrl.strip();
        token = token.strip();
        serverId = serverId.strip();
        serverName = serverName.strip();
        if (!isValidWebSocketUrl(websocketUrl)) {
            validationMessage = Component.translatable("screen.mineastr.local_server.error_url");
            return;
        }
        if (serverId.isEmpty()) {
            validationMessage = Component.translatable("screen.mineastr.local_server.error_id");
            return;
        }
        if (serverName.isEmpty()) {
            validationMessage = Component.translatable("screen.mineastr.local_server.error_name");
            return;
        }

        MineAstrClientConfig.LOCAL_WORLD_SERVER_ENABLED.set(localWorldEnabled);
        MineAstrConfig.WEBSOCKET_URL.set(websocketUrl);
        MineAstrConfig.TOKEN.set(token);
        MineAstrConfig.SERVER_ID.set(serverId);
        MineAstrConfig.SERVER_NAME.set(serverName);
        MineAstrClientConfig.SPEC.save();
        MineAstrConfig.SPEC.save();
        MineAstrClient.applyLocalWorldServerSettings(localWorldEnabled);
        onClose();
    }

    private static boolean isValidWebSocketUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return ("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
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
        int top = Math.max(2, (height - PANEL_HEIGHT) / 2);
        graphics.fill(left - 2, top - 2, left + panelWidth + 2, top + PANEL_HEIGHT + 2, 0x553DE0B4);
        graphics.fill(left, top, left + panelWidth, top + PANEL_HEIGHT, 0xEE101827);
        graphics.fill(left, top, left + 4, top + PANEL_HEIGHT, ACCENT);
        graphics.drawString(font, title, left + 16, top + 12, TEXT, false);
        var subtitleLines = font.split(Component.translatable("screen.mineastr.local_server.subtitle"), panelWidth - 32);
        for (int index = 0; index < Math.min(2, subtitleLines.size()); index++) {
            graphics.drawString(font, subtitleLines.get(index), left + 16, top + 27 + index * 10, MUTED, false);
        }

        int labelY = top + 54;
        String[] labels = {
                "screen.mineastr.local_server.enabled",
                "screen.mineastr.local_server.websocket_url",
                "screen.mineastr.local_server.token",
                "screen.mineastr.local_server.server_id",
                "screen.mineastr.local_server.server_name"
        };
        for (String label : labels) {
            graphics.drawString(font, Component.translatable(label), left + 16, labelY, TEXT, false);
            labelY += 28;
        }
        Component footer = validationMessage.getString().isEmpty()
                ? Component.translatable("screen.mineastr.local_server.note")
                : validationMessage;
        var footerLines = font.split(footer, panelWidth - 32);
        if (!footerLines.isEmpty()) {
            graphics.drawString(font, footerLines.getFirst(), left + 16, top + PANEL_HEIGHT - 43,
                    validationMessage.getString().isEmpty() ? MUTED : ERROR, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class SwitchButton extends AbstractButton {
        private boolean value;
        private final Consumer<Boolean> changed;

        private SwitchButton(int x, int y, int width, boolean initialValue, Consumer<Boolean> changed) {
            super(x, y, width, 20, Component.empty());
            this.value = initialValue;
            this.changed = changed;
            updateMessage();
        }

        @Override
        public void onPress() {
            value = !value;
            updateMessage();
            changed.accept(value);
        }

        private void updateMessage() {
            setMessage(Component.translatable(value
                    ? "screen.mineastr.local_server.switch_on"
                    : "screen.mineastr.local_server.switch_off"));
        }

        private void setValue(boolean value) {
            this.value = value;
            updateMessage();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int border = isHoveredOrFocused() ? 0xFFB9FFF0 : 0xFF52657C;
            int background = value ? 0xFF173D36 : 0xFF202938;
            graphics.fill(getX(), getY(), getRight(), getBottom(), border);
            graphics.fill(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1, background);
            graphics.drawString(Minecraft.getInstance().font, getMessage(), getX() + 8, getY() + 6,
                    value ? 0xFFB9FFF0 : 0xFFCCD5E2, false);

            int trackRight = getRight() - 8;
            int trackLeft = trackRight - 38;
            int trackTop = getY() + 4;
            int trackBottom = getY() + 16;
            graphics.fill(trackLeft, trackTop, trackRight, trackBottom, value ? 0xFF36B894 : 0xFF566273);
            int knobLeft = value ? trackRight - 11 : trackLeft + 1;
            graphics.fill(knobLeft, trackTop + 1, knobLeft + 10, trackBottom - 1, 0xFFF1F6FA);
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
