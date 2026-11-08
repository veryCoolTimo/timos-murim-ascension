package io.github.verycooltimo.murim.sect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Author 22.10: «у главного зала вообще нет охраны» - two guards stand at the doors of the main hall in the training hours. */
class SectHallDoorTest {

    @Test
    @DisplayName("Every training hour of every day exactly one first-generation disciple guards the hall door, besides the day guard")
    void secondGuardEveryDay() {
        for (long day = 0; day < 8; day++) {
            long t = day * 24000L + SectSchedule.Period.TRAINING.start() + 600L;
            List<SectRoster> at = SectRoster.firstRanks().stream().filter(m -> SectRota.atHallDoor(m, t)).toList();
            assertEquals(1, at.size(), "day " + day);
            SectSchedule.Task task = SectSchedule.task(at.get(0), t);
            assertEquals(SectSchedule.Kind.GUARD, task.kind());
            assertEquals("main_hall", task.zone());
            assertTrue(task.du() > 0, "the second guard stands on the other side of the first");
            // and he is not a guard in the other periods
            for (SectSchedule.Period p : new SectSchedule.Period[] {SectSchedule.Period.BREAKFAST, SectSchedule.Period.EVENING, SectSchedule.Period.NIGHT}) {
                assertTrue(!SectRota.atHallDoor(at.get(0), day * 24000L + p.start() + 100L), "guards in " + p);
            }
        }
    }

    @Test
    @DisplayName("The door guard is never the helper of the mentor or the one at the poles")
    void doesNotTakeOtherDuties() {
        for (long day = 0; day < 8; day++) {
            long t = day * 24000L + SectSchedule.Period.TRAINING.start() + 600L;
            for (SectRoster m : SectRoster.firstRanks()) {
                if (SectRota.atHallDoor(m, t)) {
                    assertEquals(3, SectSchedule.firstTurn(m, day));
                    assertTrue(!m.equals(SectSchedule.firstAssist(day)));
                }
            }
        }
    }

    @Test
    @DisplayName("Main hall: only offices pass, the first lesson and the lessons of a graduate do not")
    void hallShutBelowFirst() {
        SectAccess.Rule hall = SectAccess.RULES.stream().filter(r -> "main_hall".equals(r.id())).findFirst().orElseThrow();
        for (SectSchedule.Period p : SectSchedule.Period.values()) {
            assertTrue(!SectAccess.allowed(hall, SectStanding.DISCIPLE, java.util.Set.of(SectStanding.LESSON_ONE), p), "pupil " + p);
            assertTrue(!SectAccess.allowed(hall, SectStanding.TRUSTED, java.util.Set.of(SectStanding.LESSON_TWO, SectStanding.APPROVAL), p), "trusted " + p);
            assertTrue(!SectAccess.allowed(hall, SectStanding.OUTSIDER, java.util.Set.of(SectPositions.FIRST), p), "outsider " + p);
            assertTrue(SectAccess.allowed(hall, SectStanding.GRADUATE, java.util.Set.of(SectPositions.FIRST), p), "first " + p);
            assertTrue(SectAccess.allowed(hall, SectStanding.GRADUATE, java.util.Set.of(SectPositions.ELDER), p), "elder " + p);
            assertTrue(SectAccess.allowed(hall, SectStanding.NOVICE, java.util.Set.of(SectAccess.HEAD_ESCORT), p), "escort " + p);
        }
    }

    @Test
    @DisplayName("Main hall: in the working hours the head receives on the steps outside, not at his seat inside")
    void headReceivesOnTheSteps() {
        SectRoster head = SectRoster.of("tae_hwi").orElseThrow();
        for (long day = 0; day < 8; day++) {
            long t = day * 24000L + SectSchedule.Period.TRAINING.start() + 100L;
            SectSchedule.Task task = SectSchedule.task(head, SectSchedule.Period.TRAINING, day);
            assertEquals(SectSchedule.Kind.WATCH, task.kind());
            assertEquals(SectSchedule.HALL_STEPS, task.partner());
            assertTrue(task.dv() < 0, "outside, in front of the hall");
        }
        var steps = SectPlaces.hallSteps(SectLayoutFile.shipped());
        assertTrue(steps != null, "the layout has no hall posts");
    }
}
