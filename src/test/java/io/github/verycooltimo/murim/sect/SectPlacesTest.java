package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.sect.SectSchedule.Kind;
import io.github.verycooltimo.murim.sect.SectSchedule.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Docs/design/38 section 5.8, test 2: role x time of day -> real, reachable, unshared places. */
class SectPlacesTest {

    private static final SectLayoutFile FILE = SectLayoutFile.shipped();
    /** Tasks that must get a place in the built sect (the rest may stay on the plan zones). */
    private static final Set<Kind> INDOOR = EnumSet.of(Kind.EAT, Kind.SLEEP, Kind.COOK, Kind.SERVE, Kind.TEND, Kind.BREW,
            Kind.GRIND, Kind.READ, Kind.COUNT, Kind.COUNCIL, Kind.RECEIVE, Kind.REVERE, Kind.FORM_ROW);

    private static Task taskOf(SectRoster m, long time) {
        long t = SectPlaces.time(FILE, m, time);
        return SectPlaces.adjust(m, SectSchedule.task(m, t), time);
    }

    @Test
    @DisplayName("Gate work fallback stays inside even when no authored layout is available")
    void gateWorkFallbackStaysInside() {
        for (SectRoster m : SectRoster.ALL) {
            for (long time = 0; time < 3 * 24000L; time += 50) {
                Task task = SectSchedule.task(m, time);
                if (task.kind() == Kind.WORK && "sect_gate".equals(task.zone())) {
                    assertTrue(task.dv() >= 2.0D, m.key() + " works outside the gate at " + time);
                }
            }
        }
    }

    @Test
    @DisplayName("All day for everybody: indoor tasks get a walkable place, no two people share a place at the same time")
    void everyTaskHasAnUnsharedPlace() {
        for (long day = 0; day < 3; day++) {
            for (long tick = 0; tick < 24000; tick += 250) {
                long time = day * 24000L + tick;
                SectSpots spots = new SectSpots();
                Set<String> taken = new HashSet<>();
                for (SectRoster m : SectRoster.ALL) {
                    Task t = taskOf(m, time);
                    SectPlaces.Pick p = SectPlaces.pick(FILE, m, t, time, spots, time, null);
                    if (p == null) {
                        if (INDOOR.contains(t.kind()) && !"gatekeeper".equals(m.key()) && !(t.kind() == Kind.RECEIVE && false)) {
                            // the only allowed holes: the gatekeeper lives outside, the stewards' evening is on the gate square
                            assertTrue(m.role() == SectRole.GATEKEEPER, m.key() + " has no place for " + t.kind() + " at " + time);
                        }
                        continue;
                    }
                    if (p.spotId().startsWith("zone:") || p.spotId().startsWith("store:")) {
                        continue;
                    }
                    SectLayoutFile.Spot s = FILE.spot(p.spotId().split("#")[0]);
                    if (s != null) {
                        assertEquals("ok", s.reach(), m.key() + " got an unreachable spot " + s.id());
                    } else {
                        assertNotNull(FILE.bed(p.spotId()), "unknown place " + p.spotId());
                    }
                    if (t.kind() != Kind.COUNCIL && t.kind() != Kind.RECEIVE) {
                        assertTrue(taken.add(p.spotId()), p.spotId() + " shared at " + time + " by " + m.key());
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("One junior tends the square each night; the post rotates alongside existing night routines")
    void squareWatchRotates() {
        Set<String> watches = new HashSet<>();
        var juniors = SectRoster.generation(3);
        for (int day = 0; day < juniors.size(); day++) {
            long time = day * (long) SectSchedule.DAY + 18000;
            String watch = SectNightWatch.member(time);
            assertTrue(watches.add(watch), "repeated square watch " + watch);
            for (int tick : new int[] {13000, 15000, 17500, 18000, 20500, 22500, 22999}) {
                long nightTime = day * (long) SectSchedule.DAY + tick;
                assertEquals(watch, SectNightWatch.member(nightTime), "watch changed during the night");
                int posts = 0;
                for (SectRoster junior : juniors) {
                    Task task = taskOf(junior, nightTime);
                    String context = junior.key() + " at " + nightTime;
                    if (junior.key().equals(watch)) {
                        assertEquals(Kind.WATCH, task.kind(), context);
                        assertEquals("training", task.zone(), context);
                    } else {
                        // SectStaff's peers-6 night windows predate the rotating square post.
                        Task awake = SectStaff.nightAwake(junior, nightTime);
                        if (awake == null) assertEquals(Kind.SLEEP, task.kind(), context);
                        else assertEquals(awake, task, context);
                    }
                    if (task.kind() == Kind.WATCH) posts++;
                }
                assertEquals(1, posts, "square posts at " + nightTime);
            }
        }
        assertEquals(juniors.size(), watches.size());
        assertEquals(SectNightWatch.MEMBER, SectNightWatch.member(18000));
    }

    @Test
    @DisplayName("Sleep: the 44 sleepers lie in 44 different beds; the same bed every night")
    void everybodyHasAnOwnBed() {
        Set<String> beds = new HashSet<>();
        for (SectRoster m : SectRoster.ALL) {
            String bed = null;
            // a night watch does not sleep that night: look at the nights of a week
            int nights = 0;
            for (int day = 0; day < 7; day++) {
                long time = 18000L + 24000L * day;
                Task sleep = SectPlaces.adjust(m, SectSchedule.task(m, SectPlaces.time(FILE, m, time)), time);
                if (sleep.kind() != Kind.SLEEP) {
                    continue;
                }
                SectPlaces.Pick a = SectPlaces.pick(FILE, m, sleep, time, new SectSpots(), 0, null);
                assertNotNull(a, m.key());
                assertNotNull(a.bed(), m.key() + " pick has no bed");
                if (bed != null) {
                    assertEquals(bed, a.spotId(), m.key() + " changes bed");
                }
                bed = a.spotId();
                nights++;
            }
            assertTrue(nights >= 4, m.key() + " sleeps only " + nights + " nights");
            assertTrue(beds.add(bed), m.key() + " shares bed " + bed);
        }
        assertEquals(44, beds.size());
    }

    @Test
    @DisplayName("The treasurer and the steward work in the treasury counting rooms; the house desks are the fallback when it is cut off")
    void treasuryFallback() {
        SectRoster gyun = SectRoster.of("tae_gyun").orElseThrow();
        Task count = new Task(Kind.COUNT, "treasury", 5.5D, 0.0D, 1.0D, 0.0D);
        SectPlaces.Pick p = SectPlaces.pick(FILE, gyun, count, 4000, new SectSpots(), 0, null);
        assertNotNull(p);
        assertTrue(p.room().startsWith("treasury.f"), p.room());
        SectRoster mun = SectRoster.of("steward_mun").orElseThrow();
        SectPlaces.Pick w = SectPlaces.pick(FILE, mun, new Task(Kind.WORK, "treasury", -9.5D, 0.0D, -1.0D, 0.0D), 4000, new SectSpots(), 0, null);
        assertNotNull(w);
        assertTrue(w.room().startsWith("treasury.f"), w.room());
    }

    @Test
    @DisplayName("Two dining waves: each wave fits the hall; the second keeps its duty through the first half of the meal")
    void diningWaves() {
        SectRoster young = SectRoster.generation(3).get(0);
        SectRoster older = SectRoster.generation(2).get(0);
        assertEquals(Kind.EAT, taskOf(older, 1100).kind());
        assertTrue(taskOf(young, 1100).kind() != Kind.EAT, "the young should still be in formation");
        assertEquals(Kind.EAT, taskOf(young, 1000 + 510).kind());
        assertTrue(taskOf(older, 1000 + 510).kind() != Kind.EAT, "the first wave has gone on to its next duty");
        for (long time : new long[] {1200, 1800, 9500, 10500}) {
            SectSpots spots = new SectSpots();
            Set<String> seats = new HashSet<>();
            int eaters = 0;
            for (SectRoster m : SectRoster.ALL) {
                Task t = taskOf(m, time);
                if (t.kind() != Kind.EAT) {
                    continue;
                }
                eaters++;
                SectPlaces.Pick p = SectPlaces.pick(FILE, m, t, time, spots, time, null);
                assertNotNull(p, m.key() + " finds no seat at " + time);
                assertTrue(seats.add(p.spotId()), "shared seat " + p.spotId());
            }
            assertTrue(eaters >= 10, "too few eaters at " + time + ": " + eaters);
        }
    }

    @Test
    @DisplayName("Spot reservation: a taken spot goes to the next free one; a released spot is free again")
    void reservations() {
        SectSpots spots = new SectSpots();
        assertTrue(spots.claim("a", "x", 0));
        assertFalse(spots.claim("a", "y", 10));
        assertTrue(spots.claim("a", "x", 10), "renew");
        assertTrue(spots.claim("b", "x", 20), "an owner holds one spot: a is freed");
        assertTrue(spots.claim("a", "y", 20));
        spots.release("y");
        assertTrue(spots.freeFor("a", "x", 21));
        assertNull(spots.holder("a", 20 + SectSpots.TTL + 1));
        // two people wanting the same seat list get two different seats
        var list = FILE.spots().stream().filter(s -> s.kind().equals("seat_bench") && "dining_hall".equals(s.building()) && "ok".equals(s.reach())).toList();
        SectSpots shared = new SectSpots();
        var a = SectPlaces.choose(list, "p1", "salt", shared, 0, null);
        var b = SectPlaces.choose(list, "p1", "salt", new SectSpots(), 0, null);
        assertEquals(a.id(), b.id(), "deterministic rotation");
        var c = SectPlaces.choose(list, "p2", "salt", shared, 0, null);
        assertTrue(c != null && !c.id().equals(a.id()));
        var again = SectPlaces.choose(list, "p1", "salt", shared, 50, null);
        assertEquals(a.id(), again.id(), "sticky");
        // banned spots are skipped
        Map<String, Long> bans = new HashMap<>();
        bans.put(a.id(), 100L);
        assertTrue(!SectPlaces.choose(list, "p1", "salt", new SectSpots(), 5, bans).id().equals(a.id()));
    }
}
