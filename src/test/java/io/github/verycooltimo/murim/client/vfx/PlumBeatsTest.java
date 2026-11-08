package io.github.verycooltimo.murim.client.vfx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlumBeatsTest {
    @Test
    void everyLargeShapeCanHoldForASecond() {
        for (var beat : new PlumBeats.Beat[]{PlumBeats.SEA, PlumBeats.RIVER,
                PlumBeats.RAIN, PlumBeats.BLOOM, PlumBeats.SCATTER}) {
            assertTrue(beat.strike() - beat.formed() >= 20);
            assertTrue(beat.holding(beat.formed()));
            assertTrue(beat.holding(beat.strike() - .01));
            assertFalse(beat.holding(beat.strike()));
            assertEquals(1, beat.gather(beat.formed()), 1E-9);
            assertEquals(0, beat.attack(beat.strike()), 1E-9);
        }
    }

    @Test
    void presentationStillMeetsExistingCombatCues() {
        assertEquals(100, 60 + PlumBeats.RIVER.strike());
        assertEquals(144, 66 + PlumBeats.RAIN.strike());
        assertTrue(PlumBeats.RAIN.end() >= PlumBeats.RAIN.strike() + 28 + 24);
    }

    @Test
    void foregroundPetalsDisappearSmoothlyAtTwoBlocks() {
        assertEquals(0, PlumBeats.nearFade(0));
        assertEquals(0, PlumBeats.nearFade(2));
        assertEquals(.5, PlumBeats.nearFade(2.75), 1E-9);
        assertEquals(1, PlumBeats.nearFade(3.5));
        assertEquals(1, PlumBeats.nearFade(100));
    }
}
