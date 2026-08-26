package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class MineAstrWorldMindTest {
    @Test
    void inventoryDeltaStoresOnlyAggregateItemChanges() {
        var delta = MineAstrWorldMind.inventoryDelta(
                Map.of("minecraft:bread", 2, "create:track", 4),
                Map.of("minecraft:bread", 1, "create:track", 7, "minecraft:stick", 2));

        assertEquals(3, delta.size());
        assertEquals("create:track", delta.get(0).getAsJsonObject().get("item_id").getAsString());
        assertEquals(3, delta.get(0).getAsJsonObject().get("delta").getAsInt());
        assertEquals("minecraft:bread", delta.get(1).getAsJsonObject().get("item_id").getAsString());
        assertEquals(-1, delta.get(1).getAsJsonObject().get("delta").getAsInt());
        assertEquals("minecraft:stick", delta.get(2).getAsJsonObject().get("item_id").getAsString());
        assertEquals(2, delta.get(2).getAsJsonObject().get("delta").getAsInt());
    }
}
