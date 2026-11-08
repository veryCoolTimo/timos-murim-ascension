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
}
