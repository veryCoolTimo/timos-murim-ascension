package io.github.verycooltimo.murim.sect;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lead time (docs/adr/0017): a person sets off early enough to be at the next place when its part of the day begins. */
class SectLeadTest {

    private static final SectLayoutFile FILE = SectLayoutFile.shipped();
    private static final boolean EVENTS = SectPlaces.hasCouncilHall(FILE);
    /** A cell of the training square, where the tasks of the open air happen. */
    private static final BlockPos SQUARE = new BlockPos(-1067, 226, 4233);
    private static final long DAY3 = 3L * SectSchedule.DAY;

    /** Where the person stands just before the change: the place of the task he had. */
    private static BlockPos before(SectRoster m, long boundary) {
        long t = boundary - 1;
        SectSchedule.Task task = SectPlaces.adjust(m, SectSchedule.task(m, SectPlaces.time(FILE, m, t), EVENTS), t);
        SectPlaces.Pick p = SectPlaces.pick(FILE, m, task, t, new SectSpots(), 0L, java.util.Map.of());
        // Work in the open air has no place in the file: he stands on the training square
        return p == null ? SQUARE : p.cell();
    }

    @Test
    @DisplayName("The first change of task after the evening is found: night at 13000 for everybody, dinner at 9000 or 10000 before it")
    void findsTheBoundary() {
        for (SectRoster m : SectRoster.ALL) {
            long b = SectLead.nextChange(FILE, m, EVENTS, DAY3 + 12000L);
            assertTrue(b < 0 || b >= DAY3 + 12000L + SectLead.STEP, m.key());
        }
        SectRoster teacher = SectRoster.of("gyeong_ha").orElseThrow();
        assertEquals(DAY3 + 9000L, SectLead.nextChange(FILE, teacher, EVENTS, DAY3 + 8000L), "first wave eats at the bell");
        SectRoster pupil = SectRoster.of("yul_do").orElseThrow();
        assertEquals(DAY3 + 10000L, SectLead.nextChange(FILE, pupil, EVENTS, DAY3 + 9500L), "second wave eats in the second half");
    }

    @Test
    @DisplayName("Every meal, every person: the walk from his place to the table fits the look-ahead and he is seated by the bell plus the tolerance")
    void everyoneIsSeatedInTime() {
        int checked = 0;
        for (SectRoster m : SectRoster.ALL) {
            for (long meal : new long[] {SectSchedule.Period.BREAKFAST.start(), SectSchedule.Period.DINNER.start()}) {
                // the first change of the person inside the meal: the bell for the first wave, the half for the second
                long from = DAY3 + meal - SectLead.MAX_LEAD + 100L;
                long boundary = SectLead.nextChange(FILE, m, EVENTS, from);
                while (boundary > 0 && SectSchedule.task(m, SectPlaces.time(FILE, m, boundary), EVENTS).kind() != SectSchedule.Kind.EAT) {
                    boundary = SectLead.nextChange(FILE, m, EVENTS, boundary);
                }
                if (boundary < 0 || boundary > DAY3 + meal + 1000L) {
                    continue;
                }
                BlockPos at = before(m, boundary);
                if (at == null) {
                    continue;
                }
                // the look-ahead of the person starts where his previous change of task was
                long lastChange = from;
                for (long b = SectLead.nextChange(FILE, m, EVENTS, from); b > 0 && b < boundary; b = SectLead.nextChange(FILE, m, EVENTS, b)) {
                    lastChange = b;
                }
                // Moving from one seat of the dining hall to another is no trip: nobody is pulled from the table.
                if (SectSchedule.task(m, SectPlaces.time(FILE, m, lastChange), EVENTS).kind() == SectSchedule.Kind.EAT) {
                    continue;
                }
                SectLead.State s = new SectLead.State();
                long setOff = -1;
                for (long t = lastChange; t <= boundary + 40; t += 20) {
                    long sched = SectLead.time(s, at, FILE, m, EVENTS, t);
                    if (sched > t) {
                        setOff = t;
                        break;
                    }
                }
                int walk = SectLead.walkTicks(FILE, m, EVENTS, at, boundary);
                if (walk <= 0) {
                    continue;
                }
                if (setOff < 0 && s.boundary != boundary) {
                    // the person's own look-ahead does not see this change as a change (the key of his task stays the same): no trip
                    continue;
                }
                if (setOff < 0) {
                    assertTrue(walk <= 2 * SectLead.MARGIN, m.key() + " never set off for the meal at " + (boundary - DAY3) + " (walk " + walk + ") lastChange " + (lastChange - DAY3) + " state " + (s.boundary - DAY3) + " latched " + s.latched + " at " + at);
                    checked++;
                    continue;
                }
                assertTrue(setOff <= boundary, m.key() + " sets off after the bell");
                // (a person whose previous task ended only shortly before the bell sets off the moment it ends: nothing earlier is possible)
                assertTrue(setOff + walk <= boundary + SectLead.JITTER || setOff <= lastChange + 20L, m.key() + " would sit down at " + (setOff + walk - DAY3)
                        + ", the bell is at " + (boundary - DAY3) + " and the tolerance " + SectLead.JITTER + "; set off at " + (setOff - DAY3) + ", walk "
                        + walk + ", state boundary " + (s.boundary - DAY3) + " walk " + s.walk + " from " + lastChange + " at " + at);
                assertTrue(walk + SectLead.MARGIN <= SectLead.MAX_LEAD, m.key() + " walks " + walk + " ticks, more than the look-ahead");
                checked++;
            }
        }
        assertTrue(checked >= 15, "people checked: " + checked);
    }

    @Test
    @DisplayName("Once set off the routine is held at the start of the next part until the world time reaches it, then it is released")
    void latchesAndReleases() {
        SectRoster m = SectRoster.of("gyeong_ha").orElseThrow();
        long boundary = DAY3 + 9000L;
        BlockPos at = before(m, boundary);
        assertNotNull(at);
        SectLead.State s = new SectLead.State();
        long setOff = -1;
        for (long t = boundary - SectLead.MAX_LEAD; t < boundary; t += 20) {
            long sched = SectLead.time(s, at, FILE, m, EVENTS, t);
            if (setOff < 0 && sched > t) {
                setOff = t;
            }
            if (setOff >= 0) {
                assertEquals(boundary, sched, "held at the start of the meal from the moment he sets off, t=" + (t - DAY3));
            } else {
                assertEquals(t, sched);
            }
        }
        assertTrue(setOff > 0 && setOff < boundary - 100L, "set off at " + setOff);
        assertEquals(boundary + 5L, SectLead.time(s, at, FILE, m, EVENTS, boundary + 5L), "after the bell the world time is the routine time");
        assertFalse(s.latched);
    }

    @Test
    @DisplayName("A person who is already at the next place does not set off early")
    void noLeadWhenAlreadyThere() {
        SectRoster m = SectRoster.of("gyeong_ha").orElseThrow();
        long boundary = DAY3 + 9000L;
        SectSchedule.Task eat = SectPlaces.adjust(m, SectSchedule.task(m, SectPlaces.time(FILE, m, boundary), EVENTS), boundary);
        SectPlaces.Pick p = SectPlaces.pick(FILE, m, eat, boundary, new SectSpots(), 0L, java.util.Map.of());
        SectLead.State s = new SectLead.State();
        for (long t = boundary - SectLead.MAX_LEAD; t < boundary; t += 20) {
            assertEquals(t, SectLead.time(s, p.cell(), FILE, m, EVENTS, t), "standing at the table he waits for the bell, t=" + (t - DAY3));
        }
    }

    @Test
    @DisplayName("The estimate counts a stroll for the last blocks and a brisk walk before, scaled by the personal pace")
    void estimate() {
        SectRoster m = SectRoster.of("gyeong_ha").orElseThrow();
        double pace = SectStagger.factor(m.key(), "pace", 0.12D);
        assertEquals(Math.ceil(10 / SectLead.STROLL * SectLead.TICKS_PER_CELL_FULL / pace), SectLead.ticksFor(m, 10), 1.0);
        double longWalk = SectLead.ticksFor(m, 120);
        assertEquals(Math.ceil((20 / SectLead.STROLL + 100 / SectLead.TRANSIT) * SectLead.TICKS_PER_CELL_FULL / pace), longWalk, 1.0);
        assertTrue(SectLead.ticksFor(m, 200) > longWalk);
    }

    @Test
    @DisplayName("When the clock goes back (/time set) the look-ahead starts again instead of waiting for a future that is not coming")
    void clockGoingBack() {
        SectRoster m = SectRoster.of("gyeong_ha").orElseThrow();
        SectLead.State s = new SectLead.State();
        BlockPos at = new BlockPos(-1067, 226, 4233);
        SectLead.time(s, at, FILE, m, EVENTS, 10L * SectSchedule.DAY + 5000L);
        long boundary = DAY3 + 9000L;
        boolean set = false;
        for (long t = boundary - SectLead.MAX_LEAD; t < boundary; t += 20) {
            if (SectLead.time(s, at, FILE, m, EVENTS, t) > t) {
                set = true;
                break;
            }
        }
        assertTrue(set, "after the clock went back he never set off for the dinner");
    }
}
