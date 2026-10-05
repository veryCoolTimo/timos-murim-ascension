package io.github.verycooltimo.murim.training;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sets from the crouch key and the view only (no new keybinds). */
class SetMachineTest {

    private final SetMachine m = new SetMachine();
    private final List<SetMachine.Event> events = new ArrayList<>();
    private long tick = 100;

    private void run(int ticks, boolean crouch, float pitch, SetMachine.Hands hands) {
        for (int i = 0; i < ticks; i++) {
            events.addAll(m.step(new SetMachine.Input(tick++, crouch, pitch, true, true, hands, false, false)));
        }
    }

    /** One tap: crouch {@code down} ticks, then up {@code up} ticks. */
    private void tap(int down, int up, float pitch, SetMachine.Hands hands) {
        run(down, true, pitch, hands);
        run(up, false, pitch, hands);
    }

    private long count(SetMachine.Kind k) {
        return events.stream().filter(e -> e.kind() == k).count();
    }

    @Test
    @DisplayName("Два приседа в такт запускают приседания; дальше каждый присед — повтор")
    void squatsStartOnTwoTaps() {
        tap(4, 8, 0.0F, SetMachine.Hands.EMPTY);
        assertFalse(m.active(), "one tap is just sneaking");
        tap(4, 20, 0.0F, SetMachine.Hands.EMPTY);
        assertEquals(Exercise.SQUAT, m.exercise());
        for (int i = 0; i < 9; i++) {
            tap(4, 20, 0.0F, SetMachine.Hands.EMPTY);
        }
        assertEquals(10, m.reps());
        assertTrue(m.good() >= 9, "tapping every 24 ticks is on the beat: " + m.good());
        run(Exercise.SQUAT.beat() * (TrainingBalance.IDLE_BEATS + 1), false, 0.0F, SetMachine.Hands.EMPTY);
        assertFalse(m.active());
        assertEquals(1, count(SetMachine.Kind.END));
    }

    @Test
    @DisplayName("С блоками в руке присед у края — не тренировка")
    void handsFullNeverStart() {
        for (int i = 0; i < 6; i++) {
            tap(3, 6, 0.0F, SetMachine.Hands.OTHER);
        }
        assertFalse(m.active());
        assertEquals(0, count(SetMachine.Kind.START));
    }

    @Test
    @DisplayName("Не в такт — повтор засчитан хуже; дёргаться быстрее полутакта — только сбивать дыхание")
    void offBeatAndRushed() {
        tap(4, 8, 0.0F, SetMachine.Hands.EMPTY);
        tap(4, 3, 0.0F, SetMachine.Hands.EMPTY);
        tap(2, 4, 0.0F, SetMachine.Hands.EMPTY);
        assertEquals(1, count(SetMachine.Kind.RUSHED));
        assertEquals(TrainingBalance.QUALITY_GOOD, SetMachine.judge(0, 24, 24), 1e-9);
        assertEquals(TrainingBalance.QUALITY_FAIR, SetMachine.judge(0, 24, 29), 1e-9);
        assertEquals(TrainingBalance.QUALITY_OFF, SetMachine.judge(0, 24, 36), 1e-9);
    }

    @Test
    @DisplayName("Задержаться внизу — стойка всадника: секунда за секундой; встал — снова приседания")
    void horseStance() {
        tap(4, 8, 0.0F, SetMachine.Hands.EMPTY);
        run(TrainingBalance.HORSE_HOLD + 1, true, 0.0F, SetMachine.Hands.EMPTY);
        assertEquals(Exercise.HORSE_STANCE, m.exercise());
        run(100, true, 0.0F, SetMachine.Hands.EMPTY);
        assertTrue(count(SetMachine.Kind.HOLD) >= 4);
        run(1, false, 0.0F, SetMachine.Hands.EMPTY);
        assertEquals(Exercise.SQUAT, m.exercise());
    }

    @Test
    @DisplayName("Взгляд в землю и присед — планка; с плитой в руке — отжимания с грузом; поднял взгляд — встал")
    void pushups() {
        tap(4, 20, 70.0F, SetMachine.Hands.SLAB);
        assertEquals(Exercise.PUSHUP_WEIGHTED, m.exercise());
        assertEquals(0, m.reps(), "the first tap only lies down");
        for (int i = 0; i < 5; i++) {
            tap(5, Exercise.PUSHUP_WEIGHTED.beat() - 5, 70.0F, SetMachine.Hands.SLAB);
        }
        assertEquals(5, m.reps());
        run(12, false, 0.0F, SetMachine.Hands.SLAB);
        assertFalse(m.active());
        assertEquals(SetMachine.EndReason.STOOD_UP, events.get(events.size() - 1).reason());
    }

    @Test
    @DisplayName("Шаг с места обрывает подход; усталость не даёт начать новый")
    void movedAndExhausted() {
        tap(4, 8, 0.0F, SetMachine.Hands.EMPTY);
        tap(4, 8, 0.0F, SetMachine.Hands.EMPTY);
        assertTrue(m.active());
        events.addAll(m.step(new SetMachine.Input(tick++, false, 0.0F, false, true, SetMachine.Hands.EMPTY, false, false)));
        assertFalse(m.active());
        assertEquals(SetMachine.EndReason.MOVED, events.get(events.size() - 1).reason());
        SetMachine tired = new SetMachine();
        List<SetMachine.Event> out = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            out.addAll(tired.step(new SetMachine.Input(1000 + i, i % 8 < 4, 0.0F, true, true, SetMachine.Hands.EMPTY, false, true)));
        }
        assertNull(tired.exercise());
        assertTrue(out.stream().anyMatch(e -> e.kind() == SetMachine.Kind.EXHAUSTED));
    }

    @Test
    @DisplayName("Дыхание кончается при рывках — подход обрывается")
    void staminaRunsOut() {
        tap(4, 8, 0.0F, SetMachine.Hands.EMPTY);
        tap(4, 13, 0.0F, SetMachine.Hands.EMPTY);
        for (int i = 0; i < 40 && m.active(); i++) {
            tap(2, 3, 0.0F, SetMachine.Hands.EMPTY);
        }
        assertFalse(m.active());
        assertTrue(events.stream().anyMatch(e -> e.reason() == SetMachine.EndReason.SPENT));
    }
}
