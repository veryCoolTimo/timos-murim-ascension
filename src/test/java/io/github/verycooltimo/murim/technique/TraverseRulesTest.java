package io.github.verycooltimo.murim.technique;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraverseRulesTest {

    @Test
    void growsWithMastery() {
        for (int layer = 1; layer <= 4; layer++) {
            assertTrue(TraverseRules.speed(layer) > TraverseRules.speed(layer - 1));
            assertTrue(TraverseRules.maxTicks(layer) > TraverseRules.maxTicks(layer - 1));
            assertTrue(TraverseRules.qiPerSecond(layer) < TraverseRules.qiPerSecond(layer - 1));
            assertTrue(TraverseRules.leapHorizontal(layer) > TraverseRules.leapHorizontal(layer - 1));
        }
    }

    @Test
    void layerZeroIsJustARun() {
        assertEquals(0.0D, TraverseRules.leapHorizontal(0));
        assertEquals(0, TraverseRules.wallKicks(0));
        assertEquals(0.6D, TraverseRules.stepHeight(0));
        assertFalse(TraverseRules.airCorrection(3));
        assertTrue(TraverseRules.airCorrection(4));
    }

    @Test
    void speedStaysUnderVanillaMoveCheck() {
        // Ванильная проверка «moved too quickly» ~10 блоков за пакет; бег держим ≤ 0,65 б/т,
        // толчки ≤ 1,5 б/т (docs/design/20-movement-qinggong.md §8.3).
        assertTrue(TraverseRules.speed(4) / 20.0D <= 0.65D + 1.0E-9D);
        assertTrue(TraverseRules.leapHorizontal(4) <= 1.5D);
        assertEquals(13.0D / 5.6D - 1.0D, TraverseRules.speedBonus(4), 1.0E-9D);
    }
}
