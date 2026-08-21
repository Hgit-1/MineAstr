package com.mineastr;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/** World-local, bounded activity storage. Exact player/chunk data never leaves this class. */
public final class MineAstrActivityData extends SavedData {
    private static final String DATA_NAME = "mineastr_activity";
    private static final int PAGE_MAX = 100;
    private static final int ANALYSIS_VERSION = 2;

    private final Map<ActivityKey, ActivityEntry> activity = new HashMap<>();
    private final Set<UUID> optedOut = new HashSet<>();
    private final Set<UUID> learningOptedOut = new HashSet<>();
    private List<Region> regions = List.of();
    private long lastAnalysisMs;
    private int analysisVersion;
    private String snapshotId = "empty";

    public static MineAstrActivityData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MineAstrActivityData::new, MineAstrActivityData::load),
                DATA_NAME);
    }

    private static MineAstrActivityData load(CompoundTag tag, HolderLookup.Provider registries) {
        MineAstrActivityData data = new MineAstrActivityData();
        ListTag optouts = tag.getList("optouts", Tag.TAG_COMPOUND);
        for (int index = 0; index < optouts.size(); index++) {
            CompoundTag item = optouts.getCompound(index);
            if (item.hasUUID("uuid")) data.optedOut.add(item.getUUID("uuid"));
        }
        ListTag learningOptouts = tag.getList("learning_optouts", Tag.TAG_COMPOUND);
        for (int index = 0; index < learningOptouts.size(); index++) {
            CompoundTag item = learningOptouts.getCompound(index);
            if (item.hasUUID("uuid")) data.learningOptedOut.add(item.getUUID("uuid"));
        }
        ListTag entries = tag.getList("activity", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag item = entries.getCompound(index);
            try {
                ActivityKey key = new ActivityKey(
                        item.getLong("week"), item.getString("dimension"), item.getInt("chunk_x"), item.getInt("chunk_z"), item.getUUID("player"));
                Map<String, Long> environmentBlocks = readLongMap(item, "environment_blocks");
                String legacySurface = item.getString("surface_block");
                if (!legacySurface.isBlank()) environmentBlocks.putIfAbsent(legacySurface, 1L);
                data.activity.put(key, new ActivityEntry(
                        item.getLong("samples"), item.getLong("last_seen"),
                        item.getString("biome"), environmentBlocks,
                        readLongMap(item, "feature_counts"), readLongMap(item, "namespace_counts"),
                        item.getLong("environment_samples"), item.getLong("environment_scanned_blocks"),
                        item.getLong("environment_non_air_blocks"), item.getLong("environment_constructed_blocks")));
            } catch (RuntimeException ignored) {
                MineAstr.LOGGER.warn("MineAstr 已忽略损坏的活动数据条目。");
            }
        }
        data.lastAnalysisMs = tag.getLong("last_analysis_ms");
        data.analysisVersion = tag.getInt("analysis_version");
        ListTag savedRegions = tag.getList("regions", Tag.TAG_COMPOUND);
        List<Region> loadedRegions = new ArrayList<>();
        for (int index = 0; index < savedRegions.size(); index++) {
            Region region = Region.load(savedRegions.getCompound(index));
            if (region != null) loadedRegions.add(region);
        }
        data.regions = List.copyOf(loadedRegions);
        data.refreshSnapshotId();
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag optouts = new ListTag();
        optedOut.stream().sorted().forEach(uuid -> {
            CompoundTag item = new CompoundTag();
            item.putUUID("uuid", uuid);
            optouts.add(item);
        });
        tag.put("optouts", optouts);
        ListTag learningOptouts = new ListTag();
        learningOptedOut.stream().sorted().forEach(uuid -> {
            CompoundTag item = new CompoundTag();
            item.putUUID("uuid", uuid);
            learningOptouts.add(item);
        });
        tag.put("learning_optouts", learningOptouts);
        ListTag entries = new ListTag();
        activity.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(row -> {
            ActivityKey key = row.getKey();
            ActivityEntry value = row.getValue();
            CompoundTag item = new CompoundTag();
            item.putLong("week", key.week);
            item.putString("dimension", key.dimension);
            item.putInt("chunk_x", key.chunkX);
            item.putInt("chunk_z", key.chunkZ);
            item.putUUID("player", key.playerUuid);
            item.putLong("samples", value.samples);
            item.putLong("last_seen", value.lastSeenMs);
            item.putString("biome", value.biome);
            writeLongMap(item, "environment_blocks", value.environmentBlocks);
            writeLongMap(item, "feature_counts", value.featureCounts);
            writeLongMap(item, "namespace_counts", value.namespaceCounts);
            item.putLong("environment_samples", value.environmentSamples);
            item.putLong("environment_scanned_blocks", value.environmentScannedBlocks);
            item.putLong("environment_non_air_blocks", value.environmentNonAirBlocks);
            item.putLong("environment_constructed_blocks", value.environmentConstructedBlocks);
            entries.add(item);
        });
        tag.put("activity", entries);
        tag.putLong("last_analysis_ms", lastAnalysisMs);
        tag.putInt("analysis_version", analysisVersion);
        ListTag savedRegions = new ListTag();
        regions.forEach(region -> savedRegions.add(region.save()));
        tag.put("regions", savedRegions);
        return tag;
    }

    public boolean isOptedOut(UUID player) {
        return optedOut.contains(player);
    }

    public void setOptedOut(UUID player, boolean value) {
        if (value) {
            optedOut.add(player);
            activity.keySet().removeIf(key -> key.playerUuid.equals(player));
            regions = regions.stream().map(region -> region.withoutContributor(player)).toList();
            refreshSnapshotId();
        } else {
            optedOut.remove(player);
        }
        setDirty();
    }

    public boolean isLearningOptedOut(UUID player) {
        return learningOptedOut.contains(player);
    }

    public void setLearningOptedOut(UUID player, boolean value) {
        if (value) learningOptedOut.add(player);
        else learningOptedOut.remove(player);
        setDirty();
    }

    public void sample(ServerPlayer player, long nowMs) {
        if (optedOut.contains(player.getUUID())) return;
        ServerLevel level = player.serverLevel();
        ChunkPos chunk = player.chunkPosition();
        ActivityKey key = new ActivityKey(
                week(nowMs), level.dimension().location().toString(), chunk.x, chunk.z, player.getUUID());
        ActivityEntry entry = activity.computeIfAbsent(key, ignored -> new ActivityEntry(
                0, nowMs, "", new HashMap<>(), new HashMap<>(), new HashMap<>(), 0, 0, 0, 0));
        entry.samples++;
        entry.lastSeenMs = nowMs;
        setDirty();
    }

    public void sampleEnvironment(ServerPlayer player, long nowMs, int horizontalRadius, int verticalRadius) {
        if (optedOut.contains(player.getUUID())) return;
        ServerLevel level = player.serverLevel();
        ChunkPos chunk = player.chunkPosition();
        if (!level.hasChunk(chunk.x, chunk.z)) return;
        ActivityKey key = new ActivityKey(
                week(nowMs), level.dimension().location().toString(), chunk.x, chunk.z, player.getUUID());
        ActivityEntry entry = activity.get(key);
        if (entry == null) return;
        BlockPos pos = player.blockPosition();
        entry.biome = level.getBiome(pos).unwrapKey()
                .map(resourceKey -> resourceKey.location().toString()).orElse("unknown");
        EnvironmentSample sample = sampleEnvironment(level, pos, horizontalRadius, verticalRadius);
        sample.blocks.forEach((id, count) -> entry.environmentBlocks.merge(id, count, Long::sum));
        sample.features.forEach((id, count) -> entry.featureCounts.merge(id, count, Long::sum));
        sample.namespaces.forEach((id, count) -> entry.namespaceCounts.merge(id, count, Long::sum));
        entry.environmentScannedBlocks += sample.scannedBlocks;
        entry.environmentNonAirBlocks += sample.nonAirBlocks;
        entry.environmentConstructedBlocks += sample.constructedBlocks;
        compact(entry.environmentBlocks, 24);
        compact(entry.namespaceCounts, 16);
        entry.environmentSamples++;
        setDirty();
    }

    public void prune(long nowMs, int retentionDays) {
        long cutoff = nowMs - ChronoUnit.DAYS.getDuration().toMillis() * retentionDays;
        if (activity.entrySet().removeIf(row -> row.getValue().lastSeenMs < cutoff)) setDirty();
    }

    public boolean analysisDue(long nowMs, int analysisDays) {
        return analysisVersion < ANALYSIS_VERSION || lastAnalysisMs == 0
                || nowMs - lastAnalysisMs >= ChronoUnit.DAYS.getDuration().toMillis() * analysisDays;
    }

    public void analyze(long nowMs, int analysisDays, int sampleSeconds, int minimumChunkMinutes, int minimumRegionMinutes) {
        long cutoff = nowMs - ChronoUnit.DAYS.getDuration().toMillis() * analysisDays;
        Map<ChunkKey, Aggregate> chunks = new HashMap<>();
        for (Map.Entry<ActivityKey, ActivityEntry> row : activity.entrySet()) {
            ActivityKey key = row.getKey();
            ActivityEntry entry = row.getValue();
            if (entry.lastSeenMs < cutoff || optedOut.contains(key.playerUuid)) continue;
            Aggregate aggregate = chunks.computeIfAbsent(
                    new ChunkKey(key.dimension, key.chunkX, key.chunkZ), ignored -> new Aggregate());
            aggregate.samples += entry.samples;
            aggregate.lastSeenMs = Math.max(aggregate.lastSeenMs, entry.lastSeenMs);
            aggregate.contributors.merge(key.playerUuid, entry.samples, Long::sum);
            if (!entry.biome.isBlank()) aggregate.biomes.merge(entry.biome, Math.max(1L, entry.environmentSamples), Long::sum);
            entry.environmentBlocks.forEach((id, count) -> aggregate.blocks.merge(id, count, Long::sum));
            entry.featureCounts.forEach((id, count) -> aggregate.features.merge(id, count, Long::sum));
            entry.namespaceCounts.forEach((id, count) -> aggregate.namespaces.merge(id, count, Long::sum));
            aggregate.environmentSamples += entry.environmentSamples;
            aggregate.environmentScannedBlocks += entry.environmentScannedBlocks;
            aggregate.environmentNonAirBlocks += entry.environmentNonAirBlocks;
            aggregate.environmentConstructedBlocks += entry.environmentConstructedBlocks;
        }
        long minimumSamples = samplesForMinutes(minimumChunkMinutes, sampleSeconds);
        chunks.entrySet().removeIf(row -> row.getValue().samples < minimumSamples);
        List<RegionDraft> drafts = cluster(chunks);
        long minimumRegionSamples = samplesForMinutes(minimumRegionMinutes, sampleSeconds);
        drafts.removeIf(draft -> draft.samples() < minimumRegionSamples);
        assignStableIds(drafts, regions, nowMs);
        List<Region> next = drafts.stream().map(draft -> draft.toRegion(nowMs, sampleSeconds)).toList();
        regions = List.copyOf(next);
        lastAnalysisMs = nowMs;
        analysisVersion = ANALYSIS_VERSION;
        refreshSnapshotId();
        setDirty();
    }

    public JsonObject manifest() {
        JsonObject result = new JsonObject();
        result.addProperty("snapshot_id", snapshotId);
        result.addProperty("generated_at_ms", lastAnalysisMs);
        result.addProperty("analysis_days", MineAstrConfig.ACTIVITY_ANALYSIS_DAYS.getAsInt());
        result.addProperty("region_count", regions.size());
        result.addProperty("page_size_max", PAGE_MAX);
        return result;
    }

    public JsonObject page(String requestedSnapshot, int cursor, int pageSize) {
        if (!snapshotId.equals(requestedSnapshot)) throw new IllegalStateException("地区快照已更新，请重新读取 manifest。");
        int from = Math.max(0, Math.min(cursor, regions.size()));
        int size = Math.max(1, Math.min(PAGE_MAX, pageSize));
        int to = Math.min(regions.size(), from + size);
        JsonArray items = new JsonArray();
        for (int index = from; index < to; index++) items.add(regions.get(index).toJson());
        JsonObject result = new JsonObject();
        result.addProperty("snapshot_id", snapshotId);
        result.addProperty("cursor", from);
        result.addProperty("next_cursor", to);
        result.addProperty("done", to >= regions.size());
        result.add("items", items);
        return result;
    }

    private static List<RegionDraft> cluster(Map<ChunkKey, Aggregate> chunks) {
        Map<String, Set<ChunkKey>> remainingByDimension = new HashMap<>();
        chunks.keySet().forEach(key -> remainingByDimension.computeIfAbsent(key.dimension, ignored -> new HashSet<>()).add(key));
        List<RegionDraft> result = new ArrayList<>();
        for (Set<ChunkKey> remaining : remainingByDimension.values()) {
            while (!remaining.isEmpty()) {
                ChunkKey seed = remaining.iterator().next();
                remaining.remove(seed);
                Set<ChunkKey> members = new HashSet<>();
                ArrayDeque<ChunkKey> queue = new ArrayDeque<>();
                queue.add(seed);
                while (!queue.isEmpty()) {
                    ChunkKey current = queue.removeFirst();
                    members.add(current);
                    for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                        ChunkKey neighbor = new ChunkKey(current.dimension, current.chunkX + dx, current.chunkZ + dz);
                        if (remaining.remove(neighbor)) queue.add(neighbor);
                    }
                }
                result.add(new RegionDraft(seed.dimension, members, chunks));
            }
        }
        result.sort(Comparator.comparingLong(RegionDraft::samples).reversed()
                .thenComparing(RegionDraft::dimension).thenComparingInt(RegionDraft::minX).thenComparingInt(RegionDraft::minZ));
        return result;
    }

    private static void assignStableIds(List<RegionDraft> drafts, List<Region> previous, long nowMs) {
        Set<String> used = new HashSet<>();
        for (RegionDraft draft : drafts) {
            List<Region> matches = new ArrayList<>();
            for (Region candidate : previous) {
                if (used.contains(candidate.id) || !candidate.dimension.equals(draft.dimension)) continue;
                long shared = draft.members.stream().filter(candidate.members::contains).count();
                double overlap = shared / (double) Math.max(1, Math.min(draft.members.size(), candidate.members.size()));
                if (overlap >= 0.25) matches.add(candidate);
            }
            Region best = matches.stream().min(Comparator.comparingLong(Region::createdAtMs)).orElse(null);
            if (best != null) {
                draft.id = best.id;
                draft.createdAtMs = best.createdAtMs;
                draft.aliases.addAll(best.aliases);
                for (Region match : matches) {
                    used.add(match.id);
                    draft.aliases.addAll(match.aliases);
                    if (!match.id.equals(best.id)) draft.aliases.add(match.id);
                }
            } else {
                String seed = draft.dimension + ":" + draft.minX() + ":" + draft.minZ() + ":" + nowMs;
                draft.id = "region-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString().substring(0, 12);
                draft.createdAtMs = nowMs;
            }
        }
    }

    private void refreshSnapshotId() {
        StringBuilder canonical = new StringBuilder(Long.toString(lastAnalysisMs));
        regions.forEach(region -> {
            canonical.append('|').append(region.id).append(':').append(region.samples);
            canonical.append(':').append(region.environmentSamples).append(':').append(region.likelyConstructedRatio);
            region.features.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(row -> canonical.append(':').append(row.getKey()).append('=').append(row.getValue()));
            region.aliases.stream().sorted().forEach(alias -> canonical.append(':').append(alias));
            region.contributors.keySet().stream().sorted().forEach(uuid -> canonical.append(':').append(uuid));
        });
        snapshotId = UUID.nameUUIDFromBytes(canonical.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static long week(long timeMs) {
        return Math.floorDiv(timeMs, ChronoUnit.DAYS.getDuration().toMillis() * 7L);
    }

    void addActivityForTest(UUID player, String dimension, int chunkX, int chunkZ, long nowMs, long samples) {
        activity.put(new ActivityKey(week(nowMs), dimension, chunkX, chunkZ, player),
                new ActivityEntry(samples, nowMs, "minecraft:plains", new HashMap<>(), new HashMap<>(),
                        new HashMap<>(), 1, 0, 0, 0));
    }

    int regionCountForTest() {
        return regions.size();
    }

    String regionIdAtForTest(String dimension, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(dimension, chunkX, chunkZ);
        return regions.stream().filter(region -> region.members.contains(key)).map(Region::id).findFirst().orElse("");
    }

    Set<String> aliasesAtForTest(String dimension, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(dimension, chunkX, chunkZ);
        return regions.stream().filter(region -> region.members.contains(key)).map(Region::aliases).findFirst().orElse(Set.of());
    }

    JsonObject regionJsonAtForTest(int index) {
        Region region = regions.get(index);
        JsonObject json = new JsonObject();
        json.addProperty("environment_sample_count", region.environmentSamples);
        json.addProperty("likely_constructed_ratio", region.likelyConstructedRatio);
        JsonObject features = new JsonObject();
        region.features.forEach(features::addProperty);
        json.add("feature_counts", features);
        JsonArray namespaces = new JsonArray();
        region.namespaces.forEach(namespaces::add);
        json.add("top_block_namespaces", namespaces);
        return json;
    }

    private static EnvironmentSample sampleEnvironment(
            ServerLevel level, BlockPos center, int horizontalRadius, int verticalRadius) {
        Map<String, Long> blocks = new HashMap<>(), features = new HashMap<>(), namespaces = new HashMap<>();
        long scanned = 0, nonAir = 0, constructed = 0;
        int minY = Math.max(level.getMinBuildHeight(), center.getY() - verticalRadius);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + verticalRadius);
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                int x = center.getX() + dx, z = center.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var state = level.getBlockState(pos);
                    scanned++;
                    if (state.isAir()) continue;
                    nonAir++;
                    var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    String id = key == null ? "unknown" : key.toString();
                    blocks.merge(id, 1L, Long::sum);
                    String namespace = key == null ? "unknown" : key.getNamespace();
                    namespaces.merge(namespace, 1L, Long::sum);
                    if (MineAstrTools.isLikelyConstructed(id)) constructed++;
                    String feature = MineAstrTools.featureCategory(id);
                    if (feature != null) features.merge(feature, 1L, Long::sum);
                }
            }
        }
        compact(blocks, 16);
        return new EnvironmentSample(blocks, features, namespaces, scanned, nonAir, constructed);
    }

    private static long samplesForMinutes(int minutes, int sampleSeconds) {
        return Math.max(1L, (minutes * 60L + sampleSeconds - 1L) / sampleSeconds);
    }

    private static Map<String, Long> readLongMap(CompoundTag parent, String name) {
        Map<String, Long> result = new HashMap<>();
        ListTag values = parent.getList(name, Tag.TAG_COMPOUND);
        for (int index = 0; index < values.size(); index++) {
            CompoundTag item = values.getCompound(index);
            String id = item.getString("id");
            if (!id.isBlank()) result.merge(id, item.getLong("count"), Long::sum);
        }
        return result;
    }

    private static void writeLongMap(CompoundTag parent, String name, Map<String, Long> values) {
        ListTag tags = new ListTag();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(row -> {
            CompoundTag item = new CompoundTag();
            item.putString("id", row.getKey());
            item.putLong("count", row.getValue());
            tags.add(item);
        });
        parent.put(name, tags);
    }

    private static void compact(Map<String, Long> values, int limit) {
        if (values.size() <= limit) return;
        Map<String, Long> compacted = new HashMap<>();
        values.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(limit).forEach(row -> compacted.put(row.getKey(), row.getValue()));
        values.clear();
        values.putAll(compacted);
    }

    private record EnvironmentSample(
            Map<String, Long> blocks, Map<String, Long> features, Map<String, Long> namespaces,
            long scannedBlocks, long nonAirBlocks, long constructedBlocks) {
    }

    private record ActivityKey(long week, String dimension, int chunkX, int chunkZ, UUID playerUuid)
            implements Comparable<ActivityKey> {
        @Override public int compareTo(ActivityKey other) {
            int result = Long.compare(week, other.week);
            if (result == 0) result = dimension.compareTo(other.dimension);
            if (result == 0) result = Integer.compare(chunkX, other.chunkX);
            if (result == 0) result = Integer.compare(chunkZ, other.chunkZ);
            if (result == 0) result = playerUuid.compareTo(other.playerUuid);
            return result;
        }
    }

    private static final class ActivityEntry {
        private long samples;
        private long lastSeenMs;
        private String biome;
        private final Map<String, Long> environmentBlocks;
        private final Map<String, Long> featureCounts;
        private final Map<String, Long> namespaceCounts;
        private long environmentSamples;
        private long environmentScannedBlocks;
        private long environmentNonAirBlocks;
        private long environmentConstructedBlocks;
        private ActivityEntry(
                long samples, long lastSeenMs, String biome, Map<String, Long> environmentBlocks,
                Map<String, Long> featureCounts, Map<String, Long> namespaceCounts, long environmentSamples,
                long environmentScannedBlocks, long environmentNonAirBlocks, long environmentConstructedBlocks) {
            this.samples = samples;
            this.lastSeenMs = lastSeenMs;
            this.biome = biome;
            this.environmentBlocks = environmentBlocks;
            this.featureCounts = featureCounts;
            this.namespaceCounts = namespaceCounts;
            this.environmentSamples = environmentSamples;
            this.environmentScannedBlocks = environmentScannedBlocks;
            this.environmentNonAirBlocks = environmentNonAirBlocks;
            this.environmentConstructedBlocks = environmentConstructedBlocks;
        }
    }

    private record ChunkKey(String dimension, int chunkX, int chunkZ) {
    }

    private static final class Aggregate {
        private long samples;
        private long lastSeenMs;
        private final Map<UUID, Long> contributors = new HashMap<>();
        private final Map<String, Long> biomes = new HashMap<>();
        private final Map<String, Long> blocks = new HashMap<>();
        private final Map<String, Long> features = new HashMap<>();
        private final Map<String, Long> namespaces = new HashMap<>();
        private long environmentSamples;
        private long environmentScannedBlocks;
        private long environmentNonAirBlocks;
        private long environmentConstructedBlocks;
    }

    private static final class RegionDraft {
        private String id;
        private long createdAtMs;
        private final Set<String> aliases = new HashSet<>();
        private final String dimension;
        private final Set<ChunkKey> members;
        private final Map<ChunkKey, Aggregate> source;
        private RegionDraft(String dimension, Set<ChunkKey> members, Map<ChunkKey, Aggregate> source) {
            this.dimension = dimension;
            this.members = members;
            this.source = source;
        }
        private String dimension() { return dimension; }
        private int minX() { return members.stream().mapToInt(ChunkKey::chunkX).min().orElse(0); }
        private int minZ() { return members.stream().mapToInt(ChunkKey::chunkZ).min().orElse(0); }
        private long samples() { return members.stream().map(source::get).mapToLong(value -> value.samples).sum(); }
        private Region toRegion(long analyzedAtMs, int sampleSeconds) {
            long samples = samples();
            long weightedX = 0, weightedZ = 0, lastSeen = 0;
            Map<UUID, Long> contributors = new HashMap<>();
            Map<String, Long> biomes = new HashMap<>(), blocks = new HashMap<>(), features = new HashMap<>(), namespaces = new HashMap<>();
            long environmentSamples = 0, environmentNonAir = 0, environmentConstructed = 0;
            for (ChunkKey member : members) {
                Aggregate value = source.get(member);
                weightedX += (member.chunkX * 16L + 8L) * value.samples;
                weightedZ += (member.chunkZ * 16L + 8L) * value.samples;
                lastSeen = Math.max(lastSeen, value.lastSeenMs);
                value.contributors.forEach((key, count) -> contributors.merge(key, count, Long::sum));
                value.biomes.forEach((key, count) -> biomes.merge(key, count, Long::sum));
                value.blocks.forEach((key, count) -> blocks.merge(key, count, Long::sum));
                value.features.forEach((key, count) -> features.merge(key, count, Long::sum));
                value.namespaces.forEach((key, count) -> namespaces.merge(key, count, Long::sum));
                environmentSamples += value.environmentSamples;
                environmentNonAir += value.environmentNonAirBlocks;
                environmentConstructed += value.environmentConstructedBlocks;
            }
            int centerX = round64(weightedX / Math.max(1L, samples));
            int centerZ = round64(weightedZ / Math.max(1L, samples));
            return new Region(id, Set.copyOf(aliases), dimension, Set.copyOf(members), centerX, centerZ, samples, sampleSeconds,
                    createdAtMs,
                    lastSeen, analyzedAtMs, top(biomes, 5), top(blocks, 8), Map.copyOf(contributors),
                    environmentSamples, environmentNonAir == 0 ? 0.0 : round3((double) environmentConstructed / environmentNonAir),
                    topMap(features, 32), top(namespaces, 12));
        }
    }

    private static int round64(long coordinate) {
        return Math.toIntExact(Math.round(coordinate / 64.0) * 64L);
    }

    private static List<String> top(Map<String, Long> values, int limit) {
        return values.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(limit).map(Map.Entry::getKey).toList();
    }

    private static Map<String, Long> topMap(Map<String, Long> values, int limit) {
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(limit).forEach(row -> result.put(row.getKey(), row.getValue()));
        return Map.copyOf(result);
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record Region(String id, Set<String> aliases, String dimension, Set<ChunkKey> members, int centerX, int centerZ,
                          long samples, int sampleSeconds, long createdAtMs, long lastSeenMs, long analyzedAtMs,
                          List<String> biomes, List<String> blocks, Map<UUID, Long> contributors,
                          long environmentSamples, double likelyConstructedRatio,
                          Map<String, Long> features, List<String> namespaces) {
        private JsonObject toJson() {
            JsonObject item = new JsonObject();
            item.addProperty("region_id", id);
            JsonArray aliasArray = new JsonArray(); aliases.stream().sorted().forEach(aliasArray::add); item.add("aliases", aliasArray);
            item.addProperty("dimension", dimension);
            item.addProperty("center_x_approx", centerX);
            item.addProperty("center_z_approx", centerZ);
            item.addProperty("activity_minutes", samples * sampleSeconds / 60L);
            item.addProperty("chunk_count", members.size());
            item.addProperty("last_seen_ms", lastSeenMs);
            item.addProperty("analyzed_at_ms", analyzedAtMs);
            JsonArray biomeArray = new JsonArray(); biomes.forEach(biomeArray::add); item.add("biomes", biomeArray);
            JsonArray blockArray = new JsonArray(); blocks.forEach(blockArray::add); item.add("surface_blocks", blockArray);
            item.addProperty("environment_sample_count", environmentSamples);
            item.addProperty("likely_constructed_ratio", likelyConstructedRatio);
            JsonObject featureObject = new JsonObject();
            features.forEach(featureObject::addProperty);
            item.add("feature_counts", featureObject);
            JsonArray namespaceArray = new JsonArray(); namespaces.forEach(namespaceArray::add);
            item.add("top_block_namespaces", namespaceArray);
            JsonArray contributorArray = new JsonArray();
            contributors.entrySet().stream().sorted(Map.Entry.<UUID, Long>comparingByValue().reversed()).forEach(row -> {
                JsonObject contributor = new JsonObject();
                contributor.addProperty("contributor_key", contributorKey(row.getKey()));
                contributor.addProperty("activity_minutes", row.getValue() * sampleSeconds / 60L);
                contributorArray.add(contributor);
            });
            item.add("contributors_private", contributorArray);
            item.addProperty("privacy_note", "中心已按约 64 格降精度；只传输周边方块聚合特征，不传输逐点轨迹、精确边界、容器内容、告示牌文字或完整建筑形状。");
            return item;
        }
        private Region withoutContributor(UUID player) {
            if (!contributors.containsKey(player)) return this;
            Map<UUID, Long> updated = new HashMap<>(contributors);
            updated.remove(player);
            return new Region(id, aliases, dimension, members, centerX, centerZ, samples, sampleSeconds,
                    createdAtMs, lastSeenMs, analyzedAtMs, biomes, blocks, Map.copyOf(updated),
                    environmentSamples, likelyConstructedRatio, features, namespaces);
        }
        private static String contributorKey(UUID uuid) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] bytes = digest.digest(("mineastr:" + MineAstrConfig.SERVER_ID.get() + ":" + uuid)
                        .getBytes(StandardCharsets.UTF_8));
                return java.util.HexFormat.of().formatHex(bytes);
            } catch (NoSuchAlgorithmException exc) {
                throw new IllegalStateException("SHA-256 unavailable", exc);
            }
        }
        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", id); tag.putString("dimension", dimension); tag.putInt("center_x", centerX); tag.putInt("center_z", centerZ);
            ListTag aliasTags = new ListTag(); aliases.forEach(value -> { CompoundTag item = new CompoundTag(); item.putString("value", value); aliasTags.add(item); }); tag.put("aliases", aliasTags);
            tag.putLong("samples", samples); tag.putInt("sample_seconds", sampleSeconds); tag.putLong("created_at", createdAtMs); tag.putLong("last_seen", lastSeenMs); tag.putLong("analyzed_at", analyzedAtMs);
            ListTag chunkTags = new ListTag(); members.forEach(chunk -> { CompoundTag item = new CompoundTag(); item.putInt("x", chunk.chunkX); item.putInt("z", chunk.chunkZ); chunkTags.add(item); }); tag.put("chunks", chunkTags);
            ListTag biomeTags = new ListTag(); biomes.forEach(value -> { CompoundTag item = new CompoundTag(); item.putString("value", value); biomeTags.add(item); }); tag.put("biomes", biomeTags);
            ListTag blockTags = new ListTag(); blocks.forEach(value -> { CompoundTag item = new CompoundTag(); item.putString("value", value); blockTags.add(item); }); tag.put("blocks", blockTags);
            ListTag contributorTags = new ListTag(); contributors.forEach((uuid, count) -> { CompoundTag item = new CompoundTag(); item.putUUID("uuid", uuid); item.putLong("samples", count); contributorTags.add(item); }); tag.put("contributors", contributorTags);
            tag.putLong("environment_samples", environmentSamples);
            tag.putDouble("likely_constructed_ratio", likelyConstructedRatio);
            writeLongMap(tag, "feature_counts", features);
            ListTag namespaceTags = new ListTag(); namespaces.forEach(value -> { CompoundTag item = new CompoundTag(); item.putString("value", value); namespaceTags.add(item); }); tag.put("namespaces", namespaceTags);
            return tag;
        }
        private static Region load(CompoundTag tag) {
            String id = tag.getString("id"), dimension = tag.getString("dimension");
            if (id.isBlank() || dimension.isBlank()) return null;
            Set<ChunkKey> chunks = new HashSet<>(); ListTag chunkTags = tag.getList("chunks", Tag.TAG_COMPOUND);
            for (int i = 0; i < chunkTags.size(); i++) { CompoundTag item = chunkTags.getCompound(i); chunks.add(new ChunkKey(dimension, item.getInt("x"), item.getInt("z"))); }
            List<String> aliases = strings(tag.getList("aliases", Tag.TAG_COMPOUND)); List<String> biomes = strings(tag.getList("biomes", Tag.TAG_COMPOUND)); List<String> blocks = strings(tag.getList("blocks", Tag.TAG_COMPOUND));
            Map<UUID, Long> contributors = new HashMap<>(); ListTag contributorTags = tag.getList("contributors", Tag.TAG_COMPOUND);
            for (int i = 0; i < contributorTags.size(); i++) { CompoundTag item = contributorTags.getCompound(i); if (item.hasUUID("uuid")) contributors.put(item.getUUID("uuid"), item.getLong("samples")); }
            long createdAt = tag.getLong("created_at");
            if (createdAt == 0) createdAt = tag.getLong("analyzed_at");
            List<String> namespaces = strings(tag.getList("namespaces", Tag.TAG_COMPOUND));
            return new Region(id, Set.copyOf(aliases), dimension, Set.copyOf(chunks), tag.getInt("center_x"), tag.getInt("center_z"), tag.getLong("samples"), tag.getInt("sample_seconds"), createdAt, tag.getLong("last_seen"), tag.getLong("analyzed_at"), List.copyOf(biomes), List.copyOf(blocks), Map.copyOf(contributors), tag.getLong("environment_samples"), tag.getDouble("likely_constructed_ratio"), Map.copyOf(readLongMap(tag, "feature_counts")), List.copyOf(namespaces));
        }
        private static List<String> strings(ListTag tags) { List<String> result = new ArrayList<>(); for (int i = 0; i < tags.size(); i++) result.add(tags.getCompound(i).getString("value")); return result; }
    }
}
