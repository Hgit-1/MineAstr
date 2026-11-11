package com.mineastr;

import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MineAstr.MODID)
@PrefixGameTestTemplate(false)
public final class MineAstrEntitySpikeGameTests {
    private static MineAstrServerEntitySpike active;
    private static Consumer<BlockEvent.BreakEvent> breakGuard;

    @GameTest(template = "empty", batch = "mineastr_entity_spike", timeoutTicks = 200)
    public static void lifecycleAndProtectedActions(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) helper.setBlock(x, 1, z, Blocks.STONE);
        }
        BlockPos min = helper.absolutePos(BlockPos.ZERO);
        BlockPos max = helper.absolutePos(new BlockPos(11, 4, 11));
        String region = level.dimension().location() + "," + min.getX() + "," + min.getY() + "," + min.getZ()
                + "," + max.getX() + "," + max.getY() + "," + max.getZ();
        var stored = MineAstrEntitySpikeData.get(level.getServer());
        stored.active = false;
        stored.player = new CompoundTag();
        stored.setDirty();
        MineAstrServerEntitySpike spike = new MineAstrServerEntitySpike(
                () -> new MineAstrServerEntitySpike.Settings(true, region, List.of()));
        active = spike;
        spike.start(level.getServer());
        BlockPos start = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos target = helper.absolutePos(new BlockPos(4, 2, 2));
        BlockPos destination = helper.absolutePos(new BlockPos(6, 2, 2));
        spike.spawn(level, start);
        helper.onEachTick(spike::tick);
        var actor = (ServerPlayer) level.getEntity(MineAstrServerEntitySpike.PROFILE.getId());
        helper.assertTrue(actor != null, "ServerPlayer must be a real world entity");
        helper.assertTrue(level.getServer().getPlayerList().getPlayer(actor.getUUID()) == null,
                "The fixture must not impersonate an authenticated online player");
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
        MineAstrEntitySpikeCommands.register(dispatcher, spike);
        helper.assertTrue(!dispatcher.getRoot().getChild("mineastr").getChild("entity-spike")
                .canUse(level.getServer().createCommandSourceStack().withPermission(0)), "Non-operators cannot access the fixture");
        var operator = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "SpikeTestOwner"));
        operator.moveTo(Vec3.atBottomCenterOf(start.south(2)), 0, 0);
        operator.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_PLANKS, 3));
        helper.assertTrue(spike.transfer(operator, 0, 3, false) == 3, "Deposit must use real operator inventory");
        helper.assertTrue(operator.getMainHandItem().isEmpty(), "Deposit must consume source items");
        helper.assertTrue(spike.status().getAsJsonObject("inventory").get("total_inventory_item_count").getAsInt() == 3,
                "Observation must reuse the native inventory summary");
        helper.setBlock(new BlockPos(4, 2, 2), Blocks.DIRT);
        int[] deniedBreaks = {0};
        breakGuard = event -> {
            if (event.getPlayer().getUUID().equals(actor.getUUID()) && event.getPos().equals(target)) {
                deniedBreaks[0]++;
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(breakGuard);
        spike.dig(target);

        helper.runAtTickTime(25, () -> {
            try {
                helper.assertTrue(deniedBreaks[0] > 0 && level.getBlockState(target).is(Blocks.DIRT),
                        "Canceled BreakEvent must preserve the block");
                spike.cancel();
            } finally {
                NeoForge.EVENT_BUS.unregister(breakGuard);
                breakGuard = null;
            }
            spike.dig(target);
        });
        helper.runAtTickTime(55, () -> {
            helper.assertTrue(level.getBlockState(target).isAir(), "Native survival digging must complete");
            helper.assertTrue(spike.status().get("state").getAsString().equals("completed"), "Digging must report a verified terminal state");
            int before = actor.getMainHandItem().getCount();
            Consumer<PlayerInteractEvent.RightClickBlock> guard = event -> {
                if (event.getEntity().getUUID().equals(actor.getUUID())) event.setCanceled(true);
            };
            NeoForge.EVENT_BUS.addListener(guard);
            try {
                boolean refused = false;
                try { spike.place(target); } catch (IllegalStateException expected) { refused = true; }
                helper.assertTrue(refused && level.getBlockState(target).isAir(), "Canceled placement must not modify the world");
                helper.assertTrue(actor.getMainHandItem().getCount() == before, "Canceled placement must not consume inventory");
            } finally {
                NeoForge.EVENT_BUS.unregister(guard);
            }
            spike.place(target);
            helper.assertTrue(level.getBlockState(target).is(Blocks.OAK_PLANKS), "Native placement must set the requested block");
            helper.assertTrue(actor.getMainHandItem().getCount() == 2, "Survival placement must consume exactly one item");
            spike.goTo(destination);
        });
        helper.runAtTickTime(135, () -> {
            helper.assertTrue(actor.position().distanceTo(Vec3.atBottomCenterOf(destination)) < 0.11,
                    "Walking must reach the measured position, not just accept a task");
            helper.assertTrue(spike.status().get("state").getAsString().equals("completed"), "Walking must complete");
            spike.goTo(start);
            spike.cancel();
        });
        helper.runAtTickTime(138, () -> {
            helper.assertTrue(actor.position().distanceTo(Vec3.atBottomCenterOf(destination)) < 0.11, "Cancel must stop movement");
            MineAstr.LOGGER.info("MINEASTR_SPIKE_METRICS {}", spike.status());
            spike.close();
            CompoundTag serialized = MineAstrEntitySpikeData.get(level.getServer()).save(new CompoundTag(), level.registryAccess());
            var reloaded = MineAstrEntitySpikeData.load(serialized, level.registryAccess());
            level.getServer().overworld().getDataStorage().set("mineastr_entity_spike", reloaded);
        });
        helper.runAtTickTime(141, () -> {
            spike.start(level.getServer());
            var restored = (ServerPlayer) level.getEntity(MineAstrServerEntitySpike.PROFILE.getId());
            helper.assertTrue(restored != null && restored.getMainHandItem().is(Items.OAK_PLANKS)
                    && restored.getMainHandItem().getCount() == 2, "NBT round-trip must restore native inventory and identity");
            helper.assertTrue(restored.position().distanceTo(Vec3.atBottomCenterOf(destination)) < 0.11,
                    "Restoration must use the saved safe location");
            helper.assertTrue(spike.status().get("node_child_processes").getAsLong() == 0, "The spike must not need a Node child process");
            helper.assertTrue(!spike.status().get("hmac_channel_used").getAsBoolean(), "The spike must not use the HMAC inventory channel");
            MineAstr.LOGGER.info("MINEASTR_SPIKE_METRICS {}", spike.status());
            spike.despawn();
            spike.close();
            helper.succeed();
        });
    }

    @AfterBatch(batch = "mineastr_entity_spike")
    public static void cleanup(ServerLevel level) {
        if (breakGuard != null) NeoForge.EVENT_BUS.unregister(breakGuard);
        breakGuard = null;
        if (active != null) active.close();
        active = null;
    }
}
