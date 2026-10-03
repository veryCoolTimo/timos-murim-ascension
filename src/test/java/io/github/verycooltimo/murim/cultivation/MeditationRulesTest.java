package io.github.verycooltimo.murim.cultivation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Мини-игра кольца и медитация после семени (docs/design/19, решение автора 30.09). */
class MeditationRulesTest {

    /** Играет, как внимательный игрок: держит, когда кольцо шире центра полосы. */
    private static RingMinigame.Result play(int beat, long seed, java.util.function.BiPredicate<RingMinigame, Integer> hold) {
        Random random = new Random(seed);
        RingMinigame game = RingMinigame.start();
        for (int i = 0; i < 6000 && game.result() == RingMinigame.Result.PLAYING; i++) {
            game = game.step(hold.test(game, beat), beat, random::nextDouble);
        }
        return game.result();
    }

    private static boolean attentive(RingMinigame game, int beat) {
        return game.radius() > RingMinigame.bandCentre(beat, game.ticks());
    }

    @Test
    @DisplayName("Внимательный игрок проходит оба такта мини-игры")
    void attentivePasses() {
        for (long seed = 0; seed < 20; seed++) {
            assertEquals(RingMinigame.Result.PASSED, play(1, seed, MeditationRulesTest::attentive), "удержание, seed " + seed);
            assertEquals(RingMinigame.Result.PASSED, play(2, seed, MeditationRulesTest::attentive), "сжатие, seed " + seed);
        }
    }

    @Test
    @DisplayName("Бездействие и зажатая наглухо клавиша ведут к искажению ци")
    void idleAndMashingFail() {
        assertEquals(RingMinigame.Result.BACKLASH, play(1, 1, (g, b) -> false), "отпустил — рассыпается");
        assertEquals(RingMinigame.Result.BACKLASH, play(1, 1, (g, b) -> true), "перетянул — трещит");
        assertEquals(RingMinigame.Result.BACKLASH, play(2, 1, (g, b) -> false));
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
        assertTrue(RingMinigame.bandCentre(2, 500) < 0.2D);
        assertTrue(RingMinigame.bandHalfWidth(2, 500) < RingMinigame.bandHalfWidth(1, 500));
    }

    @Test
    @DisplayName("Первая минута медитации выгоднее следующих")
    void gainDecays() {
        assertTrue(MeditationService.gainAt(0) > MeditationService.gainAt(1200));
        assertEquals(MeditationService.GAIN_START, MeditationService.gainAt(0), 1.0E-12D);
    }
}
