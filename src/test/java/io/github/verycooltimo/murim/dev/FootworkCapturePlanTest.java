package io.github.verycooltimo.murim.dev;

import io.github.verycooltimo.murim.combat.FootworkService;
import org.junit.jupiter.api.Test;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class FootworkCapturePlanTest {
    @Test void duelCuesIncludeOneDelayedMeleeContact() {
        var cues = FootworkCapturePlan.sequence(FootworkCapturePlan.DEFAULT_DUEL);
        assertEquals(java.util.List.of(
                new FootworkCapturePlan.Cue("enemy_swing", 20),
                new FootworkCapturePlan.Cue("enemy_hit", 28),
                new FootworkCapturePlan.Cue("seven_stars_shift", 25),
                new FootworkCapturePlan.Cue("six_harmonies_strike", 36),
                new FootworkCapturePlan.Cue("seven_stars_follow", 60),
                new FootworkCapturePlan.Cue("falling_petal", 63)), cues);
        assertEquals(1, cues.stream().filter(c -> c.action().equals("enemy_hit")).count());
        assertEquals(java.util.List.of(new FootworkCapturePlan.Cue("enemy_swing", 7),
                new FootworkCapturePlan.Cue("enemy_hit", 15)), FootworkCapturePlan.sequence("enemy_swing@7"));
        assertThrows(UnsupportedOperationException.class, () -> cues.clear());
    }

    @Test void chaseFilmsRetreatBeforePursuitAndDipperHasOwnCue() {
        assertEquals(java.util.List.of(new FootworkCapturePlan.Cue("enemy_retreat", 20),
                new FootworkCapturePlan.Cue("seven_stars_follow", 25),
                new FootworkCapturePlan.Cue("falling_petal", 31)),
                FootworkCapturePlan.sequence(FootworkCapturePlan.CHASING_DUEL));
        assertEquals(java.util.List.of(new FootworkCapturePlan.Cue("seven_stars_dipper", 20)),
                FootworkCapturePlan.sequence(FootworkCapturePlan.DIPPER_DUEL));
    }

    @Test void strictDuelInputsRejectMistakesBeforeFilming() {
        for (String script : new String[] {"typo@20", "enemy_hit@28", "falling_petal@-1",
                "enemy_swing@", "enemy_swing@20@", "enemy_swing@20,", "enemy_swing", "enemy_swing@2147483647"}) {
            assertThrows(RuntimeException.class, () -> FootworkCapturePlan.sequence(script), script);
        }
        for (String enemy : new String[] {"bandit", "vindicator", "zombie"})
            assertEquals(enemy, FootworkCapturePlan.enemy(enemy));
        assertThrows(IllegalArgumentException.class, () -> FootworkCapturePlan.enemy("vindictor"));
    }
    @Test void rUsesDirectionWithoutPassiveSprint() {
        assertEquals(FootworkService.LEFT, FootworkCapturePlan.input("left"));
        assertEquals(FootworkService.RIGHT, FootworkCapturePlan.input("right"));
        assertEquals(FootworkService.BACK, FootworkCapturePlan.input("back"));
        assertEquals(FootworkService.FORWARD, FootworkCapturePlan.input("pass"));
        assertEquals(FootworkService.SPRINT | FootworkService.FORWARD, FootworkCapturePlan.input("run"));
        for (String mode : new String[] {"left", "right", "back", "pass", "plain", "death", "shadow", "run"}) {
            assertEquals(0, FootworkCapturePlan.input(mode) & FootworkService.PASSIVE);
        }
        assertEquals(0, FootworkCapturePlan.input("plain"));
        assertEquals(0, FootworkCapturePlan.input("shadow"));
    }

    @Test void forwardFlashSelectsTheEvadeFormRatherThanTheSprintForm() {
        assertEquals("wind_god_steps", FootworkCapturePlan.subject("wind_god_lightning", "pass"));
        assertEquals("wind_god_lightning", FootworkCapturePlan.subject("wind_god_lightning", "run"));
        assertEquals("seven_stars_follow", FootworkCapturePlan.subject("seven_stars_follow", "pass"));
        assertEquals("seven_stars_shift", FootworkCapturePlan.subject("seven_stars_shift", "left"));
    }

    @Test void repeatClockStartsAfterWarmupAndIncludesSeveralUses() {
        assertArrayEquals(new int[] {0, 40, 80},
                IntStream.range(-60, 100).filter(t -> FootworkCapturePlan.due("left", t, 40)).toArray());
        assertArrayEquals(new int[] {0, 25, 50, 75},
                IntStream.range(0, 100).filter(t -> FootworkCapturePlan.due("pass", t, 25)).toArray());
        assertTrue(FootworkCapturePlan.due("pass", 1, 0));
        assertTrue(FootworkCapturePlan.due("pass", 1, -40));
        for (String mode : new String[] {"run", "shadow", "shadowrun"}) {
            assertTrue(FootworkCapturePlan.due(mode, 0, 40));
            assertFalse(FootworkCapturePlan.due(mode, 40, 40), "repeat R would turn off " + mode);
        }
        for (String mode : new String[] {"", "autorun", "autoshadow"}) {
            assertFalse(FootworkCapturePlan.due(mode, 0, 40));
            assertFalse(FootworkCapturePlan.due(mode, 40, 40));
        }
    }
}
