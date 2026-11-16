package io.github.verycooltimo.murim.cultivation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PillScenePlannerTest {
    @Test void firstThreeFullThenShort() {
        for (int seen = 0; seen < 3; seen++) assertEquals(216, PillScenePlanner.ticks(seen, false));
        assertEquals(105, PillScenePlanner.ticks(3, false));
        assertEquals(105, PillScenePlanner.ticks(40, false));
    }

    @Test void settingForcesTheFullScene() {
        assertEquals(216, PillScenePlanner.ticks(3, true));
        assertEquals(216, PillScenePlanner.ticks(99, true));
    }

    @Test void sceneIsSevenSecondsAndShortIsThreeAndAHalf() {
        assertEquals(10.8, PillScenePlanner.FULL_TICKS / 20.0, 1e-9);
        assertEquals(5.25, PillScenePlanner.SHORT_TICKS / 20.0, 1e-9);
    }

    @Test void onlyMiddlePillsGetTheScene() {
        assertFalse(PillScenePlanner.eligible(java.util.List.of(PillKind.VITALITY)), "common pill keeps the plain absorption");
        assertFalse(PillScenePlanner.eligible(java.util.List.of(PillKind.HUNDRED_GINSENG)));
        assertTrue(PillScenePlanner.eligible(java.util.List.of(PillKind.SNOW_PLUM)));
        assertTrue(PillScenePlanner.eligible(java.util.List.of(PillKind.SMALL_RESTORATION)));
        assertTrue(PillScenePlanner.eligible(java.util.List.of(PillKind.VITALITY, PillKind.SNOW_PLUM)), "a stack with a middle pill is middle");
        assertFalse(PillScenePlanner.eligible(java.util.List.of(PillKind.ORIGIN_ENERGY)), "high pills keep the manual game");
        assertFalse(PillScenePlanner.eligible(java.util.List.of(PillKind.SNOW_PLUM, PillKind.ORIGIN_ENERGY)));
        assertFalse(PillScenePlanner.eligible(java.util.List.of(PillKind.QUICK_RECOVERY)), "utility pills never");
        assertFalse(PillScenePlanner.eligible(java.util.List.of()));
    }
}
