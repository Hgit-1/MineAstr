package com.mineastr;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class MineAstrCommands {
    private MineAstrCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, MineAstrBridge bridge) {
        dispatcher.register(Commands.literal("mineastr")
                .then(Commands.literal("status")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> status(context.getSource(), bridge)))
                .then(Commands.literal("reconnect")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> reconnect(context.getSource(), bridge)))
                .then(Commands.literal("privacy")
                        .executes(context -> privacy(context.getSource())))
                .then(Commands.literal("tracking")
                        .then(Commands.literal("status")
                                .executes(context -> trackingStatus(context.getSource(), bridge)))
                        .then(Commands.literal("optout")
                                .executes(context -> tracking(context.getSource(), bridge, true)))
                        .then(Commands.literal("optin")
                                .executes(context -> tracking(context.getSource(), bridge, false))))
                .then(Commands.literal("regions")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("analyze-now")
                                .executes(context -> analyzeNow(context.getSource(), bridge))))
                .then(Commands.literal("knowledge")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status")
                                .executes(context -> knowledgeStatus(context.getSource(), bridge)))
                        .then(Commands.literal("rescan")
                                .executes(context -> knowledgeRescan(context.getSource(), bridge)))
                        .then(Commands.literal("rescan-status")
                                .executes(context -> knowledgeStatus(context.getSource(), bridge)))));
    }

    private static int status(CommandSourceStack source, MineAstrBridge bridge) {
        String stateKey;
        if (!MineAstrConfig.ENABLED.getAsBoolean()) {
            stateKey = "commands.mineastr.status.disabled";
        } else if (!bridge.isStarted()) {
            stateKey = "commands.mineastr.status.inactive";
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
        if (bridge.reconnect()) {
            source.sendSuccess(() -> Component.translatable("commands.mineastr.reconnect"), false);
            return 1;
        }
        source.sendFailure(Component.translatable("commands.mineastr.reconnect.unavailable"));
        return 0;
    }

    private static int privacy(CommandSourceStack source) {
        String notice = MineAstrConfig.renderPrivacyNotice();
        if (notice.isEmpty()) notice = "服务器提供者未配置 MineAstr 简要数据告知，请联系管理员了解数据处理规则。";
        for (String line : notice.split("\\n")) source.sendSuccess(() -> Component.literal("[MineAstr] " + line), false);
        source.sendSuccess(() -> Component.literal("活动统计：" + (MineAstrConfig.ENABLE_ACTIVITY_TRACKING.getAsBoolean() ? "已启用" : "已禁用")
                + "；原始数据最长保留 " + MineAstrConfig.ACTIVITY_RETENTION_DAYS.getAsInt() + " 天。"), false);
        return 1;
    }

    private static int trackingStatus(CommandSourceStack source, MineAstrBridge bridge) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        boolean active = MineAstrConfig.ENABLE_ACTIVITY_TRACKING.getAsBoolean() && !bridge.isActivityOptedOut(player.getUUID());
        source.sendSuccess(() -> Component.literal("MineAstr 活动统计：" + (active ? "正在采集" : "未采集") + "。"), false);
        return 1;
    }

    private static int tracking(CommandSourceStack source, MineAstrBridge bridge, boolean optout) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        bridge.setActivityOptedOut(player.getUUID(), optout);
        source.sendSuccess(() -> Component.literal(optout
                ? "已退出 MineAstr 活动统计，并删除保留期内可归属于你的原始活动数据。"
                : "已重新加入 MineAstr 活动统计；从现在开始记录。"), false);
        return 1;
    }

    private static int analyzeNow(CommandSourceStack source, MineAstrBridge bridge) {
        bridge.analyzeActivityNow();
        source.sendSuccess(() -> Component.literal("MineAstr 已立即重新分析活动地区。"), false);
        return 1;
    }

    private static int knowledgeStatus(CommandSourceStack source, MineAstrBridge bridge) {
        var status = bridge.knowledgeStatus();
        String state = status.has("state") ? status.get("state").getAsString() : "unknown";
        String task = status.has("task_id") ? status.get("task_id").getAsString() : "";
        int total = status.has("total_entries") ? status.get("total_entries").getAsInt() : 0;
        source.sendSuccess(() -> Component.literal(
                "MineAstr 知识扫描：" + state + "；任务=" + (task.isBlank() ? "无" : task) + "；条目=" + total), false);
        if (status.has("last_error") && !status.get("last_error").getAsString().isBlank()) {
            source.sendFailure(Component.literal("最近错误：" + status.get("last_error").getAsString()));
        }
        return 1;
    }

    private static int knowledgeRescan(CommandSourceStack source, MineAstrBridge bridge) {
        String taskId = bridge.rescanKnowledge(source.getServer());
        if ("disabled".equals(taskId)) {
            source.sendFailure(Component.literal("MineAstr Mod 知识扫描已禁用。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已提交 MineAstr 知识扫描：" + taskId), false);
        return 1;
    }
}
