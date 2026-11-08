package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.technique.RiverRules;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiverShapesTest {
    @Test void supplementalPetalsRespectTheExistingGatesAndBudget() {
        assertEquals(0, RiverShapes.parcelsPerPlane(RiverRules.petals(0)));
        assertEquals(0, RiverShapes.crestStreaks(RiverRules.strands(0)));
        for (int tier = 0; tier <= 8; tier++) {
            int count = RiverShapes.parcelsPerPlane(RiverRules.petals(tier));
            if (tier < 3) assertEquals(0, count);
            else {
                assertTrue(count >= 6 && count <= 22);
                int streams = RiverRules.strands(tier);
                int extra = streams * (count * 3 * 2 + 2);
                // Main folded population plus current substance leave room for the impact and accents.
                assertTrue(2 * RiverPresentation.petalCount(tier) + extra + 128 < RiverPresentation.quadCeiling(tier));
                assertTrue(RiverShapes.crestStreaks(streams) >= 3 && RiverShapes.crestStreaks(streams) <= 5);
            }
        }
    }
    @Test void paintedParcelsHaveThreeDepthPlanesAndBoundedSizes() {
        for (int identity = 0; identity < 1000; identity++) {
            double seed = RiverShapes.seed(identity);
            assertTrue(seed >= 0 && seed < 1);
            assertEquals(seed, RiverShapes.seed(identity));
            for (int plane = 0; plane < 3; plane++) {
                double size = RiverShapes.petalSize(seed, plane, false);
                assertTrue(size >= .064 && size <= .45);
                assertEquals(size, RiverShapes.petalSize(seed, plane, true));
            }
        }
    }
    @Test void fullStarHasLongAxisAndBoundedSecondaryRays() {
        for (double scale : new double[] {1, 1.15}) {
            assertTrue(RiverShapes.rayLength(0, scale, 0) >= 8 && RiverShapes.rayLength(0, scale, 0) <= 9);
            assertTrue(RiverShapes.rayLength(1, scale, 0) >= 3 && RiverShapes.rayLength(1, scale, 0) <= 4);
            for (int ray = 2; ray < 8; ray++) for (int i = 0; i <= 100; i++) {
                double length = RiverShapes.rayLength(ray, scale, i / 100.0);
                assertTrue(length >= 2.5 && length <= 5);
            }
        }
    }
    @Test void raysGrowHoldAndCoolWithoutFlicker() {
        for (int life = 12; life <= 14; life++) {
            assertEquals(0, RiverShapes.rayAlpha(-1, life));
            assertEquals(0, RiverShapes.rayAlpha(0, life));
            assertEquals(1, RiverShapes.rayAlpha(2, life));
            assertEquals(0, RiverShapes.rayAlpha(4, life));
            double previous = 0;
            for (int i = 40; i <= life * 10; i++) {
                double alpha = RiverShapes.rayAlpha(i / 10.0, life);
                assertTrue(alpha >= 0 && alpha <= previous);
                previous = alpha;
            }
            assertEquals(0, RiverShapes.rayAlpha(life, life));
        }
        assertTrue(RiverShapes.coreHalfWidth(2, .3) < .04);
        assertEquals(.04, RiverShapes.coreHalfWidth(5, .3), 1E-9);
        assertEquals(.04, RiverShapes.coreHalfWidth(12, .175), 1E-9);
        assertEquals(.7, RiverShapes.coreDiameter(0));
        assertEquals(1, RiverShapes.coreDiameter(2), 1E-9);
        assertEquals(5, RiverShapes.shockRadius(8, 1), 1E-9);
        assertEquals(5.75, RiverShapes.shockRadius(8, 1.15), 1E-9);
    }
}
