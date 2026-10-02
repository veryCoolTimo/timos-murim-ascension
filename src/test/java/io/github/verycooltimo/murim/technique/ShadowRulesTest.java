package io.github.verycooltimo.murim.technique;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowRulesTest {

    @Test
    void growsWithMastery() {
        for (int layer = 2; layer <= 7; layer++) {
            assertTrue(ShadowRules.duration(layer) >= ShadowRules.duration(layer - 1));
            assertTrue(ShadowRules.observers(layer) >= ShadowRules.observers(layer - 1));
            assertTrue(ShadowRules.detection(layer, false) <= ShadowRules.detection(layer - 1, false));
        }
        assertEquals(0, ShadowRules.duration(0));
        assertEquals(8, ShadowRules.observers(7));
    }

    @Test
    void radiusNeverBelowContactAndLightIsHarsher() {
        assertEquals(ShadowRules.MIN_RADIUS, ShadowRules.radius(1.0D, 7, false));
        assertTrue(ShadowRules.radius(35.0D, 3, true) > ShadowRules.radius(35.0D, 3, false));
        assertTrue(ShadowRules.radius(35.0D, 7, true) <= 35.0D);
    }

    @Test
    void deathDistanceBySpec() {
        assertEquals(5.0D, ShadowRules.deathDistance(3));
        assertEquals(6.0D, ShadowRules.deathDistance(5));
        assertEquals(7.0D, ShadowRules.deathDistance(7));
    }
}
