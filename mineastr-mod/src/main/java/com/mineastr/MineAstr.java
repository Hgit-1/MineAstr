package com.mineastr;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

@Mod(MineAstr.MODID)
public final class MineAstr {
    public static final String MODID = "mineastr";
    public static final String MOD_VERSION = "0.1.0";
    public static final Logger LOGGER = LogUtils.getLogger();

    private final MineAstrBridge bridge = new MineAstrBridge();

    public MineAstr(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.register(this);
        modContainer.registerConfig(ModConfig.Type.COMMON, MineAstrConfig.SPEC);
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
}
