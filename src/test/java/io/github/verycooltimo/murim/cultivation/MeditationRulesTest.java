package io.github.verycooltimo.murim.cultivation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Время сессии и окно удержания (docs/design/19-dantian-qi-meditation.md §3а). */
class MeditationRulesTest {

    @Test
    @DisplayName("Удержание засчитывается только в окне")
    void holdCountsOnlyInWindow() {
        MeditationState state = MeditationState.started(true).withHolding(true);
        for (int i = 0; i < MeditationService.SESSION_TICKS; i++) {
            state = state.tick(state.holding() && MeditationService.isRingWindow(state.ticks()));
        }
        assertEquals(MeditationService.RING_TO - MeditationService.RING_FROM, state.holdTicks());
        assertTrue(state.holdTicks() >= MeditationService.HOLD_REQUIRED,
                "удержание всего окна обязано проходить такт");
    }

    @Test
    @DisplayName("Требование к удержанию выполнимо без идеала")
    void holdRequirementIsForgiving() {
        int window = MeditationService.RING_TO - MeditationService.RING_FROM;
        assertTrue(MeditationService.HOLD_REQUIRED < window);
        assertFalse(MeditationService.isRingWindow(MeditationService.RING_TO));
        assertTrue(MeditationService.isRingWindow(MeditationService.RING_FROM));
    }

    @Test
    @DisplayName("Первая минута медитации выгоднее следующих")
    void gainDecays() {
        assertTrue(MeditationService.gainAt(0) > MeditationService.gainAt(1200));
        assertEquals(MeditationService.GAIN_START, MeditationService.gainAt(0), 1.0E-12D);
    }
}
