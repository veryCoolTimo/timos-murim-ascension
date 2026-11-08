package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.sect.SectSchedule.Kind;
import io.github.verycooltimo.murim.sect.SectSchedule.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The day of a guard (behaviour review 21.10, round 1): round, meal relief, the gatekeeper's night, the keepers of the closed places. */
class SectGuardDayTest {

    private static final SectRoster GATEKEEPER = SectRoster.of("gatekeeper").orElseThrow();

    @Test
    @DisplayName("watch-2: a day guard walks a round: post 0 the hall, ancestors and scriptures; post 1 the treasury, elders and cave")
    void patrolMoves() {
        for (long day = 0; day < 7; day++) {
            for (int post = 0; post < SectRota.POSTS; post++) {
                SectRoster g = SectRota.dayWatch(day, post);
                Set<String> zones = new HashSet<>();
                for (long t = day * 24000L + 2100L; t < day * 24000L + 9000L; t += 100L) {
                    if (SectRota.meal(g, t).isEmpty()) {
                        Task task = SectSchedule.task(g, t);
                        assertEquals(Kind.GUARD, task.kind(), g.key() + " at " + t);
                        zones.add(task.zone());
                    }
                }
                assertEquals(post == 0 ? Set.of("main_hall", "ancestors", "scriptures") : Set.of("treasury", "elders", "penance"), zones,
                        "round of post " + post + " on day " + day);
            }
        }
    }

    @Test
    @DisplayName("watch-2: the shift starts at its own post (the relief), then the round begins")
    void handoverAtHomePost() {
        for (int post = 0; post < SectRota.POSTS; post++) {
            long t = 13000L + 50L;
            SectRoster night = SectRota.nightWatch(SectSchedule.day(t), post);
            assertEquals(SectSchedule.post(new SectRota.Duty(post, true)), SectSchedule.task(night, t));
        }
    }

    @Test
    @DisplayName("watch-2: in every meal each of the three eats once, the other day guard stands in his place, nobody eats twice")
    void mealRelief() {
        for (long day = 0; day < 7; day++) {
            for (SectSchedule.Period p : new SectSchedule.Period[] {SectSchedule.Period.BREAKFAST, SectSchedule.Period.DINNER}) {
                SectRoster g0 = SectRota.dayWatch(day, 0);
                SectRoster g1 = SectRota.dayWatch(day, 1);
                int len = SectRota.mealLength(p);
                // the day of the sect starts at the formation (23000 of the day before)
                long base = day * 24000L + p.start();
                int[] eats = new int[3];
                for (int since = 0; since < len; since += 20) {
                    long t = base + since;
                    List<SectRoster> who = List.of(GATEKEEPER, g0, g1);
                    for (int i = 0; i < 3; i++) {
                        if (SectRota.eating(who.get(i), t)) {
                            eats[i]++;
                            assertEquals(Kind.EAT, SectSchedule.task(who.get(i), t).kind());
                        }
                    }
                    // the gate always has somebody on it by day; the hall and the treasury are held by the one who is not at the table
                    boolean gate = false;
                    for (SectRoster m : who) {
                        Task task = SectSchedule.task(m, t);
                        gate |= task.kind() == Kind.GUARD && "sect_gate".equals(task.zone());
                    }
                    assertTrue(gate, "empty gate " + p + " +" + since + " day " + day);
                    assertTrue(!SectRota.eating(g0, t) || !SectRota.eating(g1, t) && !SectRota.eating(GATEKEEPER, t), "two eat at once");
                }
                for (int i = 0; i < 3; i++) {
                    assertTrue(eats[i] > 0, "person " + i + " never eats in " + p + " day " + day);
                }
            }
        }
    }

    @Test
    @DisplayName("watch-2: the gatekeeper sleeps at night once the night guard of post 0 has the gate, and is back at the gate before the formation")
    void gatekeeperNight() {
        for (long day = 0; day < 7; day++) {
            long start = day * 24000L + 13000L;
            assertEquals(Kind.GUARD, SectSchedule.task(GATEKEEPER, start + 100L).kind(), "he waits for the relief");
            for (long t = start + SectSchedule.HANDOVER; t < start + 10000L; t += 500L) {
                assertEquals(Kind.SLEEP, SectSchedule.task(GATEKEEPER, t).kind(), "asleep at " + t);
                SectRoster night0 = SectRota.nightWatch(SectSchedule.day(t), 0);
                Task covering = SectSchedule.task(night0, t);
                assertEquals(Kind.GUARD, covering.kind());
                assertEquals("sect_gate", covering.zone(), "the night guard of post 0 holds the gate");
            }
            assertEquals(Kind.GUARD, SectSchedule.task(GATEKEEPER, day * 24000L + 24000L - 100L).kind(), "at the gate before the formation");
            assertEquals(Kind.GUARD, SectSchedule.task(GATEKEEPER, day * 24000L + 4000L).kind());
        }
    }

    @Test
    @DisplayName("watch-1: every closed place has an awake keeper throughout the day, night and meals")
    void everyClosedPlaceHasKeeper() {
        List<String> zones = SectAccess.RULES.stream().map(SectAccess.Rule::zone).filter(z -> !SectAccess.GROUNDS.equals(z)).toList();
        assertEquals(7, zones.size());
        for (long day = 1; day < 8; day++) {
            for (String zone : zones) {
                for (long[] window : new long[][] {{1000L, 13000L}, {13700L, 22900L}}) {
                    for (long t = day * 24000L + window[0]; t < day * 24000L + window[1]; t += 100L) {
                        SectRoster k = SectRota.keeper(zone, t);
                        assertTrue(k != null, zone + " has no keeper at " + t);
                        assertTrue(SectRota.onDuty(k, t));
                        assertFalse(SectRota.eating(k, t));
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("hier-1: who is greeted how")
    void greetingByRank() {
        assertEquals(SectHonour.Greeting.NONE, SectHonour.greeting(false, false, false, SectStanding.OUTSIDER, 0));
        assertEquals(SectHonour.Greeting.WORD, SectHonour.greeting(true, false, false, SectStanding.NOVICE, 0));
        assertEquals(SectHonour.Greeting.WORD, SectHonour.greeting(true, false, false, SectStanding.DISCIPLE, 1));
        assertEquals(SectHonour.Greeting.NOD, SectHonour.greeting(true, false, false, SectStanding.GRADUATE, 2));
        assertEquals(SectHonour.Greeting.BOW, SectHonour.greeting(true, false, true, SectStanding.DISCIPLE, 1));
        assertEquals(SectHonour.Greeting.BOW, SectHonour.greeting(true, true, false, SectStanding.TRUSTED, 4));
        assertTrue(SectHonour.superior(SectRole.LEADER) && SectHonour.superior(SectRole.ELDER) && SectHonour.superior(SectRole.FIRST));
        assertFalse(SectHonour.superior(SectRole.DISCIPLE_A));
    }
}
