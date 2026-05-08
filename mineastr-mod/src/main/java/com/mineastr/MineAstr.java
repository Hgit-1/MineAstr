package com.mineastr;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

@Mod(MineAstr.MODID)
public final class MineAstr {
    public static final String MODID = "mineastr";
    public static final String MOD_VERSION = "0.3.0";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static MineAstrBridge activeBridge;

    private final MineAstrBridge bridge;

    public MineAstr(IEventBus modEventBus, ModContainer modContainer) {
        this.bridge = new MineAstrBridge();
        activeBridge = this.bridge;
        NeoForge.EVENT_BUS.register(this);
        modEventBus.addListener(MineAstrNetwork::register);
        modContainer.registerConfig(ModConfig.Type.COMMON, MineAstrConfig.SPEC);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modContainer.registerConfig(ModConfig.Type.CLIENT, MineAstrClientConfig.SPEC);
            initClient(modContainer);
        }
    }

    public static MineAstrBridge bridge() {
        if (activeBridge == null) {
            activeBridge = new MineAstrBridge();
        }
        return activeBridge;
    }

    private static void initClient(ModContainer modContainer) {
        try {
            Class<?> clientClass = Class.forName("com.mineastr.MineAstrClient");
            clientClass.getMethod("init", ModContainer.class).invoke(null, modContainer);
        } catch (ReflectiveOperationException exc) {
            LOGGER.warn("MineAstr 客户端初始化失败：{}", exc.getMessage());
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        bridge.start(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        bridge.stop();
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        bridge.forwardChat(event.getPlayer(), event.getRawText());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        MineAstrCommands.register(event.getDispatcher(), bridge);
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            bridge.unregisterClientCapability(player);
        }
    }
}
