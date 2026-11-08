package io.github.verycooltimo.murim.client.vfx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BambooEndingTest {
    @Test
    void palmsAndWallsHaveOrderedStartsAndFinishWithinOneSecond() {
        for (int count : new int[]{96, 18, 77}) {
            for (int end : new int[]{20, 26}) {
                double previous = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < count; i++) {
                    double start = BambooEnding.start(end - 20, end, i, count);
                    assertTrue(start > previous);
                    assertEquals(1F, BambooEnding.fade((float) start, start), 1E-6F);
                    assertEquals(.5F, BambooEnding.fade((float) (start + 3), start), 1E-6F);
                    assertEquals(0F, BambooEnding.fade(end, start));
                    previous = start;
                }
                assertEquals(end, previous + BambooEnding.FADE_TICKS, 1E-9);
            }
        }
    }

    @Test
    void deflectPiecesLeaveInOrderAndTravelBeforeTheirOwnFade() {
        int count = 96; // Four subdivisions of each of the 24 silhouette triangles.
        double previous = Double.NEGATIVE_INFINITY;
        for (int piece = 0; piece < count; piece++) {
            double start = BambooEnding.pieceStart(20, piece, count);
            assertTrue(start > previous);
            assertEquals(0, BambooEnding.pieceDistance(start, start));
            assertEquals(1F, BambooEnding.pieceFade(start, start));
            assertEquals(1F, BambooEnding.pieceFade(start + 4, start));
            assertTrue(BambooEnding.pieceDistance(start + 4, start) >= .5);
            assertEquals(.5F, BambooEnding.pieceFade(start + 6, start), 1E-6F);
            assertEquals(0F, BambooEnding.pieceFade(20, start));
            for (double age = 0; age <= 20; age += .125) {
                float fade = BambooEnding.pieceFade(age, start);
                if (fade < 1 && fade > 0) {
                    assertTrue(BambooEnding.pieceDistance(age, start) >= .5);
                }
            }
            previous = start;
        }
        assertEquals(0, BambooEnding.pieceStart(20, 0, count));
        assertEquals(20, previous + 8);
        for (int tick = 0; tick < 20; tick++) {
            int starting = 0;
            for (int piece = 0; piece < count; piece++) {
                double start = BambooEnding.pieceStart(20, piece, count);
                if (start >= tick && start < tick + 1) starting++;
            }
            assertTrue(starting <= 8, "Only a few pieces may detach per tick");
        }
    }

    @Test
    void swordMarksPulseInSequenceWithoutOverlap() {
        for (float age = 0; age <= 30; age += .125F) {
            int active = 0;
            for (int point = 0; point < 6; point++) {
                if (BambooEnding.pointFade(age, point) > 0) active++;
            }
            assertTrue(active <= 1);
        }
        for (int point = 0; point < 6; point++) {
            assertEquals(1F, BambooEnding.pointFade(point * 1.5F + .5F, point));
            assertEquals(0F, BambooEnding.pointFade(9, point));
        }
    }
}
