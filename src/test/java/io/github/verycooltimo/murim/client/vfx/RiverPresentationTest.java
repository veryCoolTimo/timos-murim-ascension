package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.technique.RiverRules;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiverPresentationTest {
    @Test void tierZeroIsBareAndCountsFollowTheExistingLadder() {
        assertEquals(0, RiverPresentation.veins(0));
        assertEquals(0, RiverPresentation.quadCeiling(0));
        for (int tier = 0; tier <= 8; tier++) {
            assertEquals(new int[]{0, 0, 0, 80, 120, 170, 220, 270, 300}[tier], RiverPresentation.petalCount(tier));
            if (tier > 0) {
                assertTrue(RiverPresentation.veins(tier) >= 3 && RiverPresentation.veins(tier) <= 5);
                assertEquals(2400 + 2 * RiverPresentation.petalCount(tier), RiverPresentation.quadCeiling(tier));
            }
        }
    }
    @Test void parcelsOvertakeWithoutReversingAndArriveAtContact() {
        for (int flight = 3; flight <= 8; flight++) {
            for (int seed = 0; seed <= 10; seed++) {
                double previous = 0;
                for (int frame = 0; frame <= flight * 100; frame++) {
                    double p = RiverPresentation.progress(frame / 100.0, flight, seed / 10.0, seed / 10.0);
                    assertTrue(p >= previous && p <= 1);
                    previous = p;
                }
                assertEquals(1, previous, 1E-9);
                assertEquals(0, RiverPresentation.progress(0, flight, seed / 10.0, seed / 10.0));
            }
            assertTrue(RiverPresentation.progress(flight / 2.0, flight, 1, 0)
                    > RiverPresentation.progress(flight / 2.0, flight, 0, 0));
        }
    }
    @Test void starHasCompactCoreNeedleTipsAndShortPeak() {
        assertEquals(0, RiverPresentation.halfWidth(0));
        assertEquals(.04, RiverPresentation.halfWidth(.001));
        for (int i = 0; i <= 100; i++) {
            double age = i / 10.0;
            assertTrue(RiverPresentation.coreDiameter(age) >= .4 && RiverPresentation.coreDiameter(age) <= .8);
            assertTrue(RiverPresentation.rayAlpha(age, 9) >= 0 && RiverPresentation.rayAlpha(age, 9) <= 1);
        }
        assertEquals(1, RiverPresentation.rayAlpha(2, 9), 1E-9);
        assertEquals(0, RiverPresentation.rayAlpha(10, 9));
    }
    @Test void currentNarrowsAndPartsAtContactAndGroundHoldsAreStaggered() {
        assertEquals(.9, RiverPresentation.currentRadius(0, false));
        assertEquals(.3, RiverPresentation.currentRadius(1, false), 1E-9);
        assertTrue(RiverPresentation.currentRadius(.88, true) > RiverPresentation.currentRadius(.88, false) + .4);
        assertEquals(60, RiverPresentation.groundLife(0));
        assertEquals(160, RiverPresentation.groundLife(1));
        assertEquals(110, RiverPresentation.groundLife(.5));
    }
}
