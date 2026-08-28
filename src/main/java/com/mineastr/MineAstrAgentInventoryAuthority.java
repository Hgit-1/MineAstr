package com.mineastr;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;

/**
 * Executes inventory operations against the authoritative server registry.
 *
 * <p>NeoForge dynamically negotiates item component registries. A vanilla
 * protocol client cannot safely decode those slot packets, so the Mineflayer
 * process only handles movement while this class performs bounded inventory
 * changes on the server thread.</p>
 */
final class MineAstrAgentInventoryAuthority {
    private MineAstrAgentInventoryAuthority() {
    }

    static JsonObject execute(ServerPlayer player, String taskType, JsonObject args) {
        if ("inventory_inspect".equals(taskType)) return inventoryInspect(player);
        if ("inventory_select".equals(taskType)) return inventorySelect(player, args);
        if ("inventory_eat".equals(taskType)) return inventoryEat(player, args);
        if ("inventory_equip_best".equals(taskType)) return inventoryEquipBest(player);
        if ("inventory_select_weapon".equals(taskType)) return inventorySelectWeapon(player);
        if ("inventory_select_tool".equals(taskType)) return inventorySelectTool(player, args);
        if ("farm_scan".equals(taskType)) return farmScan(player, args);
        if ("farm_harvest".equals(taskType)) return farmHarvest(player, args);

        BlockPos position = position(args);
        BlockEntity blockEntity = player.serverLevel().getBlockEntity(position);
        if (!(blockEntity instanceof Container container)) {
            throw new IllegalStateException("目标方块不是服务端可识别的容器");
        }
        return switch (taskType) {
            case "container_inspect" -> containerInspect(player, position, blockEntity, container);
            case "container_transfer" -> containerTransfer(player, position, blockEntity, container, args);
            case "container_deposit" -> containerDeposit(player, position, blockEntity, container, args);
            case "furnace_inspect" -> furnaceInspect(player, position, blockEntity);
            case "furnace_process" -> furnaceProcess(player, position, blockEntity, args);
            case "furnace_collect" -> furnaceCollect(player, position, blockEntity);
            default -> throw new IllegalArgumentException("不支持的服务端物品操作：" + taskType);
        };
    }

    private static JsonObject inventoryInspect(ServerPlayer player) {
        JsonObject result = MineAstrTools.buildInventory(player, false);
        result.addProperty("operation", "inventory_inspect");
        result.addProperty("authority", "minecraft_server");
        return result;
    }

    private static JsonObject inventorySelect(ServerPlayer player, JsonObject args) {
        String requested = string(args, "item_id", string(args, "item_name", ""));
        if (requested.isBlank()) throw new IllegalArgumentException("选择背包物品需要 item_id");
        int sourceSlot = findMainInventorySlot(player.getInventory(), requested);
        if (sourceSlot < 0) throw new IllegalStateException("背包中没有物品：" + requested);
        ItemStack selected = selectMainInventorySlot(player, sourceSlot);
        changedPlayer(player);

        JsonObject result = inventoryResult("inventory_select", player);
        result.add("selected_item", stackData(selected));
        result.addProperty("selected_hotbar_slot", player.getInventory().selected);
        return result;
    }

    private static JsonObject inventoryEat(ServerPlayer player, JsonObject args) {
        if (player.isDeadOrDying() || player.isCreative() || player.isSpectator()) {
            throw new IllegalStateException("当前状态不允许从背包进食");
        }
        String requested = string(args, "item_id", string(args, "item_name", ""));
        int bestSlot = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (stack.isEmpty() || food == null || (!requested.isBlank() && !matches(stack, requested))) continue;
            boolean harmful = hasHarmfulFoodEffect(food);
            if (harmful && player.getFoodData().getFoodLevel() > 6) continue;
            double score = food.nutrition() * 4.0 + food.saturation() * 2.0 + (harmful ? 0.0 : 100.0);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) throw new IllegalStateException(requested.isBlank()
                ? "背包中没有可安全食用的物品" : "背包中没有可食用物品：" + requested);
        ItemStack source = inventory.items.get(bestSlot);
        FoodProperties food = source.get(DataComponents.FOOD);
        if (food == null || !player.canEat(food.canAlwaysEat())) throw new IllegalStateException("当前无需进食");
        String consumedId = itemId(source);
        int foodBefore = player.getFoodData().getFoodLevel();
        ItemStack remainder = player.eat(player.serverLevel(), source, food);
        inventory.items.set(bestSlot, remainder);
        changedPlayer(player);

        JsonObject result = inventoryResult("inventory_eat", player);
        result.addProperty("consumed_item", consumedId);
        result.addProperty("food_before", foodBefore);
        result.addProperty("food_after", player.getFoodData().getFoodLevel());
        return result;
    }

    private static JsonObject inventoryEquipBest(ServerPlayer player) {
        JsonArray equipped = new JsonArray();
        equipBestArmor(player, "helmet", EquipmentSlot.HEAD, equipped);
        equipBestArmor(player, "chestplate", EquipmentSlot.CHEST, equipped);
        equipBestArmor(player, "leggings", EquipmentSlot.LEGS, equipped);
        equipBestArmor(player, "boots", EquipmentSlot.FEET, equipped);

        Inventory inventory = player.getInventory();
        int weaponSlot = bestMainInventorySlot(inventory, MineAstrAgentInventoryAuthority::weaponScore);
        if (weaponSlot >= 0) {
            ItemStack candidate = inventory.items.get(weaponSlot);
            if (weaponScore(candidate) > weaponScore(inventory.getSelected())) {
                ItemStack selected = selectMainInventorySlot(player, weaponSlot);
                JsonObject entry = stackData(selected);
                entry.addProperty("destination", "mainhand");
                equipped.add(entry);
            }
        }
        changedPlayer(player);
        JsonObject result = inventoryResult("inventory_equip_best", player);
        result.add("equipped", equipped);
        return result;
    }

    private static JsonObject inventorySelectTool(ServerPlayer player, JsonObject args) {
        BlockPos position = position(args);
        BlockState state = player.serverLevel().getBlockState(position);
        Inventory inventory = player.getInventory();
        int bestSlot = -1;
        float bestSpeed = inventory.getSelected().isEmpty() ? 1.0F : inventory.getSelected().getDestroySpeed(state);
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack candidate = inventory.items.get(slot);
            if (candidate.isEmpty()) continue;
            float speed = candidate.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        if (bestSlot >= 0) selectMainInventorySlot(player, bestSlot);
        changedPlayer(player);
        JsonObject result = inventoryResult("inventory_select_tool", player);
        result.addProperty("target_block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        result.addProperty("destroy_speed", bestSpeed);
        result.add("selected_item", stackData(inventory.getSelected()));
        return result;
    }

    private static JsonObject inventorySelectWeapon(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        int weaponSlot = bestMainInventorySlot(inventory, MineAstrAgentInventoryAuthority::weaponScore);
        if (weaponSlot >= 0 && weaponScore(inventory.items.get(weaponSlot)) > weaponScore(inventory.getSelected())) {
            selectMainInventorySlot(player, weaponSlot);
        }
        changedPlayer(player);
        JsonObject result = inventoryResult("inventory_select_weapon", player);
        result.add("selected_item", stackData(inventory.getSelected()));
        result.addProperty("weapon_score", weaponScore(inventory.getSelected()));
        return result;
    }

    private static void equipBestArmor(
            ServerPlayer player, String suffix, EquipmentSlot destination, JsonArray equipped) {
        Inventory inventory = player.getInventory();
        int sourceSlot = bestMainInventorySlot(inventory,
                stack -> itemId(stack).endsWith("_" + suffix) ? armorScore(stack) : -1);
        if (sourceSlot < 0) return;
        ItemStack candidate = inventory.items.get(sourceSlot);
        ItemStack current = player.getItemBySlot(destination);
        if (!current.isEmpty() && armorScore(candidate) <= armorScore(current)) return;
        inventory.items.set(sourceSlot, current);
        player.setItemSlot(destination, candidate);
        JsonObject entry = stackData(candidate);
        entry.addProperty("destination", destination.getName());
        equipped.add(entry);
    }

    private static int bestMainInventorySlot(Inventory inventory, java.util.function.ToIntFunction<ItemStack> scorer) {
        int bestSlot = -1;
        int bestScore = -1;
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            if (stack.isEmpty()) continue;
            int score = scorer.applyAsInt(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private static ItemStack selectMainInventorySlot(ServerPlayer player, int sourceSlot) {
        Inventory inventory = player.getInventory();
        if (sourceSlot < 0 || sourceSlot >= inventory.items.size()) {
            throw new IllegalArgumentException("背包槽位无效");
        }
        int selectedSlot = inventory.selected;
        if (sourceSlot != selectedSlot) {
            ItemStack source = inventory.items.get(sourceSlot);
            ItemStack previous = inventory.items.get(selectedSlot);
            inventory.items.set(sourceSlot, previous);
            inventory.items.set(selectedSlot, source);
        }
        return inventory.items.get(selectedSlot);
    }

    private static int findMainInventorySlot(Inventory inventory, String requested) {
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            if (matches(inventory.items.get(slot), requested)) return slot;
        }
        return -1;
    }

    private static int armorScore(ItemStack stack) {
        String id = itemId(stack);
        if (id.contains("netherite_")) return 600;
        if (id.contains("diamond_")) return 500;
        if (id.contains("iron_")) return 400;
        if (id.contains("chainmail_")) return 300;
        if (id.contains("golden_")) return 200;
        if (id.contains("leather_")) return 100;
        return 1;
    }

    private static int weaponScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        String id = itemId(stack);
        int kind = id.matches(".*_(?:sword|katana|saber|rapier)$") ? 30
                : id.matches(".*_(?:mace|trident)$") ? 20
                : id.matches(".*_(?:axe|battleaxe|warhammer)$") ? 10 : 0;
        if (kind == 0) return 0;
        return armorScore(stack) * 100 + kind;
    }

    private static boolean hasHarmfulFoodEffect(FoodProperties food) {
        return food.effects().stream().anyMatch(possible ->
                possible.effect().getEffect().value().getCategory() == MobEffectCategory.HARMFUL);
    }

    private static JsonObject inventoryResult(String operation, ServerPlayer player) {
        JsonObject result = new JsonObject();
        result.addProperty("operation", operation);
        result.addProperty("authority", "minecraft_server");
        result.addProperty("selected_hotbar_slot", player.getInventory().selected);
        return result;
    }

    private static JsonObject containerInspect(
            ServerPlayer player, BlockPos position, BlockEntity blockEntity, Container container) {
        JsonObject result = baseResult("inspect", player, position, blockEntity);
        result.addProperty("container_slots", container.getContainerSize());
        result.add("items", summarize(container));
        return result;
    }

    private static JsonObject containerTransfer(
            ServerPlayer player,
            BlockPos position,
            BlockEntity blockEntity,
            Container container,
            JsonObject args) {
        String direction = string(args, "direction", "").toLowerCase(Locale.ROOT);
        if (!direction.equals("to_container") && !direction.equals("from_container")) {
            throw new IllegalArgumentException("容器搬运方向必须是 to_container 或 from_container");
        }
        String itemId = normalizedItemId(string(args, "item_id", string(args, "item_name", "")));
        if (itemId.isBlank()) throw new IllegalArgumentException("容器搬运需要物品 ID");
        int requested = boundedInt(args, "count", 1, 1, 2304);
        JsonArray before = summarize(container);
        int moved = direction.equals("to_container")
                ? movePlayerToContainer(player, container, itemId, requested)
                : moveContainerToPlayer(player, container, itemId, requested);
        if (moved <= 0) throw new IllegalStateException("来源中没有可搬运的物品或目标空间不足：" + itemId);
        changed(player, blockEntity, container);

        JsonObject result = baseResult("transfer", player, position, blockEntity);
        result.addProperty("direction", direction);
        result.addProperty("requested_item", itemId);
        result.addProperty("requested_count", requested);
        result.addProperty("transferred_count", moved);
        result.add("before_items", before);
        result.add("after_items", summarize(container));
        return result;
    }

    private static JsonObject containerDeposit(
            ServerPlayer player,
            BlockPos position,
            BlockEntity blockEntity,
            Container container,
            JsonObject args) {
        String requested = string(args, "item_id", string(args, "item_name", ""));
        int keepCount = boundedInt(args, "keep_count", 0, 0, 2304);
        int maximum = boundedInt(args, "max_items", 2304, 1, 2304);
        boolean includeHotbar = args.has("include_hotbar") && args.get("include_hotbar").getAsBoolean();
        Inventory inventory = player.getInventory();
        Map<String, Integer> movableById = new LinkedHashMap<>();
        for (ItemStack stack : inventory.items) {
            if (stack.isEmpty() || (!requested.isBlank() && !matches(stack, requested))) continue;
            movableById.merge(itemId(stack), stack.getCount(), Integer::sum);
        }
        movableById.replaceAll((ignored, total) -> Math.max(0, total - keepCount));

        JsonArray before = summarize(container);
        Map<String, Integer> movedById = new LinkedHashMap<>();
        int remaining = maximum;
        int firstSlot = includeHotbar ? 0 : Inventory.getSelectionSize();
        for (int slot = firstSlot; slot < inventory.items.size() && remaining > 0; slot++) {
            if (slot == inventory.selected) continue;
            ItemStack source = inventory.items.get(slot);
            if (source.isEmpty() || (!requested.isBlank() && !matches(source, requested))) continue;
            String id = itemId(source);
            int allowed = Math.min(remaining, movableById.getOrDefault(id, 0));
            if (allowed <= 0) continue;
            int moved = insert(container, source, allowed);
            if (moved <= 0) continue;
            source.shrink(moved);
            remaining -= moved;
            movableById.put(id, Math.max(0, movableById.getOrDefault(id, 0) - moved));
            movedById.merge(id, moved, Integer::sum);
        }
        if (movedById.isEmpty()) throw new IllegalStateException("没有符合条件的物品可存入容器，或容器空间不足");
        changed(player, blockEntity, container);

        JsonObject result = baseResult("deposit", player, position, blockEntity);
        result.addProperty("requested_item", requested);
        result.addProperty("keep_count", keepCount);
        result.addProperty("include_hotbar", includeHotbar);
        result.addProperty("transferred_count", movedById.values().stream().mapToInt(Integer::intValue).sum());
        JsonArray movedItems = new JsonArray();
        movedById.forEach((id, count) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("item_id", id);
            entry.addProperty("count", count);
            movedItems.add(entry);
        });
        result.add("moved_items", movedItems);
        result.add("before_items", before);
        result.add("after_items", summarize(container));
        return result;
    }

    private static JsonObject farmScan(ServerPlayer player, JsonObject args) {
        BlockPos center = position(args);
        int radius = boundedInt(args, "radius", 6, 1, 8);
        int maximum = boundedInt(args, "max_count", 32, 1, 64);
        JsonArray crops = new JsonArray();
        ServerLevel level = player.serverLevel();
        for (int y = -2; y <= 2 && crops.size() < maximum; y++) {
            for (int x = -radius; x <= radius && crops.size() < maximum; x++) {
                for (int z = -radius; z <= radius && crops.size() < maximum; z++) {
                    BlockPos target = center.offset(x, y, z);
                    BlockState state = level.getBlockState(target);
                    if (!isMatureCrop(state)) continue;
                    CropBlock crop = (CropBlock) state.getBlock();
                    JsonObject entry = new JsonObject();
                    entry.addProperty("x", target.getX());
                    entry.addProperty("y", target.getY());
                    entry.addProperty("z", target.getZ());
                    entry.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(crop).toString());
                    entry.addProperty("age", crop.getAge(state));
                    entry.addProperty("max_age", crop.getMaxAge());
                    crops.add(entry);
                }
            }
        }
        JsonObject result = inventoryResult("farm_scan", player);
        result.addProperty("radius", radius);
        result.addProperty("mature_count", crops.size());
        result.add("crops", crops);
        return result;
    }

    private static JsonObject farmHarvest(ServerPlayer player, JsonObject args) {
        BlockPos target = position(args);
        ServerLevel level = player.serverLevel();
        BlockState state = level.getBlockState(target);
        if (!isMatureCrop(state)) throw new IllegalStateException("目标不是成熟且受支持的作物");
        CropBlock crop = (CropBlock) state.getBlock();
        ItemStack seed = crop.getCloneItemStack(level, target, state);
        if (seed.isEmpty()) throw new IllegalStateException("无法确认该作物的补种物品");
        String seedId = itemId(seed);
        int seedSlot = findMainInventorySlot(player.getInventory(), seedId);
        if (seedSlot < 0) throw new IllegalStateException("背包中缺少补种物品：" + seedId);

        ItemStack seedStack = player.getInventory().items.get(seedSlot);
        seedStack.shrink(1);
        BlockEntity blockEntity = level.getBlockEntity(target);
        Block.dropResources(state, level, target, blockEntity, player, player.getMainHandItem());
        level.setBlock(target, crop.getStateForAge(0), Block.UPDATE_ALL);
        changedPlayer(player);

        JsonObject result = inventoryResult("farm_harvest", player);
        result.addProperty("x", target.getX());
        result.addProperty("y", target.getY());
        result.addProperty("z", target.getZ());
        result.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(crop).toString());
        result.addProperty("replanted", true);
        result.addProperty("seed_item", seedId);
        return result;
    }

    static boolean isMatureCrop(BlockState state) {
        return state != null && state.getBlock() instanceof CropBlock crop
                && isMatureCropAge(true, crop.getAge(state), crop.getMaxAge());
    }

    static boolean isMatureCropAge(boolean cropBlock, int age, int maximumAge) {
        return cropBlock && maximumAge >= 0 && age >= maximumAge;
    }

    private static JsonObject furnaceInspect(ServerPlayer player, BlockPos position, BlockEntity blockEntity) {
        AbstractFurnaceBlockEntity furnace = furnace(blockEntity);
        JsonObject result = baseResult("inspect", player, position, blockEntity);
        result.add("input", stackData(furnace.getItem(0)));
        result.add("fuel", stackData(furnace.getItem(1)));
        result.add("output", stackData(furnace.getItem(2)));
        return result;
    }

    private static JsonObject furnaceProcess(
            ServerPlayer player, BlockPos position, BlockEntity blockEntity, JsonObject args) {
        AbstractFurnaceBlockEntity furnace = furnace(blockEntity);
        String inputId = normalizedItemId(string(args, "input_item", ""));
        if (inputId.isBlank()) throw new IllegalArgumentException("熔炉加工需要 input_item");
        int inputCount = boundedInt(args, "input_count", 1, 1, 64);
        String fuelId = normalizedItemId(string(args, "fuel_item", ""));
        int fuelCount = boundedInt(args, "fuel_count", 1, 1, 64);

        int insertedInput = movePlayerToSlot(player, furnace, 0, inputId, inputCount);
        if (insertedInput <= 0) throw new IllegalStateException("背包中没有可放入熔炉的原料：" + inputId);
        int insertedFuel = 0;
        if (!fuelId.isBlank()) insertedFuel = movePlayerToSlot(player, furnace, 1, fuelId, fuelCount);
        else insertedFuel = moveFirstAcceptedPlayerStackToSlot(player, furnace, 1, fuelCount);
        if (insertedFuel <= 0) {
            moveSlotToPlayer(player, furnace, 0, insertedInput);
            throw new IllegalStateException(fuelId.isBlank() ? "背包中没有可识别的燃料" : "背包中没有燃料：" + fuelId);
        }
        changed(player, blockEntity, furnace);

        JsonObject result = furnaceInspect(player, position, blockEntity);
        result.addProperty("operation", "process_started");
        result.addProperty("input_item", inputId);
        result.addProperty("input_count", insertedInput);
        result.addProperty("fuel_item", itemId(furnace.getItem(1)));
        result.addProperty("fuel_count", insertedFuel);
        return result;
    }

    private static JsonObject furnaceCollect(ServerPlayer player, BlockPos position, BlockEntity blockEntity) {
        AbstractFurnaceBlockEntity furnace = furnace(blockEntity);
        ItemStack output = furnace.getItem(2);
        if (output.isEmpty()) throw new IllegalStateException("熔炉当前没有可收取的产物");
        String outputId = itemId(output);
        int requested = output.getCount();
        int moved = moveSlotToPlayer(player, furnace, 2, requested);
        if (moved <= 0) throw new IllegalStateException("背包空间不足，无法收取熔炉产物");
        changed(player, blockEntity, furnace);
        JsonObject result = furnaceInspect(player, position, blockEntity);
        result.addProperty("operation", "collect");
        JsonObject taken = new JsonObject();
        taken.addProperty("item_id", outputId);
        taken.addProperty("count", moved);
        result.add("taken_output", taken);
        return result;
    }

    private static AbstractFurnaceBlockEntity furnace(BlockEntity blockEntity) {
        if (blockEntity instanceof AbstractFurnaceBlockEntity furnace) return furnace;
        throw new IllegalStateException("目标方块不是原版兼容熔炉");
    }

    private static int movePlayerToContainer(
            ServerPlayer player, Container container, String requestedId, int requestedCount) {
        int remaining = requestedCount;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack source = inventory.getItem(slot);
            if (source.isEmpty() || !matches(source, requestedId)) continue;
            int moved = insert(container, source, remaining);
            if (moved > 0) {
                source.shrink(moved);
                remaining -= moved;
            }
        }
        return requestedCount - remaining;
    }

    private static int moveContainerToPlayer(
            ServerPlayer player, Container container, String requestedId, int requestedCount) {
        int remaining = requestedCount;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack source = container.getItem(slot);
            if (source.isEmpty() || !matches(source, requestedId)) continue;
            int moved = addToPlayer(player, source, Math.min(remaining, source.getCount()));
            if (moved > 0) {
                source.shrink(moved);
                remaining -= moved;
            }
        }
        return requestedCount - remaining;
    }

    private static int movePlayerToSlot(
            ServerPlayer player, Container container, int targetSlot, String requestedId, int requestedCount) {
        int remaining = requestedCount;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack source = inventory.getItem(slot);
            if (source.isEmpty() || !matches(source, requestedId)) continue;
            int moved = insertSlot(container, targetSlot, source, remaining);
            if (moved > 0) {
                source.shrink(moved);
                remaining -= moved;
            }
        }
        return requestedCount - remaining;
    }

    private static int moveFirstAcceptedPlayerStackToSlot(
            ServerPlayer player, Container container, int targetSlot, int requestedCount) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack source = inventory.getItem(slot);
            if (source.isEmpty() || !container.canPlaceItem(targetSlot, source)) continue;
            int moved = insertSlot(container, targetSlot, source, requestedCount);
            if (moved > 0) source.shrink(moved);
            if (moved > 0) return moved;
        }
        return 0;
    }

    private static int moveSlotToPlayer(ServerPlayer player, Container container, int slot, int requestedCount) {
        ItemStack source = container.getItem(slot);
        if (source.isEmpty()) return 0;
        int moved = addToPlayer(player, source, Math.min(requestedCount, source.getCount()));
        if (moved > 0) source.shrink(moved);
        return moved;
    }

    private static int insert(Container target, ItemStack source, int requestedCount) {
        int remaining = Math.min(requestedCount, source.getCount());
        int original = remaining;
        for (int slot = 0; slot < target.getContainerSize() && remaining > 0; slot++) {
            ItemStack existing = target.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, source)
                    || !target.canPlaceItem(slot, source)) continue;
            int maximum = Math.min(target.getMaxStackSize(), existing.getMaxStackSize());
            int moved = Math.min(remaining, Math.max(0, maximum - existing.getCount()));
            if (moved > 0) {
                existing.grow(moved);
                remaining -= moved;
            }
        }
        for (int slot = 0; slot < target.getContainerSize() && remaining > 0; slot++) {
            if (!target.getItem(slot).isEmpty() || !target.canPlaceItem(slot, source)) continue;
            int moved = Math.min(remaining, Math.min(target.getMaxStackSize(), source.getMaxStackSize()));
            ItemStack inserted = source.copy();
            inserted.setCount(moved);
            target.setItem(slot, inserted);
            remaining -= moved;
        }
        return original - remaining;
    }

    private static int insertSlot(Container target, int slot, ItemStack source, int requestedCount) {
        if (slot < 0 || slot >= target.getContainerSize() || !target.canPlaceItem(slot, source)) return 0;
        int requested = Math.min(requestedCount, source.getCount());
        ItemStack existing = target.getItem(slot);
        int maximum = Math.min(target.getMaxStackSize(), source.getMaxStackSize());
        if (existing.isEmpty()) {
            int moved = Math.min(requested, maximum);
            ItemStack inserted = source.copy();
            inserted.setCount(moved);
            target.setItem(slot, inserted);
            return moved;
        }
        if (!ItemStack.isSameItemSameComponents(existing, source)) return 0;
        int moved = Math.min(requested, Math.max(0, maximum - existing.getCount()));
        if (moved > 0) existing.grow(moved);
        return moved;
    }

    private static int addToPlayer(ServerPlayer player, ItemStack source, int requestedCount) {
        ItemStack moving = source.copy();
        moving.setCount(requestedCount);
        player.getInventory().add(moving);
        return requestedCount - moving.getCount();
    }

    private static JsonObject baseResult(
            String operation, ServerPlayer player, BlockPos position, BlockEntity blockEntity) {
        JsonObject result = new JsonObject();
        result.addProperty("operation", operation);
        result.addProperty("authority", "minecraft_server");
        result.addProperty("dimension", player.serverLevel().dimension().location().toString());
        result.addProperty("x", position.getX());
        result.addProperty("y", position.getY());
        result.addProperty("z", position.getZ());
        result.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(
                player.serverLevel().getBlockState(position).getBlock()).toString());
        return result;
    }

    private static JsonArray summarize(Container container) {
        Map<String, Summary> grouped = new LinkedHashMap<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) continue;
            String id = itemId(stack);
            Summary summary = grouped.computeIfAbsent(id,
                    ignored -> new Summary(id, stack.getHoverName().getString(), 0));
            grouped.put(id, new Summary(summary.id(), summary.displayName(), summary.count() + stack.getCount()));
        }
        JsonArray result = new JsonArray();
        grouped.values().stream().limit(128).forEach(summary -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("item_id", summary.id());
            entry.addProperty("display_name", summary.displayName());
            entry.addProperty("count", summary.count());
            result.add(entry);
        });
        return result;
    }

    private static JsonObject stackData(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        JsonObject result = new JsonObject();
        result.addProperty("item_id", itemId(stack));
        result.addProperty("display_name", stack.getHoverName().getString());
        result.addProperty("count", stack.getCount());
        return result;
    }

    private static void changed(ServerPlayer player, BlockEntity blockEntity, Container container) {
        container.setChanged();
        blockEntity.setChanged();
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }

    private static void changedPlayer(ServerPlayer player) {
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
    }

    private static BlockPos position(JsonObject args) {
        return new BlockPos(requiredCoordinate(args, "x"), requiredCoordinate(args, "y"), requiredCoordinate(args, "z"));
    }

    private static int requiredCoordinate(JsonObject args, String name) {
        if (!args.has(name)) throw new IllegalArgumentException("任务缺少坐标 " + name);
        int value = args.get(name).getAsInt();
        if (Math.abs((long) value) > 30_000_000L) throw new IllegalArgumentException("坐标超出世界边界：" + name);
        return value;
    }

    private static int boundedInt(JsonObject args, String name, int fallback, int minimum, int maximum) {
        int value = args.has(name) ? args.get(name).getAsInt() : fallback;
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String string(JsonObject args, String name, String fallback) {
        return args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsString() : fallback;
    }

    private static boolean matches(ItemStack stack, String requestedId) {
        String requested = requestedId == null ? "" : requestedId.strip().toLowerCase(Locale.ROOT);
        if (requested.isBlank()) return false;
        String actual = itemId(stack);
        if (requested.contains(":")) return actual.equals(requested);
        int separator = actual.indexOf(':');
        return actual.equals("minecraft:" + requested)
                || (separator >= 0 && actual.substring(separator + 1).equals(requested));
    }

    private static String normalizedItemId(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return normalized.isBlank() || normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private record Summary(String id, String displayName, int count) {
    }
}
