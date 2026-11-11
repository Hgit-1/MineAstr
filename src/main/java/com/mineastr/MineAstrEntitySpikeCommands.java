package com.mineastr;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;

final class MineAstrEntitySpikeCommands {
    private MineAstrEntitySpikeCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, MineAstrServerEntitySpike spike) {
        dispatcher.register(Commands.literal("mineastr")
                .then(Commands.literal("entity-spike")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(context -> report(context, spike)))
                        .then(Commands.literal("observe").executes(context -> report(context, spike)))
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(context -> execute(context, () -> spike.spawn(context.getSource().getLevel(),
                                                BlockPosArgument.getBlockPos(context, "pos"))))))
                        .then(Commands.literal("goto")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(context -> execute(context, () -> spike.goTo(BlockPosArgument.getBlockPos(context, "pos"))))))
                        .then(Commands.literal("dig")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(context -> execute(context, () -> spike.dig(BlockPosArgument.getBlockPos(context, "pos"))))))
                        .then(Commands.literal("place")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(context -> execute(context, () -> spike.place(BlockPosArgument.getBlockPos(context, "pos"))))))
                        .then(Commands.literal("select")
                                .then(Commands.argument("slot", IntegerArgumentType.integer(0, 8))
                                        .executes(context -> execute(context, () -> spike.select(IntegerArgumentType.getInteger(context, "slot"))))))
                        .then(Commands.literal("deposit")
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                        .executes(context -> transfer(context, spike, false))))
                        .then(Commands.literal("withdraw")
                                .then(Commands.argument("slot", IntegerArgumentType.integer(0, 35))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                .executes(context -> transfer(context, spike, true)))))
                        .then(Commands.literal("stop").executes(context -> execute(context, spike::cancel)))
                        .then(Commands.literal("despawn").executes(context -> execute(context, spike::despawn)))));
    }

    private static int report(CommandContext<CommandSourceStack> context, MineAstrServerEntitySpike spike) {
        context.getSource().sendSuccess(() -> Component.literal(spike.status().toString()), false);
        return 1;
    }

    private static int transfer(CommandContext<CommandSourceStack> context, MineAstrServerEntitySpike spike, boolean withdraw)
            throws CommandSyntaxException {
        var operator = context.getSource().getPlayerOrException();
        int slot = withdraw ? IntegerArgumentType.getInteger(context, "slot") : 0;
        int count = IntegerArgumentType.getInteger(context, "count");
        return execute(context, () -> {
            int moved = spike.transfer(operator, slot, count, withdraw);
            context.getSource().sendSuccess(() -> Component.literal("实验背包实际转移数量：" + moved), false);
        });
    }

    private static int execute(CommandContext<CommandSourceStack> context, Runnable action) {
        try {
            action.run();
            MineAstr.LOGGER.info("MineAstr entity-spike operator={} action={}", context.getSource().getTextName(), context.getInput());
            context.getSource().sendSuccess(() -> Component.literal("Phase 0 实验操作已受理；使用 status 核实最终状态。"), false);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException exc) {
            context.getSource().sendFailure(Component.literal(exc.getMessage()));
            return 0;
        }
    }
}
