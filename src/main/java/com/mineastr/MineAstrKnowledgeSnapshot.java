package com.mineastr;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
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

    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private final AtomicLong scanGeneration = new AtomicLong();
    private final ExecutorService scanner = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MineAstr-Knowledge-Scanner");
        thread.setDaemon(true);
        return thread;
    });

    public void refresh(MinecraftServer server) {
        if (!MineAstrConfig.ENABLE_KNOWLEDGE_SCAN.getAsBoolean()) {
            return;
        }
        long generation = scanGeneration.incrementAndGet();
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
                MineAstr.LOGGER.error("MineAstr 服务器知识快照扫描失败，已保留上一版：{}", exc.getMessage(), exc);
            }
        }, scanner);
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
    }

    private static Snapshot scan(MinecraftServer server) {
        Map<String, JsonArray> categories = new LinkedHashMap<>();
        categories.put("mods", scanMods());
        categories.put("items", scanRegistry(BuiltInRegistries.ITEM, MineAstrKnowledgeSnapshot::itemData));
        categories.put("blocks", scanRegistry(BuiltInRegistries.BLOCK, MineAstrKnowledgeSnapshot::blockData));
        categories.put("entities", scanRegistry(BuiltInRegistries.ENTITY_TYPE, MineAstrKnowledgeSnapshot::entityData));
        categories.put("fluids", scanRegistry(BuiltInRegistries.FLUID, MineAstrKnowledgeSnapshot::fluidData));
        categories.put("recipes", scanRecipes(server));

        JsonObject canonical = new JsonObject();
        categories.forEach(canonical::add);
        String snapshotId = sha256(GSON.toJson(canonical));
        return new Snapshot(snapshotId, System.currentTimeMillis(), categories);
    }

    private static JsonArray scanMods() {
        List<JsonObject> entries = new ArrayList<>();
        for (IModInfo mod : ModList.get().getMods()) {
            JsonObject data = new JsonObject();
            data.addProperty("id", mod.getModId());
            data.addProperty("name", mod.getDisplayName());
            data.addProperty("version", mod.getVersion().toString());
            data.addProperty("description", mod.getDescription());

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

    private static <T> JsonArray scanRegistry(Registry<T> registry, EntryEncoder<T> encoder) {
        List<JsonObject> entries = new ArrayList<>();
        for (T value : registry) {
            ResourceLocation id = registry.getKey(value);
            if (id == null) {
                continue;
            }
            JsonObject data = encoder.encode(id, value);
            data.add("tags", tags(registry.wrapAsHolder(value)));
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

    private static JsonArray scanRecipes(MinecraftServer server) {
        List<JsonObject> entries = new ArrayList<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            JsonObject data = new JsonObject();
            data.addProperty("id", holder.id().toString());
            data.addProperty("namespace", holder.id().getNamespace());
            data.addProperty("type", registryId(BuiltInRegistries.RECIPE_TYPE, recipe.getType()));
            data.addProperty("serializer", registryId(BuiltInRegistries.RECIPE_SERIALIZER, recipe.getSerializer()));

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
