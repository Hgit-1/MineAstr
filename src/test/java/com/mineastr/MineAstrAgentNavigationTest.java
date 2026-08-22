package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
