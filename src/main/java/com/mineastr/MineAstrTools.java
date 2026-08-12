package com.mineastr;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public final class MineAstrTools {
    private static final int MAX_PALETTE_RESULTS = 24;
    private static final int MAX_ENTITY_DETAILS = 32;
    private static final int MAX_FEATURE_SAMPLES = 8;
    private static final int GRID_X = 6;
    private static final int GRID_Y = 4;
    private static final int GRID_Z = 6;

    private MineAstrTools() {
    }

    public static JsonObject buildPlayerState(ServerPlayer player) {
        JsonObject data = playerIdentity(player);
        data.addProperty("dimension", player.serverLevel().dimension().location().toString());
        data.addProperty("x", round2(player.getX()));
        data.addProperty("y", round2(player.getY()));
        data.addProperty("z", round2(player.getZ()));
        data.addProperty("yaw", round2(player.getYRot()));
        data.addProperty("pitch", round2(player.getXRot()));
        data.addProperty("game_mode", player.gameMode.getGameModeForPlayer().getName());
        data.addProperty("health", round2(player.getHealth()));
        data.addProperty("max_health", round2(player.getMaxHealth()));
        data.addProperty("armor", player.getArmorValue());
        data.addProperty("food", player.getFoodData().getFoodLevel());
        data.addProperty("saturation", round2(player.getFoodData().getSaturationLevel()));
        data.addProperty("air", player.getAirSupply());
        data.addProperty("experience_level", player.experienceLevel);
        data.addProperty("experience_progress", round2(player.experienceProgress));
        data.addProperty("on_ground", player.onGround());
        data.addProperty("sprinting", player.isSprinting());
        data.addProperty("swimming", player.isSwimming());
        data.addProperty("sleeping", player.isSleeping());

        JsonArray effects = new JsonArray();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            JsonObject item = new JsonObject();
            String id = effect.getEffect().unwrapKey()
                    .map(key -> key.location().toString())
                    .orElse("unknown");
            item.addProperty("id", id);
            item.addProperty("amplifier", effect.getAmplifier());
            item.addProperty("duration_ticks", effect.getDuration());
            item.addProperty("ambient", effect.isAmbient());
            item.addProperty("visible", effect.isVisible());
            effects.add(item);
        }
        data.add("effects", effects);
        return data;
    }

    public static JsonObject buildInventory(ServerPlayer player, boolean includeEnderChest) {
        JsonObject data = playerIdentity(player);
        Inventory inventory = player.getInventory();
        data.addProperty("selected_hotbar_slot", inventory.selected);
        data.add("selected_item", stackData(inventory.getSelected(), "selected"));

        JsonArray hotbar = new JsonArray();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            addStack(hotbar, inventory.getItem(slot), "hotbar_" + slot, slot);
        }
        data.add("hotbar", hotbar);

        JsonArray main = new JsonArray();
        for (int slot = Inventory.getSelectionSize(); slot < inventory.items.size(); slot++) {
            addStack(main, inventory.items.get(slot), "inventory_" + slot, slot);
        }
        data.add("inventory", main);

        String[] armorNames = {"feet", "legs", "chest", "head"};
        JsonArray armor = new JsonArray();
        for (int slot = 0; slot < inventory.armor.size(); slot++) {
            addStack(armor, inventory.armor.get(slot), armorNames[Math.min(slot, armorNames.length - 1)], slot);
        }
        data.add("armor", armor);

        JsonArray offhand = new JsonArray();
        for (int slot = 0; slot < inventory.offhand.size(); slot++) {
            addStack(offhand, inventory.offhand.get(slot), "offhand", slot);
        }
        data.add("offhand", offhand);

        int occupied = 0;
        int totalItems = 0;
        for (ItemStack stack : inventory.items) {
            if (!stack.isEmpty()) {
                occupied++;
                totalItems += stack.getCount();
            }
        }
        data.addProperty("occupied_inventory_slots", occupied);
        data.addProperty("free_inventory_slots", Math.max(0, inventory.items.size() - occupied));
        data.addProperty("total_inventory_item_count", totalItems);

        if (includeEnderChest) {
            data.add("ender_chest", containerData(player.getEnderChestInventory(), "ender_chest"));
        }
        return data;
    }

    public static JsonObject buildNearbyEntities(ServerPlayer player, double radius) {
        ServerLevel level = player.serverLevel();
        AABB bounds = new AABB(
                player.getX() - radius,
                player.getY() - radius,
                player.getZ() - radius,
                player.getX() + radius,
                player.getY() + radius,
                player.getZ() + radius);
        List<Entity> entities = new ArrayList<>(level.getEntities(player, bounds, entity -> entity != player));
        entities.sort(Comparator.comparingDouble(player::distanceToSqr));

        Map<String, Integer> typeCounts = new HashMap<>();
        Map<String, Integer> categoryCounts = new HashMap<>();
        for (Entity entity : entities) {
            String type = entityTypeId(entity);
            typeCounts.merge(type, 1, Integer::sum);
            categoryCounts.merge(entity.getType().getCategory().getName(), 1, Integer::sum);
        }

        JsonObject data = playerIdentity(player);
        data.addProperty("dimension", level.dimension().location().toString());
        data.addProperty("radius", round2(radius));
        data.addProperty("entity_count", entities.size());
        data.add("counts_by_type", countObject(typeCounts, 32));
        data.add("counts_by_category", countObject(categoryCounts, 16));

        JsonArray details = new JsonArray();
        for (int index = 0; index < Math.min(MAX_ENTITY_DETAILS, entities.size()); index++) {
            Entity entity = entities.get(index);
            JsonObject item = new JsonObject();
            item.addProperty("type", entityTypeId(entity));
            item.addProperty("name", entity.getName().getString());
            item.addProperty("x", round2(entity.getX()));
            item.addProperty("y", round2(entity.getY()));
            item.addProperty("z", round2(entity.getZ()));
            item.addProperty("distance", round2(Math.sqrt(player.distanceToSqr(entity))));
            if (entity instanceof LivingEntity living) {
                item.addProperty("health", round2(living.getHealth()));
                item.addProperty("max_health", round2(living.getMaxHealth()));
            }
            details.add(item);
        }
        data.add("nearest_entities", details);
        data.addProperty("details_truncated", entities.size() > MAX_ENTITY_DETAILS);
        return data;
    }

    public static JsonObject analyzeRegion(
            ServerLevel level,
            BlockPos center,
            int horizontalRadius,
            int verticalRadius) {
        int minX = center.getX() - horizontalRadius;
        int maxX = center.getX() + horizontalRadius;
        int minY = Math.max(level.getMinBuildHeight(), center.getY() - verticalRadius);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + verticalRadius);
        int minZ = center.getZ() - horizontalRadius;
        int maxZ = center.getZ() + horizontalRadius;
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;

        Map<String, Integer> palette = new HashMap<>();
        Map<String, Integer> biomes = new HashMap<>();
        Map<String, Integer> features = new LinkedHashMap<>();
        Map<String, JsonArray> featureSamples = new LinkedHashMap<>();
        Map<String, Integer> blockEntityTypes = new HashMap<>();
        int[] gridTotal = new int[GRID_X * GRID_Y * GRID_Z];
        int[] gridOccupied = new int[gridTotal.length];
        int[] gridConstructed = new int[gridTotal.length];
        int scanned = 0;
        int unloaded = 0;
        int nonAir = 0;
        int solid = 0;
        int fluid = 0;
        int likelyConstructed = 0;
        long surfaceTotal = 0;
        int surfaceColumns = 0;
        int surfaceMin = Integer.MAX_VALUE;
        int surfaceMax = Integer.MIN_VALUE;
        Bounds occupiedBounds = new Bounds();
        Bounds constructedBounds = new Bounds();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean loaded = level.hasChunk(x >> 4, z >> 4);
                int columnTop = Integer.MIN_VALUE;
                for (int y = minY; y <= maxY; y++) {
                    int gridIndex = gridIndex(x, y, z, minX, minY, minZ, sizeX, sizeY, sizeZ);
                    if (!loaded) {
                        unloaded++;
                        continue;
                    }
                    gridTotal[gridIndex]++;
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    scanned++;
                    if (state.isAir()) {
                        continue;
                    }

                    nonAir++;
                    gridOccupied[gridIndex]++;
                    occupiedBounds.include(x, y, z);
                    columnTop = Math.max(columnTop, y);
                    ResourceLocation blockKey = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    String blockId = blockKey == null ? "unknown" : blockKey.toString();
                    palette.merge(blockId, 1, Integer::sum);
                    if (state.blocksMotion()) {
                        solid++;
                    }
                    if (!state.getFluidState().isEmpty()) {
                        fluid++;
                    }

                    boolean constructed = isLikelyConstructed(blockId);
                    if (constructed) {
                        likelyConstructed++;
                        gridConstructed[gridIndex]++;
                        constructedBounds.include(x, y, z);
                    }
                    String feature = featureCategory(blockId);
                    if (feature != null) {
                        features.merge(feature, 1, Integer::sum);
                        JsonArray samples = featureSamples.computeIfAbsent(feature, ignored -> new JsonArray());
                        if (samples.size() < MAX_FEATURE_SAMPLES) {
                            samples.add(positionObject(x, y, z, blockId));
                        }
                    }
                    if (state.hasBlockEntity()) {
                        BlockEntity blockEntity = level.getBlockEntity(pos);
                        if (blockEntity != null) {
                            ResourceLocation typeKey = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
                            blockEntityTypes.merge(typeKey == null ? "unknown" : typeKey.toString(), 1, Integer::sum);
                        }
                    }
                }
                if (loaded) {
                    int biomeY = columnTop == Integer.MIN_VALUE ? center.getY() : columnTop;
                    pos.set(x, Math.max(minY, Math.min(maxY, biomeY)), z);
                    String biome = level.getBiome(pos).unwrapKey()
                            .map(key -> key.location().toString())
                            .orElse("unknown");
                    biomes.merge(biome, 1, Integer::sum);
                }
                if (columnTop != Integer.MIN_VALUE) {
                    surfaceMin = Math.min(surfaceMin, columnTop);
                    surfaceMax = Math.max(surfaceMax, columnTop);
                    surfaceTotal += columnTop;
                    surfaceColumns++;
                }
            }
        }

        JsonObject data = new JsonObject();
        data.addProperty("dimension", level.dimension().location().toString());
        data.add("center", positionObject(center.getX(), center.getY(), center.getZ(), null));
        data.add("bounds", boundsObject(minX, minY, minZ, maxX, maxY, maxZ));
        data.addProperty("requested_blocks", sizeX * sizeY * sizeZ);
        data.addProperty("scanned_loaded_blocks", scanned);
        data.addProperty("skipped_unloaded_blocks", unloaded);
        data.addProperty("non_air_blocks", nonAir);
        data.addProperty("solid_blocks", solid);
        data.addProperty("fluid_blocks", fluid);
        data.addProperty("likely_constructed_blocks", likelyConstructed);
        data.addProperty("likely_constructed_ratio", nonAir == 0 ? 0.0 : round3((double) likelyConstructed / nonAir));
        data.add("occupied_bounds", occupiedBounds.toJson());
        data.add("likely_constructed_bounds", constructedBounds.toJson());
        data.add("top_block_palette", countArray(palette, MAX_PALETTE_RESULTS));
        data.add("biomes", countArray(biomes, 12));
        data.add("feature_counts", countObject(features, 32));

        JsonObject samples = new JsonObject();
        for (Map.Entry<String, JsonArray> entry : featureSamples.entrySet()) {
            samples.add(entry.getKey(), entry.getValue());
        }
        data.add("feature_samples", samples);
        data.add("block_entity_counts", countObject(blockEntityTypes, 24));

        JsonObject surface = new JsonObject();
        surface.addProperty("sampled_columns", surfaceColumns);
        if (surfaceColumns > 0) {
            surface.addProperty("min_y", surfaceMin);
            surface.addProperty("max_y", surfaceMax);
            surface.addProperty("average_y", round2((double) surfaceTotal / surfaceColumns));
            surface.addProperty("height_variation", surfaceMax - surfaceMin);
        }
        data.add("surface", surface);
        data.add("coarse_shape", coarseShape(gridTotal, gridOccupied, gridConstructed));
        data.addProperty("privacy_note", "仅包含方块类型、计数和粗略形状；未读取容器内容、告示牌文字或方块实体 NBT。");
        return data;
    }

    private static JsonObject playerIdentity(ServerPlayer player) {
        JsonObject data = new JsonObject();
        data.addProperty("player_uuid", player.getUUID().toString());
        data.addProperty("player_name", player.getGameProfile().getName());
        data.addProperty("display_name", player.getDisplayName().getString());
        return data;
    }

    private static JsonArray containerData(Container container, String prefix) {
        JsonArray items = new JsonArray();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            addStack(items, container.getItem(slot), prefix + "_" + slot, slot);
        }
        return items;
    }

    private static void addStack(JsonArray output, ItemStack stack, String slotName, int slot) {
        if (stack.isEmpty()) {
            return;
        }
        JsonObject data = stackData(stack, slotName);
        data.addProperty("slot_index", slot);
        output.add(data);
    }

    private static JsonObject stackData(ItemStack stack, String slotName) {
        JsonObject data = new JsonObject();
        data.addProperty("slot", slotName);
        if (stack.isEmpty()) {
            data.addProperty("empty", true);
            return data;
        }
        ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
        data.addProperty("id", itemKey == null ? "unknown" : itemKey.toString());
        data.addProperty("name", stack.getHoverName().getString());
        data.addProperty("count", stack.getCount());
        data.addProperty("enchanted", stack.isEnchanted());
        if (stack.isDamageableItem()) {
            data.addProperty("damage", stack.getDamageValue());
            data.addProperty("max_damage", stack.getMaxDamage());
            data.addProperty("remaining_durability", Math.max(0, stack.getMaxDamage() - stack.getDamageValue()));
        }
        return data;
    }

    private static String entityTypeId(Entity entity) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key == null ? "unknown" : key.toString();
    }

    private static JsonObject countObject(Map<String, Integer> counts, int limit) {
        JsonObject output = new JsonObject();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .forEach(entry -> output.addProperty(entry.getKey(), entry.getValue()));
        return output;
    }

    private static JsonArray countArray(Map<String, Integer> counts, int limit) {
        JsonArray output = new JsonArray();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .forEach(entry -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("id", entry.getKey());
                    item.addProperty("count", entry.getValue());
                    output.add(item);
                });
        return output;
    }

    private static JsonObject coarseShape(int[] total, int[] occupied, int[] constructed) {
        JsonObject data = new JsonObject();
        data.addProperty("grid_x", GRID_X);
        data.addProperty("grid_y", GRID_Y);
        data.addProperty("grid_z", GRID_Z);
        data.addProperty("order", "y,z,x");
        JsonArray occupancy = new JsonArray();
        JsonArray constructedRatio = new JsonArray();
        for (int index = 0; index < total.length; index++) {
            occupancy.add(total[index] == 0 ? 0 : Math.round(occupied[index] * 100.0F / total[index]));
            constructedRatio.add(occupied[index] == 0 ? 0 : Math.round(constructed[index] * 100.0F / occupied[index]));
        }
        data.add("occupancy_percent", occupancy);
        data.add("constructed_percent_of_occupied", constructedRatio);
        return data;
    }

    private static int gridIndex(
            int x,
            int y,
            int z,
            int minX,
            int minY,
            int minZ,
            int sizeX,
            int sizeY,
            int sizeZ) {
        int gx = Math.min(GRID_X - 1, (x - minX) * GRID_X / Math.max(1, sizeX));
        int gy = Math.min(GRID_Y - 1, (y - minY) * GRID_Y / Math.max(1, sizeY));
        int gz = Math.min(GRID_Z - 1, (z - minZ) * GRID_Z / Math.max(1, sizeZ));
        return (gy * GRID_Z + gz) * GRID_X + gx;
    }

    static String featureCategory(String blockId) {
        String id = blockId.toLowerCase(Locale.ROOT);
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (id.startsWith("create:")) {
            if (containsAny(path, "shaft", "cogwheel", "gearbox", "clutch", "gearshift", "motor", "water_wheel", "windmill", "steam_engine", "flywheel")) return "create_power";
            if (containsAny(path, "mechanical_press", "mixer", "crushing_wheel", "millstone", "basin", "deployer", "spout", "mechanical_saw", "encased_fan")) return "create_processing";
            if (containsAny(path, "belt", "funnel", "chute", "tunnel")) return "create_belts";
            if (containsAny(path, "track_station", "track_signal", "train_signal", "signal_box", "controls")) return "create_stations_signals";
        }
        if (path.contains("door") && !path.contains("trapdoor")) return "doors";
        if (path.contains("trapdoor")) return "trapdoors";
        if (path.contains("stairs")) return "stairs";
        if (path.contains("slab")) return "slabs";
        if (path.contains("glass") || path.contains("pane")) return "windows_or_glass";
        if (path.contains("fence") || path.endsWith("_wall")) return "fences_or_walls";
        if (path.contains("torch") || path.contains("lantern") || path.contains("light") || path.contains("glowstone")) return "lighting";
        if (path.contains("chest") || path.contains("barrel") || path.contains("shulker_box")) return "storage";
        if (path.contains("furnace") || path.contains("smoker") || path.contains("blast_furnace")) return "furnaces";
        if (path.contains("crafting_table") || path.contains("anvil") || path.contains("stonecutter") || path.contains("loom")) return "workstations";
        if (path.contains("redstone") || path.contains("repeater") || path.contains("comparator") || path.contains("piston") || path.contains("observer")) return "redstone";
        if (path.contains("rail") || path.equals("track") || path.endsWith("_track")) return "rails";
        if (path.contains("bed")) return "beds";
        if (path.contains("sign")) return "signs";
        if (path.contains("ladder") || path.contains("scaffolding")) return "vertical_access";
        if (path.contains("crop") || path.contains("wheat") || path.contains("carrot") || path.contains("potato") || path.contains("farmland")) return "farming";
        return null;
    }

    static boolean isLikelyConstructed(String blockId) {
        String id = blockId.toLowerCase(Locale.ROOT);
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (featureCategory(blockId) != null) {
            return true;
        }
        String[] constructedMarkers = {
                "planks", "bricks", "brick_", "concrete", "terracotta", "wool", "carpet", "tiles",
                "polished", "chiseled", "cut_", "smooth_", "quartz", "prismarine", "purpur",
                "copper", "bookshelf", "decorated_pot", "banner", "painting"
        };
        for (String marker : constructedMarkers) {
            if (path.contains(marker)) {
                return true;
            }
        }
        String[] naturalMarkers = {
                "air", "stone", "deepslate", "dirt", "grass", "podzol", "mycelium", "sand", "gravel",
                "ore", "raw_", "bedrock", "water", "lava", "snow", "ice", "clay", "mud", "moss",
                "netherrack", "soul_sand", "soul_soil", "basalt", "blackstone", "end_stone", "obsidian",
                "log", "wood", "leaves", "sapling", "vine", "flower", "mushroom", "cactus", "bamboo",
                "kelp", "seagrass", "coral", "dripstone", "amethyst", "sculk", "tuff", "calcite"
        };
        for (String marker : naturalMarkers) {
            if (path.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAny(String value, String... markers) {
        for (String marker : markers) if (value.contains(marker)) return true;
        return false;
    }

    private static JsonObject positionObject(int x, int y, int z, String blockId) {
        JsonObject data = new JsonObject();
        data.addProperty("x", x);
        data.addProperty("y", y);
        data.addProperty("z", z);
        if (blockId != null) {
            data.addProperty("block", blockId);
        }
        return data;
    }

    private static JsonObject boundsObject(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        JsonObject data = new JsonObject();
        data.add("min", positionObject(minX, minY, minZ, null));
        data.add("max", positionObject(maxX, maxY, maxZ, null));
        return data;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static final class Bounds {
        private int minX = Integer.MAX_VALUE;
        private int minY = Integer.MAX_VALUE;
        private int minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE;
        private int maxY = Integer.MIN_VALUE;
        private int maxZ = Integer.MIN_VALUE;

        private void include(int x, int y, int z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        private JsonObject toJson() {
            if (minX == Integer.MAX_VALUE) {
                return new JsonObject();
            }
            return boundsObject(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}
