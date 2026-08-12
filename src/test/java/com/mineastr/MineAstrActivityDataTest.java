package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

final class MineAstrActivityDataTest {
    @Test
    void optOutPersistsAndCanBeReversed() throws Exception {
        UUID player = UUID.fromString("12345678-1234-5678-9abc-123456789abc");
        MineAstrActivityData data = new MineAstrActivityData();
        data.setOptedOut(player, true);
        assertTrue(data.isOptedOut(player));

        CompoundTag saved = data.save(new CompoundTag(), null);
        Method load = MineAstrActivityData.class.getDeclaredMethod(
                "load", CompoundTag.class, HolderLookup.Provider.class);
        load.setAccessible(true);
        MineAstrActivityData restored = (MineAstrActivityData) load.invoke(null, saved, null);
        assertTrue(restored.isOptedOut(player));

        restored.setOptedOut(player, false);
        assertFalse(restored.isOptedOut(player));
    }

    @Test
    void clusteringSeparatesDimensionsAndDistantChunks() {
        MineAstrActivityData data = new MineAstrActivityData();
        long now = 1_800_000_000_000L;
        UUID player = UUID.randomUUID();
        data.addActivityForTest(player, "minecraft:overworld", 0, 0, now, 60);
        data.addActivityForTest(player, "minecraft:overworld", 2, 2, now, 60);
        data.addActivityForTest(player, "minecraft:overworld", 6, 6, now, 60);
        data.addActivityForTest(player, "minecraft:the_nether", 0, 0, now, 60);

        data.analyze(now, 28, 60, 2, 30);

        assertEquals(3, data.regionCountForTest());
        assertEquals(
                data.regionIdAtForTest("minecraft:overworld", 0, 0),
                data.regionIdAtForTest("minecraft:overworld", 2, 2));
        assertNotEquals(
                data.regionIdAtForTest("minecraft:overworld", 0, 0),
                data.regionIdAtForTest("minecraft:the_nether", 0, 0));
    }

    @Test
    void retentionAndOptOutRemoveIdentifiableRawContributions() {
        long now = 1_800_000_000_000L;
        UUID player = UUID.randomUUID();

        MineAstrActivityData expired = new MineAstrActivityData();
        expired.addActivityForTest(player, "minecraft:overworld", 0, 0,
                now - 85L * 24 * 60 * 60 * 1000, 120);
        expired.prune(now, 84);
        expired.analyze(now, 365, 60, 2, 30);
        assertEquals(0, expired.regionCountForTest());

        MineAstrActivityData withdrawn = new MineAstrActivityData();
        withdrawn.addActivityForTest(player, "minecraft:overworld", 0, 0, now, 120);
        withdrawn.setOptedOut(player, true);
        withdrawn.analyze(now, 28, 60, 2, 30);
        assertEquals(0, withdrawn.regionCountForTest());
    }

    @Test
    void splitKeepsIdOnLargestChildAndMergeRetainsAlias() {
        MineAstrActivityData split = new MineAstrActivityData();
        UUID player = UUID.randomUUID();
        long first = 1_800_000_000_000L;
        split.addActivityForTest(player, "minecraft:overworld", 0, 0, first, 90);
        split.addActivityForTest(player, "minecraft:overworld", 2, 0, first, 60);
        split.addActivityForTest(player, "minecraft:overworld", 4, 0, first, 30);
        split.analyze(first, 28, 60, 2, 30);
        String original = split.regionIdAtForTest("minecraft:overworld", 0, 0);

        long later = first + 29L * 24 * 60 * 60 * 1000;
        split.addActivityForTest(player, "minecraft:overworld", 0, 0, later, 90);
        split.addActivityForTest(player, "minecraft:overworld", 4, 0, later, 30);
        split.analyze(later, 28, 60, 2, 30);
        assertEquals(original, split.regionIdAtForTest("minecraft:overworld", 0, 0));
        assertNotEquals(original, split.regionIdAtForTest("minecraft:overworld", 4, 0));

        MineAstrActivityData merge = new MineAstrActivityData();
        merge.addActivityForTest(player, "minecraft:overworld", 0, 0, first, 60);
        merge.addActivityForTest(player, "minecraft:overworld", 6, 0, first, 60);
        merge.analyze(first, 28, 60, 2, 30);
        String left = merge.regionIdAtForTest("minecraft:overworld", 0, 0);
        String right = merge.regionIdAtForTest("minecraft:overworld", 6, 0);
        merge.addActivityForTest(player, "minecraft:overworld", 0, 0, later, 60);
        merge.addActivityForTest(player, "minecraft:overworld", 2, 0, later, 60);
        merge.addActivityForTest(player, "minecraft:overworld", 4, 0, later, 60);
        merge.addActivityForTest(player, "minecraft:overworld", 6, 0, later, 60);
        merge.analyze(later, 28, 60, 2, 30);
        String merged = merge.regionIdAtForTest("minecraft:overworld", 0, 0);
        assertTrue(merged.equals(left) || merged.equals(right));
        String retired = merged.equals(left) ? right : left;
        assertTrue(merge.aliasesAtForTest("minecraft:overworld", 0, 0).contains(retired));
    }

    @Test
    void regionThresholdAppliesAfterChunkClustering() {
        MineAstrActivityData data = new MineAstrActivityData();
        long now = 1_800_000_000_000L;
        UUID player = UUID.randomUUID();
        data.addActivityForTest(player, "minecraft:overworld", 0, 0, now, 20);
        data.addActivityForTest(player, "minecraft:overworld", 2, 0, now, 20);
        data.addActivityForTest(player, "minecraft:overworld", 20, 0, now, 20);

        data.analyze(now, 28, 60, 2, 30);

        assertEquals(1, data.regionCountForTest());
        assertFalse(data.regionIdAtForTest("minecraft:overworld", 0, 0).isBlank());
        assertTrue(data.regionIdAtForTest("minecraft:overworld", 20, 0).isBlank());
    }

    @Test
    void featureClassifierRecognizesCreateAndCommonBuildingSignals() {
        assertEquals("create_power", MineAstrTools.featureCategory("create:large_cogwheel"));
        assertEquals("create_processing", MineAstrTools.featureCategory("create:mechanical_press"));
        assertEquals("create_belts", MineAstrTools.featureCategory("create:belt"));
        assertEquals("create_stations_signals", MineAstrTools.featureCategory("create:track_station"));
        assertEquals("beds", MineAstrTools.featureCategory("minecraft:red_bed"));
        assertTrue(MineAstrTools.isLikelyConstructed("biomesoplenty:redwood_planks"));
        assertFalse(MineAstrTools.isLikelyConstructed("minecraft:stone"));
    }

    @Test
    void legacyActivityNbtLoadsWithEmptyFeatureAggregates() throws Exception {
        long now = 1_800_000_000_000L;
        CompoundTag root = new CompoundTag();
        CompoundTag legacy = new CompoundTag();
        legacy.putLong("week", Math.floorDiv(now, 7L * 24 * 60 * 60 * 1000));
        legacy.putString("dimension", "minecraft:overworld");
        legacy.putInt("chunk_x", 0);
        legacy.putInt("chunk_z", 0);
        legacy.putUUID("player", UUID.randomUUID());
        legacy.putLong("samples", 40);
        legacy.putLong("last_seen", now);
        legacy.putString("biome", "minecraft:plains");
        legacy.putString("surface_block", "minecraft:grass_block");
        legacy.putLong("environment_samples", 1);
        ListTag activity = new ListTag();
        activity.add(legacy);
        root.put("activity", activity);

        Method load = MineAstrActivityData.class.getDeclaredMethod(
                "load", CompoundTag.class, HolderLookup.Provider.class);
        load.setAccessible(true);
        MineAstrActivityData restored = (MineAstrActivityData) load.invoke(null, root, null);
        restored.analyze(now, 28, 60, 2, 30);

        assertEquals(1, restored.regionCountForTest());
        var json = restored.regionJsonAtForTest(0);
        assertTrue(json.has("environment_sample_count"));
        assertTrue(json.has("likely_constructed_ratio"));
        assertTrue(json.has("feature_counts"));
        assertTrue(json.has("top_block_namespaces"));
    }
}
