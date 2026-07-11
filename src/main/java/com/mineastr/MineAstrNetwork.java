package com.mineastr;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class MineAstrNetwork {
    private MineAstrNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToServer(
                MineAstrPayloads.ClientHello.TYPE,
                MineAstrPayloads.ClientHello.CODEC,
                MineAstrNetwork::handleClientHello);
        registrar.playToServer(
                MineAstrPayloads.ScreenshotChunk.TYPE,
                MineAstrPayloads.ScreenshotChunk.CODEC,
                MineAstrNetwork::handleScreenshotChunk);
        registrar.playToServer(
                MineAstrPayloads.ScreenshotError.TYPE,
                MineAstrPayloads.ScreenshotError.CODEC,
                MineAstrNetwork::handleScreenshotError);
        registrar.playToClient(
                MineAstrPayloads.ScreenshotRequest.TYPE,
                MineAstrPayloads.ScreenshotRequest.CODEC,
                MineAstrNetwork::handleScreenshotRequest);
    }

    private static void handleClientHello(MineAstrPayloads.ClientHello payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                MineAstr.bridge().registerClientCapability(player, payload.screenshotSupported(), payload.modVersion());
            }
        });
    }

    private static void handleScreenshotChunk(MineAstrPayloads.ScreenshotChunk payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                MineAstr.bridge().receiveScreenshotChunk(player, payload);
            }
        });
    }

    private static void handleScreenshotError(MineAstrPayloads.ScreenshotError payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                MineAstr.bridge().receiveScreenshotError(player, payload.code(), payload.message(), payload.requestId());
            }
        });
    }

    private static void handleScreenshotRequest(MineAstrPayloads.ScreenshotRequest payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!FMLEnvironment.dist.isClient()) {
                return;
            }
            try {
                Class<?> clientClass = Class.forName("com.mineastr.MineAstrClient");
                clientClass.getMethod("handleScreenshotRequest", MineAstrPayloads.ScreenshotRequest.class).invoke(null, payload);
            } catch (ReflectiveOperationException exc) {
                MineAstr.LOGGER.warn("MineAstr 客户端截图处理器加载失败：{}", exc.getMessage());
            }
        });
    }
}
