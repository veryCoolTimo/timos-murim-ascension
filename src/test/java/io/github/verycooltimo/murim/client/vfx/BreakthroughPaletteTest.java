package io.github.verycooltimo.murim.client.vfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The per-rank palette of the breakthrough bands (hua-main-1810 section 4): each rank has its own, all of them cycle smoothly. */
class BreakthroughPaletteTest {

    @Test
    void everyChannelStaysInRangeAndTheCycleWraps() {
        for (int rank = 1; rank <= 4; rank++) {
            for (double t = -3.0D; t <= 3.0D; t += 0.05D) {
                VfxColour c = BreakthroughVfx.rim(rank, t);
                assertTrue(c.red() >= 0.0F && c.red() <= 1.0F && c.green() >= 0.0F && c.green() <= 1.0F
                        && c.blue() >= 0.0F && c.blue() <= 1.0F, "rank " + rank + " t " + t + " out of range");
            }
            VfxColour a = BreakthroughVfx.rim(rank, 0.25D);
            VfxColour b = BreakthroughVfx.rim(rank, 1.25D);
            assertEquals(a.red(), b.red(), 1.0E-4F);
            assertEquals(a.green(), b.green(), 1.0E-4F);
            assertEquals(a.blue(), b.blue(), 1.0E-4F);
        }
    }

    @Test
    void theRanksHaveTheirOwnColours() {
        // The start of each cycle: ice, gold, plum pink, rainbow pink: four different hues.
        VfxColour third = BreakthroughVfx.rim(1, 0.0D);
        VfxColour second = BreakthroughVfx.rim(2, 0.0D);
        VfxColour first = BreakthroughVfx.rim(3, 0.0D);
        VfxColour peak = BreakthroughVfx.rim(4, 0.0D);
        assertTrue(third.blue() > third.red(), "the third rank is cool");
        assertTrue(second.red() > second.blue(), "the second rank is warm gold");
        assertTrue(first.red() > first.green() && first.blue() > first.green(), "the first rank is plum pink");
        assertNotEquals(first.green(), peak.green(), "the Peak is not the first rank's pink");
    }

    @Test
    void thePeakIsTheFullRainbowAndTheOthersStayInTheirFamily() {
        // Along one cycle the Peak passes warm and cool colours; the third rank never turns warm red.
        boolean warm = false;
        boolean cool = false;
        for (double t = 0.0D; t < 1.0D; t += 0.02D) {
            VfxColour c = BreakthroughVfx.rim(4, t);
            warm |= c.red() > 0.9F && c.blue() < 0.4F;
            cool |= c.blue() > 0.9F && c.red() < 0.5F;
        }
        assertTrue(warm && cool, "the Peak's rim runs through red and blue");
        for (double t = 0.0D; t < 1.0D; t += 0.02D) {
            VfxColour c = BreakthroughVfx.rim(1, t);
            assertTrue(c.blue() >= 0.7F, "the third rank stays cool at t " + t);
        }
    }
}
