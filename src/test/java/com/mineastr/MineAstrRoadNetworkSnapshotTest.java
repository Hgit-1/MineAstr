package com.mineastr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;

final class MineAstrRoadNetworkSnapshotTest {
    @Test
    void identifiesPermanentRoadWeaverApiCompatibilityFailures() {
        assertTrue(MineAstrRoadNetworkSnapshot.isApiCompatibilityFailure(
                new NoSuchMethodException("loadAll")));
        assertTrue(MineAstrRoadNetworkSnapshot.isApiCompatibilityFailure(
                new InvocationTargetException(new NoClassDefFoundError("legacy/RoadData"))));
        assertFalse(MineAstrRoadNetworkSnapshot.isApiCompatibilityFailure(
                new InvocationTargetException(new IllegalStateException("world temporarily unavailable"))));
    }
}
