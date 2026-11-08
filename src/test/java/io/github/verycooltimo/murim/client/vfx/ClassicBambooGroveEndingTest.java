package io.github.verycooltimo.murim.client.vfx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassicBambooGroveEndingTest {
    @Test
    void actualPiecesFillTheSameTwentySixTickEndingAtEveryHeight() {
        assertEquals(26, ClassicBambooGroveEnding.END_TICK - ClassicBambooGroveEnding.START_TICK);
        for (int stalks = 36; stalks <= 48; stalks += 3) {
            for (int nodes = 2; nodes <= 15; nodes++) {
                int pieces = 9 + nodes / 2 + nodes;
                double previous = Double.NEGATIVE_INFINITY;
                int[] startsPerTick = new int[54];
                for (int piece = 0; piece < pieces; piece++) {
                    for (int stalk = 0; stalk < stalks; stalk++) {
                        double start = ClassicBambooGroveEnding.pieceStart(piece, pieces, stalk, stalks);
                        assertTrue(start > previous);
                        assertEquals(1F, ClassicBambooGroveEnding.pieceFade((float) start,
                                piece, pieces, stalk, stalks), 1E-6F);
                        assertEquals(0F, ClassicBambooGroveEnding.pieceFade(54, piece, pieces, stalk, stalks));
                        startsPerTick[(int) start]++;
                        previous = start;
                    }
                }
                assertEquals(54, previous + ClassicBambooEnding.FADE_TICKS, 1E-9);
                for (int starts : startsPerTick) {
                    assertTrue(starts <= Math.ceil(pieces * stalks / 20.0),
                            "At most five percent start dissolving per tick");
                }
            }
        }
    }
}
