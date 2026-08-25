package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class MineAstrAgentNavigationTest {
    @Test
    void enclosedGeometryScoresHighlyWithoutUsingMaterialNames() {
        assertEquals(85, MineAstrAgentManager.enclosureConfidence(4, 4, false));
        assertEquals(100, MineAstrAgentManager.enclosureConfidence(4, 4, true));
        assertEquals(70, MineAstrAgentManager.enclosureConfidence(3, 7, false));
    }

    @Test
    void openTerrainAndIncompleteCavesRemainLowConfidence() {
        assertEquals(20, MineAstrAgentManager.enclosureConfidence(2, 4, false));
        assertEquals(55, MineAstrAgentManager.enclosureConfidence(2, 4, true));
        assertEquals(20, MineAstrAgentManager.enclosureConfidence(4, 0, false));
    }

    @Test
    void criticalCollisionAndDoorsBeatCoordinateIterationOrder() {
        double ordinaryNear = MineAstrAgentManager.navigationCandidatePriority(1, false, false, false);
        double collidingFar = MineAstrAgentManager.navigationCandidatePriority(100, true, false, false);
        double doorFar = MineAstrAgentManager.navigationCandidatePriority(100, false, true, false);
        assertTrue(collidingFar < ordinaryNear);
        assertTrue(doorFar < ordinaryNear);
    }

    @Test
    void embeddedFloorRecoveryPrefersLiftingOntoTheSameIndoorPlatform() {
        BlockPos origin = new BlockPos(843, 73, -221);
        BlockPos target = new BlockPos(841, 74, -221);
        BlockPos selected = MineAstrAgentManager.nearestSafeUnembedPosition(
                origin, target, candidate -> candidate.equals(origin.above())
                        || candidate.equals(origin.offset(1, -1, 0)));

        assertEquals(origin.above(), selected);
        assertTrue(MineAstrAgentManager.unembedCandidateScore(origin, target, origin.above())
                < MineAstrAgentManager.unembedCandidateScore(origin, target, origin.offset(1, -1, 0)));
    }

    @Test
    void equalHeightRecoveryFavorsTheNavigationTargetDirection() {
        BlockPos origin = new BlockPos(10, 64, 10);
        BlockPos target = new BlockPos(20, 64, 10);
        BlockPos selected = MineAstrAgentManager.nearestSafeUnembedPosition(
                origin, target, candidate -> candidate.equals(origin.east()) || candidate.equals(origin.west()));

        assertEquals(origin.east(), selected);
    }

    @Test
    void directCollisionEscapeIsOnlyAllowedAsABoundedVerticalLift() {
        BlockPos origin = new BlockPos(5, 70, 5);
        assertTrue(MineAstrAgentManager.isDirectVerticalUnembed(origin, origin.above(), true));
        assertTrue(MineAstrAgentManager.isDirectVerticalUnembed(origin, origin.above(2), true));
        assertFalse(MineAstrAgentManager.isDirectVerticalUnembed(origin, origin.above(3), true));
        assertFalse(MineAstrAgentManager.isDirectVerticalUnembed(origin, origin.east().above(), true));
        assertFalse(MineAstrAgentManager.isDirectVerticalUnembed(origin, origin.above(), false));
    }
}
