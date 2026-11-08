package io.github.verycooltimo.murim.sect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Desk work is done sitting (author 17.10): the shipped layout gives the leader, elders and clerks a seat at the desk. */
class SectDeskSeatTest {

    private static SectPlaces.Pick pick(String key, SectSchedule.Task t, long time) {
        SectLayoutFile f = SectLayoutFile.shipped();
        return SectPlaces.pick(f, SectRoster.of(key).orElseThrow(), t, time, null, 0L, null);
    }

    @Test
    @DisplayName("The leader sits on the mat behind the low desk of the dais during audience hours")
    void leaderOnTheDaisMat() {
        var t = new SectSchedule.Task(SectSchedule.Kind.RECEIVE, "main_hall", 0, 4, 0, -1);
        var p = pick("tae_hwi", t, 5000L);
        assertNotNull(p);
        assertEquals("main_hall.f0.r7", p.room());
        assertNotNull(p.seat(), "a seat, not a standing spot");
        assertEquals(234, p.seat().getY());
        assertTrue(p.seat().getZ() == 4281 && (p.seat().getX() == -1066 || p.seat().getX() == -1065), p.seat().toString());
    }

    @Test
    @DisplayName("The leader works at the study desk outside audience hours")
    void leaderInTheStudy() {
        var t = new SectSchedule.Task(SectSchedule.Kind.RECEIVE, "main_hall", 0, 4, 0, -1);
        var p = pick("tae_hwi", t, 1500L);
        assertNotNull(p);
        assertEquals("main_hall.f3.r3", p.room());
        assertNotNull(p.seat());
    }

    @Test
    @DisplayName("The treasurer counts at a seat of the treasury")
    void treasurerSits() {
        var t = new SectSchedule.Task(SectSchedule.Kind.COUNT, "treasury", 5.5, 0, 1, 0);
        var p = pick("tae_gyun", t, 5000L);
        assertNotNull(p);
        assertNotNull(p.seat());
    }
}
