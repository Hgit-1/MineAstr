package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class MineAstrAgentRecoveryTest {
    @Test
    void choosesOnlyAnApprovedNeighborInsideTheStrictRecoveryEnvelope() {
        BlockPos origin = new BlockPos(205, 127, -185);
        BlockPos approved = origin.offset(1, 0, 0);
        BlockPos selected = MineAstrAgentManager.nearestSafeUnembedPosition(
                origin, candidate -> candidate.equals(approved));
        assertEquals(approved, selected);
    }

    @Test
    void neverConsidersAPositionOutsideTwoHorizontalAndTwoVerticalBlocks() {
        BlockPos origin = new BlockPos(205, 127, -185);
        List<BlockPos> inspected = new ArrayList<>();
        BlockPos selected = MineAstrAgentManager.nearestSafeUnembedPosition(origin, candidate -> {
            inspected.add(candidate);
            return false;
        });
        assertNull(selected);
        assertTrue(inspected.stream().allMatch(candidate ->
                Math.abs(candidate.getX() - origin.getX()) <= 2
                        && Math.abs(candidate.getZ() - origin.getZ()) <= 2
                        && candidate.getY() - origin.getY() >= -1
                        && candidate.getY() - origin.getY() <= 2));
        assertTrue(inspected.stream().noneMatch(origin::equals));
    }
}
