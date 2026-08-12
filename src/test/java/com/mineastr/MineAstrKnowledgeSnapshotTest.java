package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class MineAstrKnowledgeSnapshotTest {
    @Test
    void paginatesWithoutCrossingSnapshotBoundary() {
        Map<String, JsonArray> categories = emptyCategories();
        JsonArray items = categories.get("items");
        for (int index = 0; index < 5; index++) {
            JsonObject item = new JsonObject();
            item.addProperty("id", "example:item_" + index);
            items.add(item);
        }

        JsonObject page = MineAstrKnowledgePages.pageFrom("snapshot-a", categories, "snapshot-a", "items", 2, 2);

        assertEquals(2, page.get("entries").getAsJsonArray().size());
        assertEquals(4, page.get("next_cursor").getAsInt());
        assertEquals(5, page.get("total").getAsInt());
        assertEquals("example:item_2", page.get("entries").getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    void rejectsStaleSnapshotsAndInvalidCategories() {
        Map<String, JsonArray> categories = emptyCategories();

        assertThrows(
                IllegalArgumentException.class,
                () -> MineAstrKnowledgePages.pageFrom("new", categories, "old", "items", 0, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> MineAstrKnowledgePages.pageFrom("new", categories, "new", "unknown", 0, 10));
    }

    @Test
    void clampsPageSizeToProtocolMaximum() {
        Map<String, JsonArray> categories = emptyCategories();
        for (int index = 0; index < 250; index++) {
            categories.get("items").add(new JsonObject());
        }

        JsonObject page = MineAstrKnowledgePages.pageFrom("a", categories, "a", "items", 0, 10_000);

        assertEquals(MineAstrKnowledgePages.MAX_PAGE_SIZE, page.get("entries").getAsJsonArray().size());
        assertEquals(MineAstrKnowledgePages.MAX_PAGE_SIZE, page.get("next_cursor").getAsInt());
    }

    @Test
    void limitsSerializedPageSizeButAlwaysMakesProgress() {
        Map<String, JsonArray> categories = emptyCategories();
        JsonObject large = new JsonObject();
        large.addProperty("description", "x".repeat(600_000));
        categories.get("mods").add(large);
        categories.get("mods").add(large.deepCopy());

        JsonObject page = MineAstrKnowledgePages.pageFrom("a", categories, "a", "mods", 0, 10);

        assertEquals(1, page.get("entries").getAsJsonArray().size());
        assertEquals(1, page.get("next_cursor").getAsInt());
    }

    private static Map<String, JsonArray> emptyCategories() {
        Map<String, JsonArray> categories = new LinkedHashMap<>();
        categories.put("mods", new JsonArray());
        categories.put("items", new JsonArray());
        categories.put("blocks", new JsonArray());
        categories.put("entities", new JsonArray());
        categories.put("fluids", new JsonArray());
        categories.put("recipes", new JsonArray());
        return categories;
    }
}
