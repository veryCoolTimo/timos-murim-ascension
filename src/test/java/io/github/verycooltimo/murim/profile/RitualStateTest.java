package io.github.verycooltimo.murim.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Состояние ритуала: счётчики кругов и напряжения. */
class RitualStateTest {

    @Test
    @DisplayName("Круг закрывается: тик обнуляется, круг засчитан, набранное сохраняется")
    void cycleResetsTickKeepsGathered() {
        RitualState state = RitualState.started();
        for (int i = 0; i < RitualState.CYCLE_TICKS; i++) {
            state = state.advanced(1.0D, 0.01D);
        }
        RitualState closed = state.cycleDone();
        assertEquals(0, closed.tick());
        assertEquals(1, closed.cycles());
        assertEquals(state.gathered(), closed.gathered(), 1.0E-9D,
                "набранное не должно теряться при закрытии круга");
    }

    @Test
    @DisplayName("Прогресс круга не превышает единицы даже при перетике")
    void progressClamped() {
        RitualState state = RitualState.started();
        for (int i = 0; i < RitualState.CYCLE_TICKS * 3; i++) {
            state = state.advanced(0.0D, 0.0D);
        }
        assertEquals(1.0F, state.cycleProgress(), 1.0E-6F);
    }

    @Test
    @DisplayName("Остановка гасит ритуал полностью")
    void stopClears() {
        RitualState state = RitualState.started().advanced(5.0D, 0.5D).cycleDone();
        assertFalse(state.stopped().active());
        assertEquals(0, state.stopped().cycles());
    }

    @Test
    @DisplayName("Отрицательные значения отвергаются")
    void invalidRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RitualState(true, -1, 0, 0.0D, 0.0D));
        assertThrows(IllegalArgumentException.class,
                () -> new RitualState(true, 0, 0, Double.NaN, 0.0D));
    }

    @Test
    @DisplayName("Напряжение копится только после безопасных кругов — иначе риска нет вовсе")
    void strainAccumulates() {
        RitualState state = RitualState.started().advanced(1.0D, 0.0D);
        assertEquals(0.0D, state.strain(), 1.0E-9D);
        assertTrue(state.advanced(1.0D, 0.2D).strain() > 0.0D);
    }
}
