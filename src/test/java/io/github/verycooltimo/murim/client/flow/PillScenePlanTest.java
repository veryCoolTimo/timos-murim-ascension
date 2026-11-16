package io.github.verycooltimo.murim.client.flow;

import static org.junit.jupiter.api.Assertions.*;

import io.github.verycooltimo.murim.client.camera.ShotPose;
import io.github.verycooltimo.murim.cultivation.PillScenePlanner;
import org.junit.jupiter.api.Test;

class PillScenePlanTest {
    @Test void fullSceneCoversEveryTickWithoutGaps() {
        int T = PillScenePlanner.FULL_TICKS;
        int end = 0;
        for (var s : PillScenePlan.segments(T)) {
            assertEquals(end, s.from());
            assertTrue(s.until() > s.from());
            end = s.until();
        }
        assertEquals(144, end);
        assertEquals(144, PillScenePlan.total(T));
        for (int t = 0; t < 144; t++) assertNotNull(PillScenePlan.segment(T, t).beat());
        assertEquals(PillScenePlan.Beat.PILL, PillScenePlan.segment(T, 0).beat());
        assertEquals(PillScenePlan.Beat.EXHALE, PillScenePlan.segment(T, 143).beat());
    }

    @Test void beatBoundariesMatchTheBrief() {
        int T = PillScenePlanner.FULL_TICKS;
        int[] cuts = {24, 52, 92, 128};
        var beats = new PillScenePlan.Beat[] {PillScenePlan.Beat.MELT, PillScenePlan.Beat.INSIDE, PillScenePlan.Beat.CORE, PillScenePlan.Beat.EXHALE};
        for (int i = 0; i < cuts.length; i++) {
            assertNotEquals(PillScenePlan.segment(T, cuts[i] - .01F).beat(), PillScenePlan.segment(T, cuts[i]).beat());
            assertEquals(beats[i], PillScenePlan.segment(T, cuts[i]).beat());
        }
    }

    @Test void shortSceneKeepsOnlyMeltAndCore() {
        int T = PillScenePlanner.SHORT_TICKS;
        var all = PillScenePlan.segments(T);
        assertEquals(3, all.size());
        assertEquals(70, PillScenePlan.total(T));
        assertEquals(0, PillScenePlan.melt(T));
        assertEquals(-1, PillScenePlan.start(T, PillScenePlan.Beat.INSIDE));
        assertEquals(0, PillScenePlan.vignette(T, 50));
    }

    @Test void cameraTrackIsContinuousAndEndsOnTheFlowCamera() {
        for (int T : new int[] {PillScenePlanner.FULL_TICKS, PillScenePlanner.SHORT_TICKS}) {
            ShotPose prev = PillScenePlan.pose(T, 0);
            for (int t = 1; t <= PillScenePlan.total(T); t++) {
                ShotPose now = PillScenePlan.pose(T, t);
                // No pop: no channel jumps between two ticks (the biggest honest move is the pull-back, ~0.1 blocks a tick).
                assertTrue(Math.abs(now.distance() - prev.distance()) < .16F, "distance pop at " + t);
                assertTrue(Math.abs(now.pitch() - prev.pitch()) < 4F, "pitch pop at " + t);
                assertTrue(Math.abs(now.azimuth() - prev.azimuth()) < 3F, "azimuth pop at " + t);
                assertTrue(Math.abs(now.fov() - prev.fov()) < .02F, "fov pop at " + t);
                prev = now;
            }
            assertEquals(PillScenePlan.REST, PillScenePlan.pose(T, PillScenePlan.total(T)));
        }
        // v3: one framing for the whole sitting, the camera never closes in on a detail.
        assertEquals(1.65F, PillScenePlan.pose(216, 0).distance(), 1e-4);
        assertEquals(1.75F, PillScenePlan.pose(216, 92).distance(), 1e-4);
    }

    @Test void cameraStaysAtBodyDistanceAndNeverPops() {
        float min = 99;
        for (int t = 0; t <= 144; t++) min = Math.min(min, PillScenePlan.pose(216, t).distance());
        assertTrue(min >= 1.65F - 1e-3, "the whole body stays in frame");
        for (int t = 0; t < 92; t++) assertEquals(0, PillScenePlan.pose(216, t).azimuth(), 1e-4);
    }

    @Test void easeCurveIsMonotoneAndBounded() {
        float prev = 0;
        for (int i = 0; i <= 100; i++) {
            float v = PillScenePlan.smooth(i / 100F);
            assertTrue(v >= prev && v <= 1);
            prev = v;
        }
        assertEquals(0, PillScenePlan.smooth(-1));
        assertEquals(1, PillScenePlan.smooth(2));
    }

    @Test void blurVignetteAndEnvelope() {
        int T = 216;
        assertEquals(0, PillScenePlan.envelope(T, 0));
        assertEquals(1, PillScenePlan.envelope(T, 60), 1e-5);
        assertEquals(0, PillScenePlan.envelope(T, 144), 1e-5);
        assertEquals(0, PillScenePlan.vignette(T, 51.9F), 1e-3);
        assertEquals(1, PillScenePlan.vignette(T, 70), 1e-5);
        assertEquals(0, PillScenePlan.vignette(T, 98.1F), 1e-3);
        assertTrue(PillScenePlan.blur(T, 70) <= 4F && PillScenePlan.blur(T, 110) > PillScenePlan.blur(T, 70));
        // 0.3 s = 6 ticks in and out
        assertEquals(1, PillScenePlan.vignette(T, 58), 1e-5);
        assertEquals(1, PillScenePlan.vignette(T, 92), 1e-5);
    }

    @Test void orbsRunThenGatherAndTheCoreBreathes() {
        int T = 216;
        assertEquals(0, PillScenePlan.orbRoute(0, 60));
        assertEquals(1, PillScenePlan.orbRoute(PillScenePlan.ORBS - 1, 60 + 6 * (PillScenePlan.ORBS - 1) + PillScenePlan.ORB_TRAVEL), 1e-5);
        for (int i = 0; i < PillScenePlan.ORBS; i++) {
            assertEquals(0, PillScenePlan.orbConverge(T, i, 80));
            assertEquals(1, PillScenePlan.orbConverge(T, i, 104 + i), 1e-5);
        }
        assertEquals(0, PillScenePlan.coreScale(T, 91));
        assertEquals(1.5F, PillScenePlan.coreScale(T, 98), 1e-4);
        assertEquals(1.0F, PillScenePlan.coreScale(T, 92 + 22), 1e-4);
        assertEquals(1, PillScenePlan.coreAlpha(T, 127), 1e-5);
        assertEquals(0, PillScenePlan.coreAlpha(T, 144), 1e-5);
    }

    @Test void cameraEasesOutToTheOrdinaryViewWithoutAJump() {
        for (int T : new int[] {216, 105}) {
            int end = PillScenePlan.total(T);
            assertEquals(end + PillScenePlan.SETTLE, PillScenePlan.end(T));
            assertEquals(PillScenePlan.REST, PillScenePlan.pose(T, end));
            assertEquals(PillScenePlan.DEFAULT, PillScenePlan.pose(T, PillScenePlan.end(T)));
            float prev = PillScenePlan.pose(T, end).distance();
            for (int t = end + 1; t <= PillScenePlan.end(T); t++) {
                float d = PillScenePlan.pose(T, t).distance();
                assertTrue(d >= prev && d - prev < .45F, "ease out, no jump at " + t);
                prev = d;
            }
        }
    }

    @Test void orbsStaySmallInNumber() {
        assertTrue(PillScenePlan.ORBS >= 3 && PillScenePlan.ORBS <= 4);
        assertTrue(PillScenePlan.blur(216, 75) <= 4F, "the subject stays crisp in the inside beat");
        assertEquals(1.75F, PillScenePlan.pose(216, 75).distance(), .05F);
    }

    @Test void skipJumpsPastTheInsideBeat() {
        assertTrue(PillScenePlan.skipTarget(216) > PillScenePlan.core(216));
        assertTrue(PillScenePlan.skipTarget(216) < PillScenePlan.total(216) - 20);
        assertTrue(PillScenePlan.skipTarget(105) > PillScenePlan.core(105));
        assertTrue(PillScenePlan.skipTarget(105) < PillScenePlan.total(105) - 20);
    }
}
