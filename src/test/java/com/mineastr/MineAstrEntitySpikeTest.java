package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

final class MineAstrEntitySpikeTest {
    @Test
    void sandboxNormalizesCornersAndRequiresTheWholeBody() {
        var region = MineAstrSpikeRegion.parse("minecraft:overworld,10,5,10,0,0,0");
        assertTrue(region.contains("minecraft:overworld", new AABB(1, 1, 1, 2, 3, 2)));
        assertFalse(region.contains("minecraft:the_nether", new AABB(1, 1, 1, 2, 3, 2)));
        assertFalse(region.contains("minecraft:overworld", new AABB(-0.1, 1, 1, 0.5, 3, 2)));
        assertFalse(region.contains("minecraft:overworld", new AABB(1, 5, 1, 2, 6.1, 2)));
    }

    @Test
    void forbiddenIntersectionIncludesPartialBodyOverlap() {
        var region = MineAstrSpikeRegion.parse("minecraft:overworld,1,0,0,1,5,5");
        assertTrue(region.intersects("minecraft:overworld", new AABB(0.8, 1, 1, 1.4, 2.8, 1.6)));
        assertFalse(region.intersects("minecraft:overworld", new AABB(0.2, 1, 1, 0.8, 2.8, 1.6)));
    }

    @Test
    void invalidSandboxFailsClosed() {
        for (String value : List.of("", "minecraft:overworld,0,0", "invalid dimension,0,0,0,1,1,1",
                "minecraft:overworld,0,nan,0,1,1,1", "minecraft:overworld,0,0,0,30000001,1,1")) {
            assertThrows(IllegalArgumentException.class, () -> MineAstrSpikeRegion.parse(value));
        }
    }

    @Test
    void boundedGroundPathDetoursWithoutEnteringBlockedCells() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(3, 64, 0);
        var path = MineAstrSpikePath.find(start, target,
                pos -> Math.abs(pos.getX()) <= 5 && Math.abs(pos.getZ()) <= 5 && !pos.equals(new BlockPos(1, 64, 0)));
        assertEquals(target, path.getLast());
        BlockPos previous = start;
        for (BlockPos step : path) {
            assertEquals(1, previous.distManhattan(step));
            assertFalse(step.equals(new BlockPos(1, 64, 0)));
            previous = step;
        }
    }

    @Test
    void pathNeverExpandsBeyondItsFixedBudget() {
        int[] visited = {0};
        assertThrows(IllegalStateException.class, () -> MineAstrSpikePath.find(BlockPos.ZERO, new BlockPos(32, 0, 0), pos -> {
            visited[0]++;
            return true;
        }));
        assertTrue(visited[0] <= MineAstrSpikePath.MAX_VISITED * 4 + 2);
    }

    @Test
    void unsupportedHeightDistanceAndUnsafeEndpointsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> MineAstrSpikePath.find(BlockPos.ZERO, new BlockPos(0, 1, 0), pos -> true));
        assertThrows(IllegalArgumentException.class, () -> MineAstrSpikePath.find(BlockPos.ZERO, new BlockPos(33, 0, 0), pos -> true));
        assertThrows(IllegalArgumentException.class, () -> MineAstrSpikePath.find(BlockPos.ZERO, new BlockPos(1, 0, 0), pos -> false));
    }

    @Test
    void noMovementIsNeededAtTheVerifiedTarget() {
        assertEquals(List.of(), MineAstrSpikePath.find(BlockPos.ZERO, BlockPos.ZERO, pos -> true));
    }
}
