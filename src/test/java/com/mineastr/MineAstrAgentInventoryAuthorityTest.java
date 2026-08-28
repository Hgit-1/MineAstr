package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MineAstrAgentInventoryAuthorityTest {
    @Test
    void onlyMatureCropAgesAreEligibleForAutomaticHarvest() {
        assertFalse(MineAstrAgentInventoryAuthority.isMatureCropAge(true, 0, 7));
        assertFalse(MineAstrAgentInventoryAuthority.isMatureCropAge(true, 6, 7));
        assertTrue(MineAstrAgentInventoryAuthority.isMatureCropAge(true, 7, 7));
        assertFalse(MineAstrAgentInventoryAuthority.isMatureCropAge(false, 7, 7));
    }
}
