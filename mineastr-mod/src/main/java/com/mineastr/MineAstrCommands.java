package com.mineastr;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class MineAstrCommands {
    private MineAstrCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, MineAstrBridge bridge) {
        dispatcher.register(Commands.literal("mineastr")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource(), bridge)))
                .then(Commands.literal("reconnect")
                        .executes(context -> reconnect(context.getSource(), bridge))));
    }

    private static int status(CommandSourceStack source, MineAstrBridge bridge) {
        String stateKey;
        if (!MineAstrConfig.ENABLED.getAsBoolean()) {
            stateKey = "commands.mineastr.status.disabled";
        } else if (bridge.isConnected()) {
            stateKey = "commands.mineastr.status.connected";
        } else if (bridge.isConnecting()) {
            stateKey = "commands.mineastr.status.connecting";
        } else {
            stateKey = "commands.mineastr.status.disconnected";
        }
        source.sendSuccess(() -> Component.translatable("commands.mineastr.status", Component.translatable(stateKey)), false);
        return 1;
    }

    private static int reconnect(CommandSourceStack source, MineAstrBridge bridge) {
        bridge.reconnect();
        source.sendSuccess(() -> Component.translatable("commands.mineastr.reconnect"), false);
        return 1;
    }
}
