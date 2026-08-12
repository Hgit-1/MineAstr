package com.mineastr;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

/** Builds and serves an immutable, content-addressed view of server Mod content. */
public final class MineAstrKnowledgeSnapshot {
    public static final int DEFAULT_PAGE_SIZE = MineAstrKnowledgePages.DEFAULT_PAGE_SIZE;
    public static final int MAX_PAGE_SIZE = MineAstrKnowledgePages.MAX_PAGE_SIZE;
    private static final Gson GSON = new Gson();
    private static final int MAX_LANGUAGE_FILE_BYTES = 512 * 1024;
    private static final int MAX_LANGUAGE_TOTAL_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CODEC_JSON_BYTES = 512 * 1024;
    private static final int MAX_CODEC_DEPTH = 12;
    private static final int MAX_CODEC_MEMBERS = 4096;

    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private final AtomicLong scanGeneration = new AtomicLong();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final ExecutorService scanner = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MineAstr-Knowledge-Scanner");
        thread.setDaemon(true);
        return thread;
    });

    private volatile String taskId = "";
    private volatile long scanStartedAtMs;
    private volatile long scanFinishedAtMs;
    private volatile String lastError = "";

    public String refresh(MinecraftServer server) {
        if (!MineAstrConfig.ENABLE_KNOWLEDGE_SCAN.getAsBoolean()) {
            return "disabled";
        }
        if (!scanning.compareAndSet(false, true)) {
            return taskId;
        }
        long generation = scanGeneration.incrementAndGet();
        taskId = "local-" + generation + "-" + Long.toUnsignedString(System.currentTimeMillis(), 36);
        scanStartedAtMs = System.currentTimeMillis();
        scanFinishedAtMs = 0;
        lastError = "";
        CompletableFuture.runAsync(() -> {
            try {
                Snapshot snapshot = scan(server);
                if (scanGeneration.get() != generation) {
                    return;
                }
                Snapshot previous = current.getAndSet(snapshot);
                if (previous == null || !previous.snapshotId.equals(snapshot.snapshotId)) {
                    MineAstr.LOGGER.info(
                            "MineAstr 服务器知识快照已更新：{} ({} 条记录)",
                            snapshot.snapshotId,
                            snapshot.totalEntries());
                }
            } catch (RuntimeException exc) {
                lastError = safeError(exc);
                MineAstr.LOGGER.error("MineAstr 服务器知识快照扫描失败，已保留上一版：{}", exc.getMessage(), exc);
            } finally {
                scanFinishedAtMs = System.currentTimeMillis();
                if (scanGeneration.get() == generation) scanning.set(false);
            }
        }, scanner);
        return taskId;
    }

    public JsonObject manifest() {
        Snapshot snapshot = current.get();
        if (snapshot == null) {
            JsonObject data = new JsonObject();
            data.addProperty("ready", false);
            data.addProperty("status", "scanning");
            data.addProperty("page_size", DEFAULT_PAGE_SIZE);
            return data;
        }
        return snapshot.manifest();
    }

    public JsonObject status() {
        Snapshot snapshot = current.get();
        JsonObject data = new JsonObject();
        data.addProperty("enabled", MineAstrConfig.ENABLE_KNOWLEDGE_SCAN.getAsBoolean());
        data.addProperty("state", scanning.get() ? "scanning" : lastError.isEmpty() ? (snapshot == null ? "idle" : "ready") : "error");
        data.addProperty("task_id", taskId);
        data.addProperty("started_at_ms", scanStartedAtMs);
        data.addProperty("finished_at_ms", scanFinishedAtMs);
        data.addProperty("last_error", lastError);
        if (snapshot != null) {
            data.addProperty("snapshot_id", snapshot.snapshotId);
            data.addProperty("generated_at_ms", snapshot.generatedAtMs);
            data.addProperty("total_entries", snapshot.totalEntries());
            JsonObject counts = new JsonObject();
            snapshot.categories.forEach((category, entries) -> counts.addProperty(category, entries.size()));
            data.add("counts", counts);
        }
        return data;
    }

    public boolean isScanning() {
        return scanning.get();
    }

    public JsonObject page(String snapshotId, String category, int cursor, int requestedPageSize) {
        Snapshot snapshot = current.get();
        if (snapshot == null) {
            throw new IllegalStateException("服务器知识快照尚未准备完成。");
        }
        return MineAstrKnowledgePages.pageFrom(
                snapshot.snapshotId, snapshot.categories, snapshotId, category, cursor, requestedPageSize);
    }

    public void close() {
        // The bridge instance can be reused by another integrated-server world in the same JVM.
        // Keep the daemon executor alive and only discard world-specific data.
        scanGeneration.incrementAndGet();
        current.set(null);
        scanning.set(false);
    }

    private static Snapshot scan(MinecraftServer server) {
        long observedAtMs = System.currentTimeMillis();
        Map<String, List<String>> languageAliases = scanLanguageAliases();
        Map<String, JsonArray> categories = new LinkedHashMap<>();
        categories.put("mods", scanMods(observedAtMs));
        categories.put("items", scanRegistry(BuiltInRegistries.ITEM, MineAstrKnowledgeSnapshot::itemData, languageAliases, observedAtMs));
        categories.put("blocks", scanRegistry(BuiltInRegistries.BLOCK, MineAstrKnowledgeSnapshot::blockData, languageAliases, observedAtMs));
        categories.put("entities", scanRegistry(BuiltInRegistries.ENTITY_TYPE, MineAstrKnowledgeSnapshot::entityData, languageAliases, observedAtMs));
        categories.put("fluids", scanRegistry(BuiltInRegistries.FLUID, MineAstrKnowledgeSnapshot::fluidData, languageAliases, observedAtMs));
        categories.put("recipes", scanRecipes(server, observedAtMs));

        JsonObject canonical = new JsonObject();
        categories.forEach((category, entries) -> canonical.add(category, stableJson(entries)));
        String snapshotId = sha256(GSON.toJson(canonical));
        return new Snapshot(snapshotId, System.currentTimeMillis(), categories);
    }

    private static JsonArray scanMods(long observedAtMs) {
        List<JsonObject> entries = new ArrayList<>();
        for (IModInfo mod : ModList.get().getMods()) {
            JsonObject data = new JsonObject();
            data.addProperty("id", mod.getModId());
            data.addProperty("name", mod.getDisplayName());
            data.addProperty("version", mod.getVersion().toString());
            data.addProperty("description", mod.getDescription());
            JsonArray aliases = new JsonArray();
            addAlias(aliases, mod.getDisplayName());
            addAlias(aliases, mod.getModId());
            data.add("aliases", aliases);
            addSourceMetadata(data, observedAtMs);

            JsonArray dependencies = new JsonArray();
            for (IModInfo.ModVersion dependency : mod.getDependencies()) {
                JsonObject item = new JsonObject();
                item.addProperty("mod_id", dependency.getModId());
                item.addProperty("type", dependency.getType().name().toLowerCase());
                item.addProperty("version_range", dependency.getVersionRange().toString());
                dependencies.add(item);
            }
            data.add("dependencies", dependencies);

            try {
                Path file = mod.getOwningFile().getFile().getFilePath();
                if (Files.isRegularFile(file)) {
                    data.addProperty("jar_sha512", digestFile(file, "SHA-512"));
                    data.addProperty("jar_file", file.getFileName().toString());
                }
            } catch (RuntimeException | IOException exc) {
                MineAstr.LOGGER.debug("MineAstr 无法计算 Mod {} 文件哈希：{}", mod.getModId(), exc.getMessage());
            }
            entries.add(data);
        }
        entries.sort(Comparator.comparing(entry -> entry.get("id").getAsString()));
        return toArray(entries);
    }

    private static <T> JsonArray scanRegistry(
            Registry<T> registry, EntryEncoder<T> encoder, Map<String, List<String>> languageAliases, long observedAtMs) {
        List<JsonObject> entries = new ArrayList<>();
        for (T value : registry) {
            ResourceLocation id = registry.getKey(value);
            if (id == null) {
                continue;
            }
            JsonObject data = encoder.encode(id, value);
            data.add("tags", tags(registry.wrapAsHolder(value)));
            JsonArray aliases = new JsonArray();
            addAlias(aliases, id.getPath().replace('_', ' '));
            addAlias(aliases, data.has("name") ? data.get("name").getAsString() : "");
            if (data.has("translation_key")) {
                for (String alias : languageAliases.getOrDefault(data.get("translation_key").getAsString(), List.of())) {
                    addAlias(aliases, alias);
                }
            }
            data.add("aliases", aliases);
            addSourceMetadata(data, observedAtMs);
            entries.add(data);
        }
        entries.sort(Comparator.comparing(entry -> entry.get("id").getAsString()));
        return toArray(entries);
    }

    private static JsonObject itemData(ResourceLocation id, Item item) {
        JsonObject data = baseEntry(id);
        data.addProperty("translation_key", item.getDescriptionId());
        data.addProperty("name", new ItemStack(item).getHoverName().getString());
        data.addProperty("max_stack_size", item.getDefaultMaxStackSize());
        return data;
    }

    private static JsonObject blockData(ResourceLocation id, Block block) {
        JsonObject data = baseEntry(id);
        data.addProperty("translation_key", block.getDescriptionId());
        data.addProperty("name", block.getName().getString());
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(block.asItem());
        if (itemId != null && block.asItem() != net.minecraft.world.item.Items.AIR) {
            data.addProperty("item_id", itemId.toString());
        }
        return data;
    }

    private static JsonObject entityData(ResourceLocation id, EntityType<?> entityType) {
        JsonObject data = baseEntry(id);
        data.addProperty("translation_key", entityType.getDescriptionId());
        data.addProperty("name", entityType.getDescription().getString());
        data.addProperty("category", entityType.getCategory().getName());
        return data;
    }

    private static JsonObject fluidData(ResourceLocation id, Fluid fluid) {
        JsonObject data = baseEntry(id);
        data.addProperty("translation_key", fluid.getFluidType().getDescriptionId());
        data.addProperty("name", fluid.getFluidType().getDescription().getString());
        return data;
    }

    private static JsonArray scanRecipes(MinecraftServer server, long observedAtMs) {
        List<JsonObject> entries = new ArrayList<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            JsonObject data = new JsonObject();
            data.addProperty("id", holder.id().toString());
            data.addProperty("namespace", holder.id().getNamespace());
            data.addProperty("type", registryId(BuiltInRegistries.RECIPE_TYPE, recipe.getType()));
            data.addProperty("serializer", registryId(BuiltInRegistries.RECIPE_SERIALIZER, recipe.getSerializer()));
            addSourceMetadata(data, observedAtMs);

            JsonArray ingredients = new JsonArray();
            boolean opaque = false;
            for (Ingredient ingredient : recipe.getIngredients()) {
                JsonArray alternatives = new JsonArray();
                for (ItemStack stack : ingredient.getItems()) {
                    JsonObject option = stackData(stack);
                    alternatives.add(option);
                }
                if (alternatives.isEmpty() && !ingredient.isEmpty()) {
                    opaque = true;
                }
                JsonObject input = new JsonObject();
                input.add("alternatives", alternatives);
                ingredients.add(input);
            }
            data.add("ingredients", ingredients);

            ItemStack result;
            try {
                result = recipe.getResultItem(server.registryAccess());
            } catch (RuntimeException exc) {
                result = ItemStack.EMPTY;
                opaque = true;
            }
            if (!result.isEmpty()) {
                data.add("result", stackData(result));
            } else {
                opaque = true;
            }
            data.addProperty("opaque", opaque);
            data.addProperty("special", recipe.isSpecial());
            if (recipe instanceof CraftingRecipe craftingRecipe) {
                data.addProperty("crafting", true);
                data.addProperty("notification", craftingRecipe.showNotification());
                if (recipe instanceof ShapedRecipe shapedRecipe) {
                    data.addProperty("crafting_style", "shaped");
                    data.addProperty("width", shapedRecipe.getWidth());
                    data.addProperty("height", shapedRecipe.getHeight());
                } else if (recipe instanceof ShapelessRecipe) {
                    data.addProperty("crafting_style", "shapeless");
                } else if (recipe.isSpecial()) {
                    data.addProperty("crafting_style", "special");
                } else {
                    data.addProperty("crafting_style", "custom");
                }
            }
            if (recipe instanceof AbstractCookingRecipe cookingRecipe) {
                data.addProperty("cooking_time", cookingRecipe.getCookingTime());
                data.addProperty("experience", cookingRecipe.getExperience());
            }
            JsonElement serializerData = encodeRecipeCodec(server, recipe);
            if (serializerData != null) {
                data.add("serializer_data", serializerData);
                data.addProperty("serializer_data_status", "decoded");
            } else {
                data.addProperty("serializer_data_status", "opaque");
            }
            entries.add(data);
        }
        entries.sort(Comparator.comparing(entry -> entry.get("id").getAsString()));
        return toArray(entries);
    }

    private static JsonObject stackData(ItemStack stack) {
        JsonObject data = new JsonObject();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        data.addProperty("id", id == null ? "unknown" : id.toString());
        data.addProperty("name", stack.getHoverName().getString());
        data.addProperty("count", stack.getCount());
        return data;
    }

    private static JsonObject baseEntry(ResourceLocation id) {
        JsonObject data = new JsonObject();
        data.addProperty("id", id.toString());
        data.addProperty("namespace", id.getNamespace());
        return data;
    }

    private static void addSourceMetadata(JsonObject data, long observedAtMs) {
        data.addProperty("source_trust", "authoritative");
        data.addProperty("confirmation_status", "observed");
        data.addProperty("updated_at_ms", observedAtMs);
        JsonArray sources = new JsonArray();
        JsonObject source = new JsonObject();
        source.addProperty("source_id", "minecraft_runtime");
        source.addProperty("source_type", "runtime");
        source.addProperty("trust", "authoritative");
        source.addProperty("status", "observed");
        source.addProperty("observed_at_ms", observedAtMs);
        sources.add(source);
        data.add("sources", sources);
    }

    private static void addAlias(JsonArray aliases, String value) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty() || text.length() > 256) return;
        for (JsonElement existing : aliases) if (text.equalsIgnoreCase(existing.getAsString())) return;
        aliases.add(text);
    }

    private static Map<String, List<String>> scanLanguageAliases() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        int totalBytes = 0;
        for (IModInfo mod : ModList.get().getMods()) {
            Path file;
            try {
                file = mod.getOwningFile().getFile().getFilePath();
            } catch (RuntimeException exc) {
                continue;
            }
            if (!Files.isRegularFile(file)) continue;
            try (ZipFile zip = new ZipFile(file.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements() && totalBytes < MAX_LANGUAGE_TOTAL_BYTES) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName().replace('\\', '/').toLowerCase();
                    if (entry.isDirectory() || entry.getSize() > MAX_LANGUAGE_FILE_BYTES
                            || !name.matches("assets/[a-z0-9_.-]+/lang/(zh_cn|en_us)\\.json")) continue;
                    int remaining = Math.min(MAX_LANGUAGE_FILE_BYTES, MAX_LANGUAGE_TOTAL_BYTES - totalBytes);
                    byte[] bytes;
                    try (InputStream input = zip.getInputStream(entry)) {
                        bytes = input.readNBytes(remaining + 1);
                    }
                    if (bytes.length > remaining) continue;
                    totalBytes += bytes.length;
                    mergeLanguageJson(aliases, bytes);
                }
            } catch (IOException | RuntimeException exc) {
                MineAstr.LOGGER.debug("MineAstr 已跳过 Mod {} 的语言资源：{}", mod.getModId(), exc.getMessage());
            }
        }
        return aliases;
    }

    static void mergeLanguageJson(Map<String, List<String>> aliases, byte[] bytes) {
        JsonElement parsed = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> item : parsed.getAsJsonObject().entrySet()) {
            if (!item.getValue().isJsonPrimitive() || !item.getValue().getAsJsonPrimitive().isString()) continue;
            String value = item.getValue().getAsString().strip();
            if (value.isEmpty() || value.length() > 256) continue;
            List<String> values = aliases.computeIfAbsent(item.getKey(), ignored -> new ArrayList<>());
            if (values.stream().noneMatch(value::equalsIgnoreCase)) values.add(value);
        }
    }

    /** Uses the serializer codec without linking to any recipe Mod API, then applies strict structural limits. */
    private static JsonElement encodeRecipeCodec(MinecraftServer server, Recipe<?> recipe) {
        try {
            return encodeRecipeCodecTyped(server, recipe);
        } catch (RuntimeException exc) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Recipe<?>> JsonElement encodeRecipeCodecTyped(MinecraftServer server, Recipe<?> value) {
        T recipe = (T) value;
        RecipeSerializer<T> serializer = (RecipeSerializer<T>) recipe.getSerializer();
        return serializer.codec().codec()
                .encodeStart(server.registryAccess().createSerializationContext(JsonOps.INSTANCE), recipe)
                .result()
                .map(json -> boundedJson(json, 0, new int[] {0}))
                .orElse(null);
    }

    static JsonElement boundedJson(JsonElement value, int depth, int[] members) {
        if (value == null || depth > MAX_CODEC_DEPTH || members[0]++ > MAX_CODEC_MEMBERS) return null;
        if (value.isJsonPrimitive()) {
            String encoded = value.toString();
            return encoded.length() <= 8192 ? value.deepCopy() : null;
        }
        if (value.isJsonNull()) return value.deepCopy();
        if (value.isJsonArray()) {
            JsonArray output = new JsonArray();
            for (JsonElement item : value.getAsJsonArray()) {
                JsonElement bounded = boundedJson(item, depth + 1, members);
                if (bounded == null) break;
                output.add(bounded);
            }
            return GSON.toJson(output).length() <= MAX_CODEC_JSON_BYTES ? output : null;
        }
        JsonObject output = new JsonObject();
        for (Map.Entry<String, JsonElement> item : value.getAsJsonObject().entrySet()) {
            if (item.getKey().length() > 256) continue;
            JsonElement bounded = boundedJson(item.getValue(), depth + 1, members);
            if (bounded == null) continue;
            output.add(item.getKey(), bounded);
        }
        return GSON.toJson(output).length() <= MAX_CODEC_JSON_BYTES ? output : null;
    }

    static JsonElement stableJson(JsonElement value) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) return value == null ? null : value.deepCopy();
        if (value.isJsonArray()) {
            JsonArray output = new JsonArray();
            for (JsonElement item : value.getAsJsonArray()) output.add(stableJson(item));
            return output;
        }
        JsonObject output = new JsonObject();
        for (Map.Entry<String, JsonElement> item : value.getAsJsonObject().entrySet()) {
            if (item.getKey().equals("updated_at_ms") || item.getKey().equals("observed_at_ms")) continue;
            output.add(item.getKey(), stableJson(item.getValue()));
        }
        return output;
    }

    private static String safeError(Throwable exc) {
        String message = exc.getMessage();
        if (message == null || message.isBlank()) message = exc.getClass().getSimpleName();
        String safe = message.replaceAll("[\\r\\n\\t]", " ").replaceAll("(?i)(token|password|secret)\\s*[:=]\\s*\\S+", "$1=[redacted]");
        return safe.substring(0, Math.min(300, safe.length()));
    }

    private static JsonArray tags(Holder<?> holder) {
        JsonArray tags = new JsonArray();
        holder.tags()
                .map(tag -> tag.location().toString())
                .sorted()
                .forEach(tags::add);
        return tags;
    }

    private static <T> String registryId(Registry<T> registry, T value) {
        ResourceLocation id = registry.getKey(value);
        return id == null ? "unknown" : id.toString();
    }

    private static JsonArray toArray(List<JsonObject> entries) {
        JsonArray array = new JsonArray();
        entries.forEach(array::add);
        return array;
    }

    private static String digestFile(Path path, String algorithm) throws IOException {
        MessageDigest digest = messageDigest(algorithm);
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(String text) {
        MessageDigest digest = messageDigest("SHA-256");
        return HexFormat.of().formatHex(digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static MessageDigest messageDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("JVM 不支持哈希算法：" + algorithm, exc);
        }
    }

    @FunctionalInterface
    private interface EntryEncoder<T> {
        JsonObject encode(ResourceLocation id, T value);
    }

    private record Snapshot(String snapshotId, long generatedAtMs, Map<String, JsonArray> categories) {
        JsonObject manifest() {
            JsonObject data = new JsonObject();
            data.addProperty("ready", true);
            data.addProperty("snapshot_id", snapshotId);
            data.addProperty("generated_at_ms", generatedAtMs);
            data.addProperty("page_size", DEFAULT_PAGE_SIZE);
            JsonObject counts = new JsonObject();
            categories.forEach((category, entries) -> counts.addProperty(category, entries.size()));
            data.add("counts", counts);
            return data;
        }

        int totalEntries() {
            return categories.values().stream().mapToInt(JsonArray::size).sum();
        }
    }
}
