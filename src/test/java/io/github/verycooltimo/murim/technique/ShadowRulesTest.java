package io.github.verycooltimo.murim.technique;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowRulesTest {

    @Test
    void growsWithMastery() {
        for (int executionTier = 2; executionTier <= 12; executionTier++) {
            assertTrue(ShadowRules.duration(executionTier) >= ShadowRules.duration(executionTier - 1));
            assertTrue(ShadowRules.observers(executionTier) >= ShadowRules.observers(executionTier - 1));
            assertTrue(ShadowRules.speedBonus(executionTier) >= ShadowRules.speedBonus(executionTier - 1));
        }
        assertEquals(0, ShadowRules.duration(0));
        assertEquals(12, ShadowRules.observers(8));
    }

    @Test
    void speedAndObserverLimitsFollowCanon() {
        assertEquals(.6D, ShadowRules.speedBonus(1), 1e-9);
        assertEquals(1.2D, ShadowRules.speedBonus(10), 1e-9);
        int[] stars = {1, 3, 5, 8, 10, 11, 12}, observers = {1, 3, 6, 12, 30, 40, 50};
        for (int i = 0; i < stars.length; i++) assertEquals(observers[i], ShadowRules.observers(stars[i]));
    }

    @Test
    void deathDistanceBySpec() {
        assertEquals(1.4D, ShadowRules.speedBonus(12), 1e-9);
        assertEquals(9.0D, ShadowRules.deathDistance(12));
        assertEquals(9.0D, ShadowRules.deathQi(12));
        assertEquals(80, ShadowRules.deathCooldown(12));
        assertEquals(5.0D, ShadowRules.deathDistance(3));
        assertEquals(6.0D, ShadowRules.deathDistance(5));
        assertEquals(7.0D, ShadowRules.deathDistance(8));
    }
    @Test
    void combatUnlocksAndExposureEndpoints() {
        assertEquals(2, ShadowRules.clones(1));
        assertEquals(2, ShadowRules.clones(3));
        assertEquals(3, ShadowRules.clones(5));
        assertEquals(4, ShadowRules.clones(8));
        assertEquals(5, ShadowRules.clones(10));
        assertEquals(6, ShadowRules.clones(12));
        assertEquals(80, ShadowRules.cloneLifetime(1));
        assertEquals(120, ShadowRules.cloneLifetime(12));
        assertEquals(1, ShadowRules.chainTargets(7));
        assertEquals(2, ShadowRules.chainTargets(8));
        assertEquals(3, ShadowRules.chainTargets(12));
        assertEquals(.4, ShadowRules.exposedBonus(3), 1e-9);
        assertEquals(.8, ShadowRules.exposedBonus(12), 1e-9);
    }
}
