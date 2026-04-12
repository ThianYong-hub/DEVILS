package com.example.addon.modules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NukerPlusTest {
    @Test
    void cubeAnchorStaysOnSameLayerForSoulSandHeight() {
        assertEquals(65, NukerPlus.resolveCubeBaseY(65.0));
        assertEquals(65, NukerPlus.resolveCubeBaseY(64.875));
    }

    @Test
    void cubeAnchorDoesNotJumpUpWhileFlyingLowInBlock() {
        assertEquals(64, NukerPlus.resolveCubeBaseY(64.3));
        assertEquals(65, NukerPlus.resolveCubeBaseY(64.999));
    }
}
