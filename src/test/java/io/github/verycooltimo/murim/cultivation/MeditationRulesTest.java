package io.github.verycooltimo.murim.cultivation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Мини-игра кольца и медитация после семени (docs/design/19, решение автора 30.09). */
class MeditationRulesTest {

    /** Играет, как внимательный игрок: держит, когда кольцо шире центра полосы. */
    private static RingMinigame play(int beat, long seed, java.util.function.BiPredicate<RingMinigame, Integer> hold) {
        Random random = new Random(seed);
        RingMinigame game = RingMinigame.start(beat, beat == 2 ? 0.55D : 0.72D);
        for (int i = 0; i < 6000 && game.result() == RingMinigame.Result.PLAYING; i++) {
            game = game.step(hold.test(game, beat), beat, random::nextDouble);
        }
        return game;
    }

    private static boolean attentive(RingMinigame game, int beat) {
        return game.radius() > game.bandCentre();
    }

    @Test
    @DisplayName("Внимательный игрок проходит оба такта мини-игры")
    void attentivePasses() {
        for (long seed = 0; seed < 20; seed++) {
            assertEquals(RingMinigame.Result.PASSED, play(1, seed, MeditationRulesTest::attentive).result(), "удержание, seed " + seed);
            assertEquals(RingMinigame.Result.PASSED, play(2, seed, MeditationRulesTest::attentive).result(), "сжатие, seed " + seed);
        }
    }

    @Test
    @DisplayName("Бездействие и зажатая наглухо клавиша ведут к искажению ци")
    void idleAndMashingFail() {
        assertEquals(RingMinigame.Result.BACKLASH, play(1, 1, (g, b) -> false).result(), "отпустил — рассыпается");
        assertEquals(RingMinigame.Result.BACKLASH, play(1, 1, (g, b) -> true).result(), "перетянул — трещит");
        assertEquals(RingMinigame.Result.BACKLASH, play(2, 1, (g, b) -> false).result());
    }

    @Test
    @DisplayName("Первые секунды напряжение не копится")
    void graceAtStart() {
        RingMinigame game = RingMinigame.start();
        for (int i = 0; i < RingMinigame.GRACE_TICKS - 1; i++) {
            game = game.step(false, 1, () -> 0.99D);
        }
        assertEquals(0.0D, game.strain(), 1.0E-12D);
    }

    @Test
    @DisplayName("Такт сжатия уводит полосу к центру")
    void seedBandContracts() {
        RingMinigame first = play(1, 8, MeditationRulesTest::attentive);
        MeditationState next = MeditationState.started(true, 1).tick(first).nextBeat(2);
        assertEquals(first.radius(), next.ring().radius());
        assertEquals(first.radius(), next.ring().bandCentre());
        assertEquals(0.12D, next.ring().bandHalfWidth());
        RingMinigame end = new RingMinigame(first.radius(), 0, 0, 0, 200, 2, first.radius());
        assertEquals(0.10D, end.bandCentre(), 1.0E-12D);
        assertEquals(0.06D, end.bandHalfWidth(), 1.0E-12D);
        assertEquals(0.55D, MeditationState.started(true, 2).ring().radius());
    }

    @Test
    void fullStabilityAtLargeRadiusCannotPass() {
        RingMinigame game = new RingMinigame(0.40D, 0.999D, 0, 0, 100, 2, 0.72D);
        RingMinigame next = game.step(true, 2, () -> 0.99D);
        assertEquals(1.0D, next.stability());
        assertEquals(0.0D, next.miss(2));
        assertTrue(next.radius() > 0.16D);
        assertEquals(RingMinigame.Result.PLAYING, next.result());
    }

    @Test
    void controllerTimingAndTerminalTicks() {
        for (long seed = 0; seed < 20; seed++) {
            RingMinigame first = play(1, seed, MeditationRulesTest::attentive);
            assertTrue(first.ticks() >= 200 && first.ticks() <= 320, "round 1 seed " + seed);
            RingMinigame second = RingMinigame.start(2, first.radius());
            Random random = new Random(seed);
            while (second.result() == RingMinigame.Result.PLAYING && second.ticks() < 400) {
                second = second.step(attentive(second, 2), 2, random::nextDouble);
            }
            assertEquals(RingMinigame.Result.PASSED, second.result(), "round 2 seed " + seed);
            assertTrue(second.ticks() >= 240 && second.ticks() <= 400, "round 2 seed " + seed);
            assertEquals(second, second.step(true, 2, random::nextDouble));
            for (int beat = 1; beat <= 2; beat++) {
                assertEquals(RingMinigame.Result.BACKLASH, play(beat, seed, (g, b) -> true).result());
                assertEquals(RingMinigame.Result.BACKLASH, play(beat, seed, (g, b) -> false).result());
            }
        }
    }

    @Test
    @DisplayName("Затухания нет: приток один и тот же в любую секунду сессии, вставать и садиться незачем")
    void gainDoesNotDecay() {
        assertEquals(MeditationService.GAIN_PER_TICK, MeditationService.gainPerTick(), 1.0E-12D);
        // Сумма за N тиков не зависит от того, одной сессией её набрали или десятком.
        double oneSession = MeditationService.gainPerTick() * 12000;
        double tenSessions = 10 * MeditationService.gainPerTick() * 1200;
        assertEquals(oneSession, tenSessions, 1.0E-9D);
        assertEquals(24.0D, MeditationService.gainPerTick() * 1200, 1.0E-9D, "24 ци в минуту при эффективности 1");
    }

    @Test
    @DisplayName("Эхо обрабатывает резерв за 30 секунд")
    void echoTakesThirtySeconds() {
        assertEquals(600, MeditationService.ECHO_TICKS);
        MeditationState sitting = MeditationState.started(true, 3);
        assertEquals(false, sitting.echoing(), "по умолчанию — Очищение");
        MeditationState echo = sitting.withEcho(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "seven_plum"));
        assertEquals(true, echo.echoing());
        MeditationState later = echo;
        for (int i = 0; i < MeditationService.ECHO_TICKS; i++) {
            later = later.tick(null);
        }
        assertEquals(MeditationService.ECHO_TICKS, later.echoTicks());
        assertEquals(0, sitting.tick(null).echoTicks(), "Очищение Эха не считает");
        assertEquals(0, echo.withEcho(null).tick(null).echoTicks());
    }
}
