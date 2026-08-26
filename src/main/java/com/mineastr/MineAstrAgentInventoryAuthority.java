package com.mineastr;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

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
        BlockPos position = position(args);
        BlockEntity blockEntity = player.serverLevel().getBlockEntity(position);
        if (!(blockEntity instanceof Container container)) {
            throw new IllegalStateException("目标方块不是服务端可识别的容器");
        }
        return switch (taskType) {
            case "container_inspect" -> containerInspect(player, position, blockEntity, container);
            case "container_transfer" -> containerTransfer(player, position, blockEntity, container, args);
            case "furnace_inspect" -> furnaceInspect(player, position, blockEntity);
            case "furnace_process" -> furnaceProcess(player, position, blockEntity, args);
            case "furnace_collect" -> furnaceCollect(player, position, blockEntity);
            default -> throw new IllegalArgumentException("不支持的服务端物品操作：" + taskType);
        };
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

    private static String normalizedItemId(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return normalized.isBlank() || normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    private static boolean matches(ItemStack stack, String requestedId) {
        return itemId(stack).equals(requestedId);
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private record Summary(String id, String displayName, int count) {
    }
}
