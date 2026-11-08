package io.github.verycooltimo.murim.client.vfx;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlumPolishTest {
    @Test void pigmentsHaveExactReviewRatios() {
        for (boolean scatter : new boolean[]{false, true}) {
            int[] counts = new int[3];
            for (int i = 0; i < 100; i++) counts[PlumPolish.pigment(i, scatter)]++;
            assertArrayEquals(scatter ? new int[]{65, 10, 25} : new int[]{65, 25, 10}, counts);
        }
    }
    @Test void seaHasThreeBoundedPlanesAndIndependentCrests() {
        assertTrue(PlumPolish.seaSlots() >= 900 && PlumPolish.seaSlots() <= 1300);
        for (int slot = 0; slot < 1000; slot++) {
            double lo = new double[]{.2, .7, 1.3}[slot % 3];
            for (double phase : new double[]{0, Math.PI / 2, -Math.PI / 2}) {
                double h = PlumPolish.seaHeight(slot, phase);
                assertTrue(h >= lo && h <= lo + .3 + 1e-9);
            }
        }
        for (int crest = 0; crest < 11; crest++) {
            assertTrue(PlumPolish.crestSpeed(crest) >= .08 && PlumPolish.crestSpeed(crest) <= .22);
            assertTrue(PlumPolish.crestRadius(crest, 40) >= 1 && PlumPolish.crestRadius(crest, 40) <= 9);
        }
    }
    @Test void headClearanceIncludesParticleExtentAndStrikeOffsetsAlternate() {
        assertFalse(PlumPolish.headClear(.36, .3));
        assertTrue(PlumPolish.headClear(1, .3));
        for (int clone = 0; clone < 6; clone++) assertEquals(clone % 2, PlumPolish.strikeDelay(clone));
    }
}
