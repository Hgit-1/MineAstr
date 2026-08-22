package com.mineastr;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;

/**
 * Exports RoadWeaver's persisted, paved-road blueprint for the isolated Node
 * Agent without making RoadWeaver a mandatory linkage dependency.
 */
final class MineAstrRoadNetworkSnapshot implements AutoCloseable {
    static final int SCHEMA_VERSION = 1;
    static final long REFRESH_INTERVAL_MS = 60_000L;
    static final int MAX_POINTS = 500_000;
    private static final Gson GSON = new Gson();
    private static final String ROADWEAVER_MOD_ID = "roadweaver";

    private Path snapshotFile;
    private long nextRefreshMs;
    private long generatedAtMs;
    private int roadCount;
    private int pointCount;
    private boolean installed;
    private boolean available;
    private String sourceVersion = "";
    private String lastError = "";

    void start(MinecraftServer server) {
        snapshotFile = server.getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("mineastr").resolve("agent")
                .resolve("road-network.json").toAbsolutePath().normalize();
        nextRefreshMs = 0L;
        refresh(server, true);
    }

    void tick(MinecraftServer server) {
        if (server == null || System.currentTimeMillis() < nextRefreshMs) return;
        refresh(server, false);
    }

    JsonObject status() {
        JsonObject status = new JsonObject();
        status.addProperty("enabled", MineAstrConfig.AGENT_ROADWEAVER_ROUTING_ENABLED.getAsBoolean());
        status.addProperty("installed", installed);
        status.addProperty("available", available);
        status.addProperty("source_version", sourceVersion);
        status.addProperty("generated_at_ms", generatedAtMs);
        status.addProperty("road_count", roadCount);
        status.addProperty("point_count", pointCount);
        if (!lastError.isBlank()) status.addProperty("last_error", lastError);
        return status;
    }

    private void refresh(MinecraftServer server, boolean initial) {
        nextRefreshMs = System.currentTimeMillis() + REFRESH_INTERVAL_MS;
        installed = ModList.get().isLoaded(ROADWEAVER_MOD_ID);
        sourceVersion = ModList.get().getModContainerById(ROADWEAVER_MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString()).orElse("");
        if (!MineAstrConfig.AGENT_ROADWEAVER_ROUTING_ENABLED.getAsBoolean() || !installed) {
            writeUnavailable(installed ? "disabled_by_config" : "roadweaver_not_installed");
            return;
        }
        try {
            JsonObject snapshot = collect(server.overworld());
            atomicWrite(snapshot);
            generatedAtMs = snapshot.get("generated_at_ms").getAsLong();
            available = true;
            lastError = "";
            if (initial) {
                MineAstr.LOGGER.info("MineAstr RoadWeaver 路网已就绪：roads={} points={} version={}",
                        roadCount, pointCount, sourceVersion);
            }
        } catch (ReflectiveOperationException | RuntimeException | java.io.IOException failure) {
            available = false;
            lastError = safeMessage(failure);
            writeUnavailable("roadweaver_adapter_failed");
            MineAstr.LOGGER.warn("MineAstr 读取 RoadWeaver 路网失败，将回退普通寻路：{}", lastError);
        }
    }

    private JsonObject collect(ServerLevel level) throws ReflectiveOperationException {
        Class<?> storageClass = Class.forName(
                "net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage", false,
                Thread.currentThread().getContextClassLoader());
        Method loadAll = storageClass.getMethod("loadAll", ServerLevel.class);
        Method fingerprintMethod = java.util.Arrays.stream(storageClass.getMethods())
                .filter(method -> "computeFingerprint".equals(method.getName()) && method.getParameterCount() == 1)
                .findFirst().orElse(null);
        Object loaded = loadAll.invoke(null, level);
        if (!(loaded instanceof List<?> roads)) throw new IllegalStateException("RoadShardStorage.loadAll 未返回列表");

        JsonArray roadArray = new JsonArray();
        int totalPoints = 0;
        for (Object road : roads) {
            if (road == null) continue;
            Method segmentsMethod = road.getClass().getMethod("roadSegmentList");
            Object rawSegments = segmentsMethod.invoke(road);
            if (!(rawSegments instanceof List<?> segments) || segments.size() < 2) continue;
            Object rawTargetY = road.getClass().getMethod("targetY").invoke(road);
            List<?> targetY = rawTargetY instanceof List<?> list ? list : List.of();
            if ((long) totalPoints + segments.size() > MAX_POINTS) {
                throw new IllegalStateException("RoadWeaver 路网超过 " + MAX_POINTS + " 个中心点安全上限");
            }

            JsonObject encodedRoad = new JsonObject();
            if (fingerprintMethod != null) {
                Object fingerprint = fingerprintMethod.invoke(null, road);
                if (fingerprint instanceof Number number) {
                    encodedRoad.addProperty("fingerprint", Long.toUnsignedString(number.longValue()));
                }
            }
            encodedRoad.addProperty("width", numberMethod(road, "width", 1));
            encodedRoad.addProperty("road_type", numberMethod(road, "roadType", 0));
            encodedRoad.addProperty("owner_a_2d", longMethod(road, "ownerA2dKey", Long.MIN_VALUE));
            encodedRoad.addProperty("owner_b_2d", longMethod(road, "ownerB2dKey", Long.MIN_VALUE));
            JsonArray points = new JsonArray();
            for (int index = 0; index < segments.size(); index++) {
                Object segment = segments.get(index);
                if (segment == null) continue;
                Object rawPosition = segment.getClass().getMethod("middlePos").invoke(segment);
                if (!(rawPosition instanceof BlockPos position)) continue;
                int y = index < targetY.size() && targetY.get(index) instanceof Number value
                        ? value.intValue() : position.getY();
                JsonObject point = new JsonObject();
                point.addProperty("x", position.getX());
                point.addProperty("y", y);
                point.addProperty("z", position.getZ());
                points.add(point);
            }
            if (points.size() < 2) continue;
            encodedRoad.add("points", points);
            roadArray.add(encodedRoad);
            totalPoints += points.size();
        }

        roadCount = roadArray.size();
        pointCount = totalPoints;
        JsonObject snapshot = baseSnapshot(true, "ok");
        snapshot.add("roads", roadArray);
        return snapshot;
    }

    private static int numberMethod(Object target, String method, int fallback) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            return value instanceof Number number ? number.intValue() : fallback;
        } catch (ReflectiveOperationException ignored) {
            return fallback;
        }
    }

    private static long longMethod(Object target, String method, long fallback) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            return value instanceof Number number ? number.longValue() : fallback;
        } catch (ReflectiveOperationException ignored) {
            return fallback;
        }
    }

    private void writeUnavailable(String reason) {
        available = false;
        roadCount = 0;
        pointCount = 0;
        generatedAtMs = System.currentTimeMillis();
        try {
            JsonObject snapshot = baseSnapshot(false, reason);
            snapshot.add("roads", new JsonArray());
            atomicWrite(snapshot);
            if (!"roadweaver_adapter_failed".equals(reason)) lastError = "";
        } catch (java.io.IOException failure) {
            lastError = safeMessage(failure);
        }
    }

    private JsonObject baseSnapshot(boolean ready, String reason) {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("schema_version", SCHEMA_VERSION);
        snapshot.addProperty("source", ROADWEAVER_MOD_ID);
        snapshot.addProperty("source_version", sourceVersion);
        snapshot.addProperty("dimension", "minecraft:overworld");
        snapshot.addProperty("generated_at_ms", System.currentTimeMillis());
        snapshot.addProperty("available", ready);
        snapshot.addProperty("reason", reason);
        return snapshot;
    }

    private void atomicWrite(JsonObject snapshot) throws java.io.IOException {
        if (snapshotFile == null) return;
        Files.createDirectories(snapshotFile.getParent());
        Path temporary = snapshotFile.resolveSibling(snapshotFile.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(snapshot), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, snapshotFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, snapshotFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safeMessage(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        String message = cause.getMessage();
        if (message == null || message.isBlank()) message = cause.getClass().getSimpleName();
        return message.replaceAll("[\\r\\n\\t]+", " ").substring(0, Math.min(300, message.length()));
    }

    @Override
    public void close() {
        snapshotFile = null;
        nextRefreshMs = 0L;
    }
}
