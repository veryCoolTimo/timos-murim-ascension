package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.client.camera.ShotTrack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeadIntroPlanTest {
    @Test void coverageCutsAndRelease() {
        int end = 0;
        assertEquals(5, HeadIntroPlan.SHOTS.size());
        for (var shot : HeadIntroPlan.SHOTS) {
            assertEquals(end, shot.from());
            assertEquals(ShotTrack.Ease.IN_OUT, shot.track().ease());
            assertTrue(shot.until() > shot.from());
            end = shot.until();
        }
        assertEquals(320, end);
        for (int cut : new int[] {70, 130, 230}) {
            assertNotSame(HeadIntroPlan.shot(cut - .01F), HeadIntroPlan.shot(cut));
            assertNotEquals(HeadIntroPlan.line(cut - .01F), HeadIntroPlan.line(cut));
        }
        assertEquals(HeadIntroPlan.SHOTS.get(3).track().at(1), HeadIntroPlan.SHOTS.get(4).track().at(0));
        assertEquals("release", HeadIntroPlan.shot(305).track().name());
        assertEquals(0, HeadIntroPlan.bars(0));
        assertEquals(1, HeadIntroPlan.bars(8));
        assertEquals(0, HeadIntroPlan.bars(318));
        assertEquals(0, HeadIntroPlan.envelope(318));
    }
    @Test void polishNumbers() {
        assertTrue(HeadIntroPlan.blur(100) >= 12 && HeadIntroPlan.blur(150) >= 12);
        var reaction = HeadIntroPlan.SHOTS.get(3).track();
        assertTrue(Math.abs(reaction.at(1).azimuth() - reaction.at(0).azimuth()) >= 6);
        assertTrue(Math.abs(reaction.at(1).pitch() - reaction.at(0).pitch()) >= 2);
        var release = HeadIntroPlan.SHOTS.get(4).track();
        for (float k = 0; k <= 1; k += .05F) assertTrue(release.at(k).azimuth() <= 20.01F);
        assertEquals(-180, release.at(1).azimuth(), .001F);
        // Seated eyes on the upper-third line: the aim point sits below the eyes, so the camera looks down by 6-12 degrees.
        assertTrue(HeadIntroPlan.framingPitch(100) >= 6 && HeadIntroPlan.framingPitch(100) <= 12);
        assertTrue(HeadIntroPlan.framingPitch(180) >= 6 && HeadIntroPlan.framingPitch(180) <= 12);
        // The establishing lens is inside the hall (doors are 11.5 blocks from the seat).
        assertTrue(HeadIntroPlan.SHOTS.get(0).track().at(0).distance() <= 10.5F);
        // Shoulder camera on the side away from the mentor.
        assertTrue(HeadIntroPlan.SHOTS.get(1).track().at(0).azimuth() > 0 && HeadIntroPlan.SHOTS.get(1).track().at(1).azimuth() > 0);
        assertTrue(HeadIntroPlan.focusRange(100) < HeadIntroPlan.focusRange(50));
        // Reaction shot: the mentor beside the newcomer stays inside the focus band and is blurred less.
        assertTrue(HeadIntroPlan.focusRange(260) >= 1.6F && HeadIntroPlan.focusRange(260) <= 2.0F);
        assertTrue(HeadIntroPlan.blur(260) <= 8);
    }
    /** The mentor stands 1.5 blocks to the player's right (SectHeadIntro); the reaction shot must hold him fully in frame. */
    @Test void reactionHoldsMentorInFrame() {
        var track = HeadIntroPlan.SHOTS.get(3).track();
        for (float k = 0; k <= 1.0F; k += .25F) {
            var pose = track.at(k);
            double a = Math.toRadians(pose.azimuth());
            // a wall may pull the lens in by up to 15%
            double near = pose.distance() * .85;
            double cx = near * Math.sin(a), cz = near * Math.cos(a);
            double half = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(70 * pose.fov() / 2)) * 16.0 / 9));
            double vx = -cx, vz = -cz;
            for (double dx : new double[] {-.35, .35}) for (double dz : new double[] {-.35, .35}) {
                double rx = 1.5 + dx - cx, rz = dz - cz;
                double angle = Math.toDegrees(Math.atan2(vx * rz - vz * rx, vx * rx + vz * rz));
                assertTrue(Math.abs(angle) < half - 10, "mentor corner at " + angle + " of " + half + " (k=" + k + ")");
            }
        }
    }
}
