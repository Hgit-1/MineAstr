package com.mineastr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Server-authoritative, privacy-bounded facts used by WorldMind.
 *
 * <p>Raw traces never contain chat, NBT, sign text, complete container contents or a continuous
 * player trail. The exact device anchor remains in the world-local file and is reduced before a
 * confirmed summary is copied into AstrBot RAG.</p>
 */
public final class MineAstrWorldMind implements AutoCloseable {
    public static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_EVENTS_PER_TRACE = 256;
    private static final int MAX_PAGE_SIZE = 100;
    private static final long MAX_RECORDING_MILLIS = 15L * 60_000L;

    private final Map<UUID, Recording> recordings = new HashMap<>();
    private final Map<String, Recording> passiveRecordings = new HashMap<>();
    private final List<PendingObservation> pending = new ArrayList<>();
    private final List<JsonObject> demonstrations = new ArrayList<>();
    private final Map<String, JsonObject> nodes = new LinkedHashMap<>();

    private MinecraftServer server;
    private Path file;
    private String snapshotId = UUID.randomUUID().toString();
    private long generatedAtMs;
    private String serverFingerprint = "";
    private String lastError = "";
    private boolean dirty;

    public synchronized void start(MinecraftServer currentServer) {
        server = currentServer;
        serverFingerprint = computeServerFingerprint(currentServer);
        file = currentServer.getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("mineastr").resolve("worldmind").resolve("snapshot.json");
        load();
        prune(System.currentTimeMillis());
        saveIfDirty();
    }

    public synchronized JsonObject startRecording(ServerPlayer player, String requestedName) {
        ensureStarted();
        if (isOptedOut(player)) throw new IllegalStateException("玩家已退出 MineAstr 技能学习");
        Recording existing = recordings.get(player.getUUID());
        if (existing != null) return recordingStatus(existing, false);
        long now = System.currentTimeMillis();
        Recording recording = new Recording(
                UUID.randomUUID().toString(), sanitizeName(requestedName), contributor(player), now, false);
        recordings.put(player.getUUID(), recording);
        return recordingStatus(recording, false);
    }

    public synchronized JsonObject stopRecording(ServerPlayer player) {
        Recording recording = recordings.remove(player.getUUID());
        if (recording == null) throw new IllegalStateException("当前没有正在录制的示范");
        finalizeRecording(recording, System.currentTimeMillis());
        JsonObject result = recordingStatus(recording, true);
        result.addProperty("saved", !recording.events.isEmpty());
        return result;
    }

    public synchronized boolean isRecording(UUID playerUuid) {
        return recordings.containsKey(playerUuid);
    }

    public synchronized void recordInteraction(
            ServerPlayer player, BlockPos position, BlockState state, ItemStack heldItem) {
        if (server == null || isOptedOut(player)) return;
        Recording recording = recordings.get(player.getUUID());
        boolean passive = false;
        if (recording == null) {
            if (!MineAstrConfig.ENABLE_PASSIVE_SKILL_LEARNING.getAsBoolean()
                    || !highInformation(state)) return;
            long now = System.currentTimeMillis();
            recording = new Recording(UUID.randomUUID().toString(), "被动设备交互", contributor(player), now, true);
            passiveRecordings.put(recording.traceId, recording);
            passive = true;
        }
        long now = System.currentTimeMillis();
        JsonObject event = baseEvent(recording, "block_interact", player.serverLevel(), position, now);
        event.addProperty("block_id", blockId(state));
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(heldItem.getItem());
        if (!heldItem.isEmpty() && itemId != null) event.addProperty("held_item_id", itemId.toString());
        event.addProperty("hand_item_count", Math.max(0, heldItem.getCount()));
        append(recording, event);
        upsertDeviceNode(player.serverLevel(), position, state, now);
        pending.add(new PendingObservation(
                player.getUUID(), recording.traceId, position.immutable(), player.serverLevel().dimension().location().toString(),
                inventoryCounts(player), now + 500L, passive));
    }

    public synchronized void recordBlockChange(
            ServerPlayer player, String eventType, ServerLevel level, BlockPos position, BlockState state) {
        Recording recording = recordings.get(player.getUUID());
        if (recording == null || isOptedOut(player)) return;
        JsonObject event = baseEvent(recording, eventType, level, position, System.currentTimeMillis());
        event.addProperty("block_id", blockId(state));
        append(recording, event);
    }

    public synchronized void tick(MinecraftServer currentServer) {
        if (server == null) return;
        long now = System.currentTimeMillis();
        Iterator<PendingObservation> iterator = pending.iterator();
        while (iterator.hasNext()) {
            PendingObservation observation = iterator.next();
            if (now < observation.dueAtMs) continue;
            iterator.remove();
            Recording recording = recordings.get(observation.playerUuid);
            if (recording == null || !recording.traceId.equals(observation.traceId)) {
                recording = passiveRecordings.get(observation.traceId);
            }
            ServerPlayer player = currentServer.getPlayerList().getPlayer(observation.playerUuid);
            ServerLevel level = findLevel(currentServer, observation.dimension);
            if (recording != null && player != null && level != null && level.hasChunkAt(observation.position)) {
                BlockState afterState = level.getBlockState(observation.position);
                JsonObject result = baseEvent(recording, "interaction_result", level, observation.position, now);
                result.addProperty("block_id", blockId(afterState));
                result.addProperty("menu_type", sanitizeToken(player.containerMenu.getClass().getSimpleName(), 80));
                result.add("inventory_delta", inventoryDelta(observation.beforeInventory, inventoryCounts(player)));
                append(recording, result);
            }
            if (observation.passive) {
                Recording completed = passiveRecordings.remove(observation.traceId);
                if (completed != null) finalizeRecording(completed, now);
            }
        }
        for (Iterator<Map.Entry<UUID, Recording>> active = recordings.entrySet().iterator(); active.hasNext();) {
            Map.Entry<UUID, Recording> entry = active.next();
            if (now - entry.getValue().startedAtMs > MAX_RECORDING_MILLIS) {
                finalizeRecording(entry.getValue(), now);
                active.remove();
            }
        }
        prune(now);
        saveIfDirty();
    }

    public synchronized void removeContributor(ServerPlayer player) {
        String key = contributor(player);
        recordings.remove(player.getUUID());
        pending.removeIf(item -> item.playerUuid.equals(player.getUUID()));
        passiveRecordings.values().removeIf(item -> item.contributor.equals(key));
        if (demonstrations.removeIf(item -> key.equals(string(item, "contributor_key")))) dirty = true;
        saveIfDirty();
    }

    public synchronized JsonObject status() {
        JsonObject result = new JsonObject();
        result.addProperty("enabled", true);
        result.addProperty("schema_version", SCHEMA_VERSION);
        result.addProperty("snapshot_id", snapshotId);
        result.addProperty("generated_at_ms", generatedAtMs);
        result.addProperty("server_fingerprint", serverFingerprint);
        result.addProperty("active_recordings", recordings.size());
        result.addProperty("demonstration_count", demonstrations.size());
        result.addProperty("node_count", nodes.size());
        result.addProperty("passive_learning_enabled", MineAstrConfig.ENABLE_PASSIVE_SKILL_LEARNING.getAsBoolean());
        result.addProperty("raw_retention_days", MineAstrConfig.WORLDMIND_RAW_RETENTION_DAYS.getAsInt());
        String sandbox = MineAstrConfig.SKILL_SANDBOX_REGION.get().strip();
        result.addProperty("sandbox_configured", !sandbox.isEmpty());
        result.addProperty("automatic_validation_allowed", !sandbox.isEmpty());
        result.addProperty("last_error", lastError);
        return result;
    }

    public synchronized JsonObject manifest() {
        JsonObject result = status();
        JsonObject categories = new JsonObject();
        categories.addProperty("nodes", nodes.size());
        categories.addProperty("demonstrations", demonstrations.size());
        result.add("categories", categories);
        return result;
    }

    public synchronized JsonObject page(String expectedSnapshotId, String category, int cursor, int pageSize) {
        if (!snapshotId.equals(expectedSnapshotId)) throw new IllegalStateException("WorldMind 快照已更新，请重新读取 manifest");
        List<JsonObject> source = switch (category) {
            case "nodes" -> new ArrayList<>(nodes.values());
            case "demonstrations" -> demonstrations;
            default -> throw new IllegalArgumentException("不支持的 WorldMind 分类：" + category);
        };
        int start = Math.max(0, Math.min(cursor, source.size()));
        int size = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
        int end = Math.min(source.size(), start + size);
        JsonArray entries = new JsonArray();
        for (int index = start; index < end; index++) entries.add(source.get(index).deepCopy());
        JsonObject result = new JsonObject();
        result.addProperty("snapshot_id", snapshotId);
        result.addProperty("category", category);
        result.addProperty("cursor", start);
        result.addProperty("next_cursor", end < source.size() ? end : -1);
        result.addProperty("complete", end >= source.size());
        result.add("entries", entries);
        return result;
    }

    @Override
    public synchronized void close() {
        long now = System.currentTimeMillis();
        for (Recording recording : recordings.values()) finalizeRecording(recording, now);
        recordings.clear();
        passiveRecordings.clear();
        pending.clear();
        saveIfDirty();
        server = null;
        file = null;
    }

    private void finalizeRecording(Recording recording, long now) {
        if (recording.events.isEmpty()) return;
        JsonObject trace = new JsonObject();
        trace.addProperty("trace_id", recording.traceId);
        trace.addProperty("name", recording.name);
        trace.addProperty("contributor_key", recording.contributor);
        trace.addProperty("capture_mode", recording.passive ? "passive" : "explicit");
        trace.addProperty("started_at_ms", recording.startedAtMs);
        trace.addProperty("finished_at_ms", now);
        trace.addProperty("state", "candidate");
        trace.addProperty("event_count", recording.events.size());
        boolean sandboxEligible = !MineAstrConfig.SKILL_SANDBOX_REGION.get().strip().isEmpty();
        for (JsonElement item : recording.events) {
            if (item.isJsonObject() && item.getAsJsonObject().has("x") && !insideSandbox(item.getAsJsonObject())) {
                sandboxEligible = false;
                break;
            }
        }
        trace.addProperty("sandbox_eligible", sandboxEligible);
        trace.add("events", recording.events.deepCopy());
        demonstrations.add(trace);
        demonstrations.sort(Comparator.comparingLong(item -> longValue(item, "finished_at_ms")));
        int maximum = MineAstrConfig.WORLDMIND_MAX_DEMONSTRATIONS.getAsInt();
        while (demonstrations.size() > maximum) demonstrations.remove(0);
        markChanged(now);
    }

    private void upsertDeviceNode(ServerLevel level, BlockPos position, BlockState state, long now) {
        String dimension = level.dimension().location().toString();
        String blockId = blockId(state);
        String id = "device-" + sha256(dimension + ":" + position.asLong() + ":" + blockId).substring(0, 20);
        JsonObject node = nodes.computeIfAbsent(id, ignored -> new JsonObject());
        node.addProperty("node_id", id);
        node.addProperty("node_type", "device");
        node.addProperty("name", blockId);
        node.addProperty("dimension", dimension);
        node.addProperty("x", position.getX());
        node.addProperty("y", position.getY());
        node.addProperty("z", position.getZ());
        node.addProperty("resource_id", blockId);
        node.addProperty("confidence", 0.35);
        node.addProperty("state", "candidate");
        node.addProperty("last_seen_ms", now);
        markChanged(now);
    }

    private void append(Recording recording, JsonObject event) {
        if (recording.events.size() >= MAX_EVENTS_PER_TRACE) return;
        recording.events.add(event);
    }

    private JsonObject baseEvent(Recording recording, String type, ServerLevel level, BlockPos position, long now) {
        JsonObject event = new JsonObject();
        event.addProperty("event_type", type);
        event.addProperty("offset_ms", Math.max(0L, now - recording.startedAtMs));
        event.addProperty("dimension", level.dimension().location().toString());
        event.addProperty("x", position.getX());
        event.addProperty("y", position.getY());
        event.addProperty("z", position.getZ());
        return event;
    }

    private JsonObject recordingStatus(Recording recording, boolean finished) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("trace_id", recording.traceId);
        result.addProperty("name", recording.name);
        result.addProperty("recording", !finished);
        result.addProperty("event_count", recording.events.size());
        result.addProperty("started_at_ms", recording.startedAtMs);
        return result;
    }

    private boolean isOptedOut(ServerPlayer player) {
        MineAstrActivityData data = server == null ? null : MineAstrActivityData.get(server);
        return data != null && data.isLearningOptedOut(player.getUUID());
    }

    private static boolean highInformation(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return state.hasBlockEntity() || (id != null && !"minecraft".equals(id.getNamespace()));
    }

    private static String blockId(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? "minecraft:air" : id.toString();
    }

    private static Map<String, Integer> inventoryCounts(ServerPlayer player) {
        Map<String, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null) counts.merge(id.toString(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    static JsonArray inventoryDelta(Map<String, Integer> before, Map<String, Integer> after) {
        JsonArray result = new JsonArray();
        java.util.Set<String> ids = new java.util.TreeSet<>(before.keySet());
        ids.addAll(after.keySet());
        for (String id : ids) {
            int delta = after.getOrDefault(id, 0) - before.getOrDefault(id, 0);
            if (delta == 0) continue;
            JsonObject item = new JsonObject();
            item.addProperty("item_id", id);
            item.addProperty("delta", delta);
            result.add(item);
            if (result.size() >= 64) break;
        }
        return result;
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) return level;
        }
        return null;
    }

    static boolean insideSandbox(JsonObject event) {
        String[] fields = MineAstrConfig.SKILL_SANDBOX_REGION.get().split(",");
        if (fields.length != 7 || !fields[0].strip().equals(string(event, "dimension"))) return false;
        try {
            int x = event.get("x").getAsInt();
            int y = event.get("y").getAsInt();
            int z = event.get("z").getAsInt();
            int x1 = Integer.parseInt(fields[1].strip());
            int y1 = Integer.parseInt(fields[2].strip());
            int z1 = Integer.parseInt(fields[3].strip());
            int x2 = Integer.parseInt(fields[4].strip());
            int y2 = Integer.parseInt(fields[5].strip());
            int z2 = Integer.parseInt(fields[6].strip());
            return x >= Math.min(x1, x2) && x <= Math.max(x1, x2)
                    && y >= Math.min(y1, y2) && y <= Math.max(y1, y2)
                    && z >= Math.min(z1, z2) && z <= Math.max(z1, z2);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void prune(long now) {
        long cutoff = now - MineAstrConfig.WORLDMIND_RAW_RETENTION_DAYS.getAsInt() * 86_400_000L;
        if (demonstrations.removeIf(item -> longValue(item, "finished_at_ms") < cutoff)) markChanged(now);
    }

    private void load() {
        demonstrations.clear();
        nodes.clear();
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            snapshotId = string(root, "snapshot_id");
            if (snapshotId.isBlank()) snapshotId = UUID.randomUUID().toString();
            generatedAtMs = longValue(root, "generated_at_ms");
            for (JsonElement item : array(root, "nodes")) {
                if (!item.isJsonObject()) continue;
                JsonObject node = item.getAsJsonObject();
                String id = string(node, "node_id");
                if (!id.isBlank()) nodes.put(id, node);
            }
            for (JsonElement item : array(root, "demonstrations")) {
                if (item.isJsonObject()) demonstrations.add(item.getAsJsonObject());
            }
            lastError = "";
        } catch (Exception exc) {
            lastError = "读取 WorldMind 快照失败：" + safeMessage(exc);
            MineAstr.LOGGER.warn("{}", lastError);
        }
    }

    private void saveIfDirty() {
        if (!dirty || file == null) return;
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("schema_version", SCHEMA_VERSION);
            root.addProperty("snapshot_id", snapshotId);
            root.addProperty("generated_at_ms", generatedAtMs);
            JsonArray nodeArray = new JsonArray();
            nodes.values().forEach(nodeArray::add);
            root.add("nodes", nodeArray);
            JsonArray traceArray = new JsonArray();
            demonstrations.forEach(traceArray::add);
            root.add("demonstrations", traceArray);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
            lastError = "";
        } catch (IOException exc) {
            lastError = "保存 WorldMind 快照失败：" + safeMessage(exc);
            MineAstr.LOGGER.warn("{}", lastError);
        }
    }

    private void markChanged(long now) {
        generatedAtMs = now;
        snapshotId = UUID.randomUUID().toString();
        dirty = true;
    }

    private void ensureStarted() {
        if (server == null) throw new IllegalStateException("WorldMind 尚未启动");
    }

    private String contributor(ServerPlayer player) {
        return sha256(MineAstrConfig.SERVER_ID.get() + ":worldmind:" + player.getUUID()).substring(0, 32);
    }

    private static String sanitizeName(String value) {
        String selected = value == null ? "" : value.replaceAll("[\\r\\n\\t]", " ").strip();
        return selected.isEmpty() ? "未命名示范" : selected.substring(0, Math.min(80, selected.length()));
    }

    private static String sanitizeToken(String value, int limit) {
        String selected = value == null ? "" : value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return selected.substring(0, Math.min(limit, selected.length()));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException(exc);
        }
    }

    private static String computeServerFingerprint(MinecraftServer server) {
        List<String> values = new ArrayList<>();
        values.add("mineastr@" + MineAstr.MOD_VERSION);
        values.add("minecraft@" + server.getServerVersion());
        net.neoforged.fml.ModList.get().getMods().stream()
                .map(info -> "mod:" + info.getModId() + "@" + info.getVersion())
                .sorted()
                .forEach(values::add);
        BuiltInRegistries.BLOCK.keySet().stream().map(id -> "block:" + id).sorted().forEach(values::add);
        BuiltInRegistries.ITEM.keySet().stream().map(id -> "item:" + id).sorted().forEach(values::add);
        BuiltInRegistries.ENTITY_TYPE.keySet().stream().map(id -> "entity:" + id).sorted().forEach(values::add);
        BuiltInRegistries.RECIPE_SERIALIZER.keySet().stream().map(id -> "recipe_serializer:" + id)
                .sorted().forEach(values::add);
        return sha256(String.join("\n", values));
    }

    private static JsonArray array(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonArray() ? object.getAsJsonArray(name) : new JsonArray();
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : "";
    }

    private static long longValue(JsonObject object, String name) {
        try {
            return object.has(name) ? object.get(name).getAsLong() : 0L;
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getSimpleName() : value.replaceAll("[\\r\\n\\t]", " ");
    }

    private static final class Recording {
        private final String traceId;
        private final String name;
        private final String contributor;
        private final long startedAtMs;
        private final boolean passive;
        private final JsonArray events = new JsonArray();

        private Recording(String traceId, String name, String contributor, long startedAtMs, boolean passive) {
            this.traceId = traceId;
            this.name = name;
            this.contributor = contributor;
            this.startedAtMs = startedAtMs;
            this.passive = passive;
        }
    }

    private record PendingObservation(
            UUID playerUuid, String traceId, BlockPos position, String dimension,
            Map<String, Integer> beforeInventory, long dueAtMs, boolean passive) {
    }
}
