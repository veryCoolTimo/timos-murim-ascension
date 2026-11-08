package io.github.verycooltimo.murim.sect;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Round 4 schedule rules: the staff's cycles, tea breaks and the night (servants-1..6, peers-6). */
class SectStaffTest {
    private static SectRoster m(String key) { return SectRoster.of(key).orElseThrow(); }
    private static long at(long day, int tick) { return day * SectSchedule.DAY + tick; }
    private static SectSchedule.Kind kind(String key, long day, int tick) { return SectSchedule.task(m(key), at(day, tick)).kind(); }

    @Test
    void cookPreparesServesClearsAndRests() {
        assertEquals(SectSchedule.Kind.COOK, kind("cook_kim", 1, 500));      // prepares before breakfast
        assertEquals(SectSchedule.Kind.SERVE, kind("cook_kim", 1, 1500));
        assertEquals(SectSchedule.Kind.CHORE, kind("cook_kim", 1, 2100));    // clears the tables
        assertEquals(SectSchedule.Kind.WORK, kind("cook_kim", 1, 2500));     // tea, the storeroom
        assertEquals(SectSchedule.Kind.SERVE, kind("cook_kim", 1, 10000));   // a cold portion is still served in the late dinner
        assertEquals(SectSchedule.Kind.CHORE, kind("cook_kim", 1, 11100));
    }

    @Test
    void helpersEatOutsideOwnService() {
        assertEquals(SectSchedule.Kind.SERVE, kind("water_gu", 1, 1500));
        assertEquals(SectSchedule.Kind.EAT, kind("water_gu", 1, 2100));
    }

    @Test
    void deliveryStopsAfterBatchAndThePortersRest() {
        for (String key : new String[]{"porter_jang", "porter_oh"}) {
            assertEquals(SectSchedule.Kind.CARRY, kind(key, 1, 3000), key);
            assertNotEquals(SectSchedule.Kind.CARRY, kind(key, 1, 6600), key + " keeps carrying after the batch");
            assertEquals(SectSchedule.Kind.WORK, kind(key, 1, 5800), key);
        }
    }

    @Test
    void stewardCountsAfterFinalDeliveryAndMissingPorterDoesNotHoldHim() {
        int last = SectStaff.deliveryTo() + SectLessons.n("deliveryShift", 360);
        assertEquals(SectSchedule.Kind.WORK, kind("steward_mun", 1, last + 10));
        assertEquals("sect_gate", SectSchedule.task(m("steward_mun"), at(1, last + 10)).zone());
        assertNotEquals("sect_gate", SectSchedule.task(m("steward_mun"), at(1, last + 1500)).zone(), "the count has a deadline");
    }

    @Test
    void waterTripsStartAtTheWellAndStopWhenTheKitchenHasEnough() {
        assertEquals(SectSchedule.Kind.CARRY, kind("water_gu", 1, 8000));    // before dinner
        assertEquals(SectSchedule.Kind.CARRY, kind("water_gu", 1, 2600));    // one trip to the alchemy pavilion
        assertNotEquals(SectSchedule.Kind.CARRY, kind("water_gu", 1, 5000)); // then rest / help in the kitchen
    }

    @Test
    void herbalistCyclesThroughGatherBasketHandoffAndDry() {
        Set<SectSchedule.Kind> seen = new HashSet<>();
        for (int t = 2000; t < 3600; t += 100) seen.add(kind("herbalist_han", 1, t));
        assertTrue(seen.containsAll(Set.of(SectSchedule.Kind.TEND, SectSchedule.Kind.CARRY, SectSchedule.Kind.WORK)), seen.toString());
    }

    @Test
    void sweeperVisitsDiningAfterMeal() {
        assertEquals("dining", SectSchedule.task(m("sweeper_ma"), at(1, 2100)).zone());
        assertEquals("dining", SectSchedule.task(m("sweeper_ma"), at(1, 11100)).zone());
        assertEquals("sect_gate", SectSchedule.task(m("sweeper_ma"), at(1, 23500)).zone());
    }

    @Test
    void servantsBreakAtDifferentTimes() {
        Set<Integer> starts = new HashSet<>();
        for (String key : new String[]{"steward_mun", "porter_jang", "porter_oh", "sweeper_ma", "water_gu"}) {
            SectSchedule.Task before = SectSchedule.task(m(key), at(1, 5990));
            for (int t = 6000; t < 7500; t += 10) {
                SectSchedule.Task task = SectSchedule.task(m(key), at(1, t));
                if (!task.equals(before)) { starts.add(t); break; }
            }
        }
        assertTrue(starts.size() >= 3, "tea breaks are not spread: " + starts);
    }

    @Test
    void servantsStillSleepSeated() {
        for (SectRoster r : SectRoster.ALL) {
            if (!r.lay()) continue;
            assertEquals(SectSchedule.Kind.SLEEP, SectSchedule.task(r, at(2, 13500)).kind(), r.key());
        }
    }

    @Test
    void nightHasOneToThreeAwakeCivilians() {
        for (long day = 0; day < 7; day++) {
            for (int t = 13500; t < 22900; t += 500) {
                int awake = 0;
                for (SectRoster r : SectRoster.ALL) {
                    if (r.role() != SectRole.DISCIPLE && !r.lay()) continue;
                    if (SectSchedule.task(r, at(day, t)).kind() != SectSchedule.Kind.SLEEP) awake++;
                }
                assertTrue(awake <= 3, "too many awake at " + day + ":" + t + " = " + awake);
            }
        }
        boolean someone = false;
        for (int t = 14500; t < 17000 && !someone; t += 100) for (SectRoster r : SectRoster.ALL)
            if ((r.role() == SectRole.DISCIPLE || r.lay()) && SectSchedule.task(r, at(1, t)).kind() != SectSchedule.Kind.SLEEP) someone = true;
        assertTrue(someone, "the night is dead");
    }

    @Test
    void nightDutyRosterIsExcluded() {
        for (long day = 0; day < 7; day++) for (int t = 14000; t < 22000; t += 500)
            for (SectRoster r : SectRoster.ALL) {
                if (SectRota.duty(r, at(day, t)).isPresent()) {
                    assertNotEquals(SectSchedule.Kind.DRILL, SectSchedule.task(r, at(day, t)).kind(), r.key());
                }
            }
    }
}
