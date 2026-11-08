package io.github.verycooltimo.murim.client.vfx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RiverStormTimelineTest {
    @Test
    void everyStripKeepsAtLeastPointZeroEightBlocksFullWidth() {
        for (double width : new double[] {0, .001, .015, .016, .03, .04, .08, 1}) {
            assertTrue(2 * RiverStormTimeline.halfWidth(width) >= .08);
            assertEquals(Math.max(.04, width), RiverStormTimeline.halfWidth(width));
        }
    }

    @Test
    void streamNeverReturnsOrMovesBeforeRelease() {
        for (int i = 0; i < RiverStormTimeline.APEX_SLOTS; i++) {
            for (int flight = 3; flight <= 8; flight++) {
                double previous = 0;
                for (int frame = 0; frame <= 600; frame++) {
                    double tick = frame / 10.0;
                    double progress = RiverStormTimeline.streamProgress(i, tick, flight);
                    assertTrue(progress >= previous && progress <= 1);
                    if (tick <= 40) assertEquals(0, progress);
                    previous = progress;
                }
                assertEquals(1, previous);
            }
        }
    }

    @Test
    void confirmedBurstSurvivesEndAndFillsTheAttackAxisForTwentyTicks() {
        // Existing HIGH/ALL apex budget: 2200 slots, with six petals per flower slot.
        // BURST at storm age 60 (cast T120); a preceding END must not cull its tail.
        for (int age = 0; age <= 20; age++) {
            assertFalse(RiverStormTimeline.expired(60 + age, 0, 60));
            int points = 0;
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < RiverStormTimeline.APEX_SLOTS; i++) {
                var point = RiverStormTimeline.point(i, age, 1);
                assertEquals(1, point.alpha(), 1E-9);
                if (point.alpha() > .1) points += RiverStormTimeline.petals(i);
                min = Math.min(min, point.along());
                max = Math.max(max, point.along());
            }
            assertTrue(points >= 2500, "petal submissions at burst +" + age);
            assertTrue(max - min >= 9, "attack-axis span at burst +" + age);
        }
        assertTrue(RiverStormTimeline.expired(111, 0, 60));
    }

    @Test
    void petalsMeltIndividuallyAfterTheHold() {
        int previous = 2200;
        boolean partial = false;
        for (int age = 25; age <= 51; age++) {
            int visible = 0;
            for (int i = 0; i < RiverStormTimeline.APEX_SLOTS; i++) {
                double alpha = RiverStormTimeline.point(i, age, 1).alpha();
                assertTrue(alpha >= 0 && alpha <= 1);
                assertTrue(alpha <= RiverStormTimeline.point(i, age - 1, 1).alpha());
                if (alpha > 0) visible++;
            }
            assertTrue(visible <= previous);
            partial |= visible > 0 && visible < 2200;
            previous = visible;
        }
        assertTrue(partial);
        assertEquals(0, previous);
    }
}
