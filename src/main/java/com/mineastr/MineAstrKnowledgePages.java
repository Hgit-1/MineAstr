package com.mineastr;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;

/** Pure protocol pagination logic, kept independent from the Minecraft runtime. */
final class MineAstrKnowledgePages {
    static final int DEFAULT_PAGE_SIZE = 100;
    static final int MAX_PAGE_SIZE = 200;
    static final int MAX_PAGE_JSON_CHARS = 1_000_000;
    private static final List<String> CATEGORIES = List.of(
            "mods", "items", "blocks", "entities", "fluids", "recipes");

    private MineAstrKnowledgePages() {
    }

    static JsonObject pageFrom(
            String currentSnapshotId,
            Map<String, JsonArray> categories,
            String requestedSnapshotId,
            String category,
            int cursor,
            int requestedPageSize) {
        if (!currentSnapshotId.equals(requestedSnapshotId)) {
            throw new IllegalArgumentException("知识快照已变更，请重新获取 manifest。");
        }
        if (!CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("不支持的知识分类：" + category);
        }
        if (cursor < 0) {
            throw new IllegalArgumentException("分页游标不能为负数。");
        }
        int pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, requestedPageSize));
        JsonArray source = categories.get(category);
        int requestedEnd = Math.min(source.size(), cursor + pageSize);
        int end = cursor;
        int estimatedChars = 256;
        JsonArray entries = new JsonArray();
        for (int index = cursor; index < requestedEnd; index++) {
            int entryChars = source.get(index).toString().length();
            if (!entries.isEmpty() && estimatedChars + entryChars > MAX_PAGE_JSON_CHARS) {
                break;
            }
            entries.add(source.get(index));
            estimatedChars += entryChars;
            end = index + 1;
        }

        JsonObject data = new JsonObject();
        data.addProperty("snapshot_id", currentSnapshotId);
        data.addProperty("category", category);
        data.addProperty("cursor", cursor);
        data.addProperty("next_cursor", end < source.size() ? end : -1);
        data.addProperty("total", source.size());
        data.add("entries", entries);
        return data;
    }
}
