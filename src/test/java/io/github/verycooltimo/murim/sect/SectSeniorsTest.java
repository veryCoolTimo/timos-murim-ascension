package io.github.verycooltimo.murim.sect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round 3 schedule rules (elders-2). */
class SectSeniorsTest {
    private static long at(long day, int tick) { return day * SectSchedule.DAY + tick; }

    @Test
    void leaderReceivesDeskMeal() {
        SectRoster head = SectRoster.of("tae_hwi").orElseThrow();
        long dinner = at(3, SectSchedule.Period.DINNER.start() + 100);
        SectSchedule.Task t = SectPlaces.adjust(head, SectSchedule.task(head, dinner), dinner);
        assertEquals(SectSchedule.Kind.EAT, t.kind());
        assertEquals("main_hall", t.zone());
        assertTrue(t.partner().startsWith("desk:"), "the head eats at his own seat");
    }

    @Test
    void outingFitsBeforeCouncilLead() {
        SectRoster head = SectRoster.of("tae_hwi").orElseThrow();
        int lead = SectCouncil.config().leadOf("tae_hwi");
        int to = SectLessons.n("outingTo", 4900);
        assertTrue(to <= SectCouncil.config().from() - lead, "the outing must end before the head sets out for the council");
        for (long day : new long[]{1, 2, 3, 4}) {
            assertEquals(SectSchedule.Kind.WATCH, SectSchedule.task(head, at(day, 4500)).kind());
            assertEquals(SectSchedule.HALL_STEPS, SectSchedule.task(head, at(day, 3000)).partner());
        }
    }
}
