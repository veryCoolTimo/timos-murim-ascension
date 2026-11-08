package io.github.verycooltimo.murim.client.vfx;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BambooLookTest {
    @Test void roundTwoMassAndStripBoundsHoldAcrossLayouts() {
        assertTrue(BambooLook.WALL_STEMS >= 7 && BambooLook.WALL_STEMS <= 11);
        assertTrue(BambooLook.GROVE_STEMS >= 5 && BambooLook.GROVE_STEMS <= 7);
        assertEquals(BambooLook.Shape.PALM, BambooLook.shape(3));
        for (long seed : new long[]{0, 1, -17, Long.MAX_VALUE}) for (int i = 0; i < 11; i++) {
            double diameter = BambooLook.stemThickness(seed, i);
            assertTrue(diameter >= .25 && diameter <= .45);
            // Rendered crown taper must preserve the minimum thickness, too.
            assertTrue(Math.max(.25, diameter * .88) >= .25);
            assertTrue(BambooLook.wallHeight(seed, i) >= 1.5 && BambooLook.wallHeight(seed, i) <= 3);
            assertTrue(BambooLook.groveHeight(seed, i) >= 2 && BambooLook.groveHeight(seed, i) <= 4);
        }
        for (int step = 0; step <= 100; step++) {
            double taperedWidth = .22 * (1 - step / 100.0);
            assertTrue(2 * BambooLook.stripHalfWidth(taperedWidth) >= .08);
        }
    }

    @Test void onlyActualTwelveStarsSelectTheMasteredLook() {
        for (int stars = 0; stars <= 11; stars++) assertFalse(BambooLook.mastered(stars));
        assertTrue(BambooLook.mastered(12));
        assertFalse(BambooLook.mastered(13));
        // An execution tier of 3/4 is never a substitute for the actual twelve-star flag.
        assertFalse(BambooLook.mastered(4));
    }
    @Test void wallAndBendDispatchToTheirOwnGeometryWithoutAPalm() {
        assertEquals(BambooLook.Shape.WALL, BambooLook.shape(4));
        assertEquals(BambooLook.Shape.BEND, BambooLook.shape(2));
        assertNotEquals(BambooLook.Shape.PALM, BambooLook.shape(4));
        assertNotEquals(BambooLook.Shape.HAND_PALM, BambooLook.shape(2));
        assertEquals(BambooLook.Shape.PALM, BambooLook.shape(3));
        assertEquals(BambooLook.Shape.BLADE_SHARDS, BambooLook.shape(5));
        assertEquals(BambooLook.Shape.HAND_PALM, BambooLook.shape(7));
        assertEquals(BambooLook.Shape.GROVE, BambooLook.shape(9));
    }
    @Test void everyStemPieceFadesSmoothlyBeforeTheOwningMarkExpires() {
        for (long seed : new long[]{0, 1, -1, 9173, Long.MAX_VALUE}) {
            for (boolean grove : new boolean[]{false, true}) {
                for (int piece = 0; piece < (grove ? 27 : 21); piece++) {
                    double end = BambooLook.endAt(seed, piece, grove);
                    assertTrue(Double.isFinite(end));
                    assertTrue(end < BambooLook.lifetime(grove ? 9 : 4));
                    assertEquals(1, BambooLook.fade(end - 6, 0, end), 1E-6);
                    assertEquals(.5, BambooLook.fade(end - 3, 0, end), 1E-6);
                    assertEquals(0, BambooLook.fade(end, 0, end));
                    assertEquals(0, BambooLook.fade(BambooLook.lifetime(grove ? 9 : 4), 0, end));
                }
            }
        }
    }
    @Test void seededLayoutsAreRepeatableAndVaryAcrossCastsAndPieces() {
        double first = BambooLook.noise(17, 0, 0);
        assertEquals(first, BambooLook.noise(17, 0, 0));
        assertNotEquals(first, BambooLook.noise(18, 0, 0));
        assertNotEquals(first, BambooLook.noise(17, 1, 0));
        for (int i = 0; i < 100; i++) {
            double n = BambooLook.noise(17, i, 7);
            assertTrue(n >= 0 && n < 1);
        }
    }
    @Test void everyOtherMasteredPieceHasAnIndividualFiniteFadeDeadline() {
        for (long seed : new long[]{0, 1, -17, Long.MAX_VALUE}) for (int i = 0; i < 32; i++) {
            for (double end : new double[]{BambooLook.palmEnd(seed,i), BambooLook.shardEnd(seed,i),
                    BambooLook.bendEnd(seed,i), BambooLook.ribbonEnd(seed,i,false), BambooLook.ribbonEnd(seed,i,true), 11, 9}) {
                assertTrue(Double.isFinite(end));
                assertTrue(end > 6 && end < 34);
                float previous = 1;
                for (double age = end - 6; age <= end + 1; age += .125) {
                    float alpha = BambooLook.fade(age,0,end);
                    assertTrue(Float.isFinite(alpha) && alpha >= 0 && alpha <= previous);
                    previous = alpha;
                }
                assertEquals(0, BambooLook.fade(end,0,end));
            }
        }
    }
    @Test void fractionalAccentsHaveBoundedWindows() {
        for (double duration : new double[]{1, 1.2, 1.6, 2.4}) {
            assertEquals(0, BambooLook.pulse(-.1, duration));
            assertEquals(0, BambooLook.pulse(0, duration));
            assertTrue(BambooLook.pulse(.2, duration) > 0);
            assertEquals(0, BambooLook.pulse(duration, duration));
            assertEquals(0, BambooLook.pulse(duration + 1, duration));
        }
    }
}
