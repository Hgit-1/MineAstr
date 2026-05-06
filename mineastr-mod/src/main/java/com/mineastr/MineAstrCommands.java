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
        String state;
        if (!MineAstrConfig.ENABLED.getAsBoolean()) {
            state = "disabled";
        } else if (bridge.isConnected()) {
            state = "connected";
        } else if (bridge.isConnecting()) {
            state = "connecting";
        } else {
            state = "disconnected";
        }
        source.sendSuccess(() -> Component.literal("MineAstr status: " + state), false);
        return 1;
    }

    private static int reconnect(CommandSourceStack source, MineAstrBridge bridge) {
        bridge.reconnect();
        source.sendSuccess(() -> Component.literal("MineAstr reconnect requested."), false);
        return 1;
    }
}
