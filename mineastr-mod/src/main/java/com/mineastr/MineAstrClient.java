package com.mineastr;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

public final class MineAstrClient {
    private static final String MIME_TYPE = "image/jpeg";

    private MineAstrClient() {
    }

    public static void init(ModContainer modContainer) {
        modContainer.registerExtensionPoint(
                IConfigScreenFactory.class,
                (container, parent) -> new ConfigurationScreen(container, parent));
        NeoForge.EVENT_BUS.register(MineAstrClient.class);
    }

    @SubscribeEvent
    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        PacketDistributor.sendToServer(new MineAstrPayloads.ClientHello(MineAstr.MOD_VERSION, true));
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
    }

    public static void handleScreenshotRequest(MineAstrPayloads.ScreenshotRequest request) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> handleScreenshotRequestOnClientThread(minecraft, request));
    }

    private static void handleScreenshotRequestOnClientThread(Minecraft minecraft, MineAstrPayloads.ScreenshotRequest request) {
        if (minecraft.player == null || minecraft.getConnection() == null) {
            sendError(request.requestId(), "not_in_game", "客户端尚未进入游戏，无法截图。");
            return;
        }

        MineAstrClientConfig.ScreenshotMode mode = MineAstrClientConfig.SCREENSHOT_MODE.get();
        if (mode == MineAstrClientConfig.ScreenshotMode.DISABLED) {
            sendError(request.requestId(), "disabled", "玩家已在 MineAstr 客户端配置中禁用截图发送。");
            return;
        }
        if (mode == MineAstrClientConfig.ScreenshotMode.AUTO) {
            captureAndSend(minecraft, request);
            return;
        }

        Screen previous = minecraft.screen;
        ConfirmScreen screen = new ConfirmScreen(
                confirmed -> {
                    minecraft.setScreen(previous);
                    if (confirmed) {
                        captureAndSend(minecraft, request);
                    } else {
                        sendError(request.requestId(), "denied", "玩家拒绝发送截图。");
                    }
                },
                Component.translatable("screen.mineastr.screenshot.title"),
                Component.translatable("screen.mineastr.screenshot.message", trimReason(request.reason())),
                Component.translatable("screen.mineastr.screenshot.allow"),
                Component.translatable("screen.mineastr.screenshot.deny"));
        minecraft.setScreen(screen);
    }

    private static void captureAndSend(Minecraft minecraft, MineAstrPayloads.ScreenshotRequest request) {
        try (NativeImage nativeImage = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            ScreenshotImage image = encodeLowResolutionScreenshot(nativeImage, request);
            sendChunks(request.requestId(), image);
        } catch (Exception exc) {
            MineAstr.LOGGER.warn("MineAstr 客户端截图失败：{}", exc.getMessage());
            sendError(request.requestId(), "capture_failed", "客户端截图失败：" + exc.getMessage());
        }
    }

    private static ScreenshotImage encodeLowResolutionScreenshot(
            NativeImage nativeImage,
            MineAstrPayloads.ScreenshotRequest request) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(nativeImage.asByteArray()));
        if (source == null) {
            throw new IOException("无法解码 Minecraft 截图。");
        }

        int maxWidth = clampPositive(Math.min(request.maxWidth(), MineAstrClientConfig.SCREENSHOT_MAX_WIDTH.getAsInt()), 64);
        int maxHeight = clampPositive(Math.min(request.maxHeight(), MineAstrClientConfig.SCREENSHOT_MAX_HEIGHT.getAsInt()), 36);
        int maxBytes = clampPositive(Math.min(request.maxBytes(), MineAstrClientConfig.SCREENSHOT_MAX_BYTES.getAsInt()), 8192);
        float quality = (float) MineAstrClientConfig.SCREENSHOT_JPEG_QUALITY.getAsDouble();

        int width = targetWidth(source.getWidth(), source.getHeight(), maxWidth, maxHeight);
        int height = Math.max(1, Math.round(source.getHeight() * (width / (float) source.getWidth())));
        byte[] encoded = new byte[0];

        for (int attempt = 0; attempt < 8; attempt++) {
            BufferedImage scaled = scaleToJpegImage(source, width, height);
            encoded = encodeJpeg(scaled, quality);
            if (encoded.length <= maxBytes || (width <= 64 && height <= 36)) {
                return new ScreenshotImage(width, height, encoded, System.currentTimeMillis());
            }
            quality = Math.max(0.12F, quality * 0.82F);
            width = Math.max(64, Math.round(width * 0.82F));
            height = Math.max(36, Math.round(height * 0.82F));
        }
        return new ScreenshotImage(width, height, encoded, System.currentTimeMillis());
    }

    private static int targetWidth(int sourceWidth, int sourceHeight, int maxWidth, int maxHeight) {
        float scale = Math.min(maxWidth / (float) sourceWidth, maxHeight / (float) sourceHeight);
        scale = Math.min(scale, 1.0F);
        return Math.max(1, Math.round(sourceWidth * scale));
    }

    private static BufferedImage scaleToJpegImage(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(Math.max(0.10F, Math.min(0.95F, quality)));
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                MemoryCacheImageOutputStream imageOutput = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, null), params);
            imageOutput.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static void sendChunks(String requestId, ScreenshotImage image) {
        int totalChunks = Math.max(1, (image.bytes.length + MineAstrPayloads.MAX_CHUNK_BYTES - 1) / MineAstrPayloads.MAX_CHUNK_BYTES);
        for (int index = 0; index < totalChunks; index++) {
            int start = index * MineAstrPayloads.MAX_CHUNK_BYTES;
            int end = Math.min(image.bytes.length, start + MineAstrPayloads.MAX_CHUNK_BYTES);
            byte[] chunk = Arrays.copyOfRange(image.bytes, start, end);
            PacketDistributor.sendToServer(new MineAstrPayloads.ScreenshotChunk(
                    requestId,
                    index,
                    totalChunks,
                    image.width,
                    image.height,
                    image.bytes.length,
                    image.capturedAtMs,
                    MIME_TYPE,
                    chunk));
        }
    }

    private static void sendError(String requestId, String code, String message) {
        PacketDistributor.sendToServer(new MineAstrPayloads.ScreenshotError(requestId, code, trimError(message)));
    }

    private static int clampPositive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static String trimReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "AstrBot 请求查看当前 Minecraft 画面。";
        }
        return reason.length() > 120 ? reason.substring(0, 120) : reason;
    }

    private static String trimError(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误。";
        }
        return message.length() > MineAstrPayloads.MAX_ERROR_LENGTH
                ? message.substring(0, MineAstrPayloads.MAX_ERROR_LENGTH)
                : message;
    }

    private record ScreenshotImage(int width, int height, byte[] bytes, long capturedAtMs) {
    }
}
