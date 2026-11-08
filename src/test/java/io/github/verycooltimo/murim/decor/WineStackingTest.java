package io.github.verycooltimo.murim.decor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WineStackingTest {
    @Test void cupsRaiseLevelToThreeAndAccumulateAtMostFiveMinutes() {
        int amplifier = -1, duration = 0;
        int[] expected = {1800, 3600, 5400, 6000, 6000, 6000, 6000, 6000};
        for (int i = 0; i < 8; i++) {
            amplifier = WineStacking.amplifier(amplifier);
            duration = WineStacking.duration(duration);
            assertEquals(Math.min(i, 2), amplifier);
            assertEquals(expected[i], duration);
        }
    }
    @Test void partiallyExpiredAndExpiredEffectsStackFromRemainingTime() {
        assertEquals(2100, WineStacking.duration(300));
        assertEquals(1800, WineStacking.duration(0));
        assertEquals(6000, WineStacking.duration(5999));
    }
    @Test void courageAndVulnerabilityUseFivePercentPerLevel() {
        for (int level = 0; level <= 3; level++)
            assertEquals(1 + .05F * level, WineStacking.damageFactor(level), .00001F);
    }
}
