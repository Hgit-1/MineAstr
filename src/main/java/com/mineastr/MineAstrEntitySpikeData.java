package com.mineastr;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

final class MineAstrEntitySpikeData extends SavedData {
    private static final String NAME = "mineastr_entity_spike";
    boolean active;
    String dimension = "minecraft:overworld";
    CompoundTag player = new CompoundTag();

    static MineAstrEntitySpikeData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MineAstrEntitySpikeData::new, MineAstrEntitySpikeData::load), NAME);
    }

    static MineAstrEntitySpikeData load(CompoundTag tag, HolderLookup.Provider registries) {
        MineAstrEntitySpikeData data = new MineAstrEntitySpikeData();
        if (tag.getInt("version") == 1) {
            data.active = tag.getBoolean("active");
            data.dimension = tag.getString("dimension");
            data.player = tag.getCompound("player").copy();
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 1);
        tag.putBoolean("active", active);
        tag.putString("dimension", dimension);
        tag.put("player", player.copy());
        return tag;
    }
}
