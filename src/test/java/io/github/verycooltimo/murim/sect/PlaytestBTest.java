package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.mastery.ArtSlots;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Playtest 22.10 rules that need no world: the day counter, the Six Elements slot rule. */
class PlaytestBTest {

    @Test
    @DisplayName("A day with no roll call but two sect tasks done counts for the streak after the night")
    void dayWithoutRollCallStillCounts() {
        SectAttendance.Log l = new SectAttendance.Log(new SectAttendance.Day(10, Set.of(), Set.of("lesson", "climb")),
                SectAttendance.Day.NONE, 0, 0, false, Long.MIN_VALUE);
        SectAttendance.Settled s = SectAttendance.roll(l, 11);
        assertTrue(s.full());
        assertEquals(1, s.log().streak());
    }

    @Test
    @DisplayName("One thing alone is not a practice day")
    void oneTaskIsNotADay() {
        SectAttendance.Log l = new SectAttendance.Log(new SectAttendance.Day(10, Set.of(), Set.of("lesson")),
                SectAttendance.Day.NONE, 0, 0, false, Long.MIN_VALUE);
        assertEquals(0, SectAttendance.roll(l, 11).log().streak());
    }

    @Test
    @DisplayName("Six Elements Sword goes on a technique slot (as its second volume) as well as on the foundation slot")
    void sixFitsBothSlots() {
        assertTrue(ArtSlots.accepts(0, ArtSlots.SIX));
    }
}
