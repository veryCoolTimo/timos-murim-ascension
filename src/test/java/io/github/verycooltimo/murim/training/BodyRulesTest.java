package io.github.verycooltimo.murim.training;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Body tempering numbers: levels, diminishing returns, fatigue, recovery, effects stay modest. */
class BodyRulesTest {

    @Test
    @DisplayName("Ступени: пороги растут, 0 очков — ступень 0, потолок — MAX_LEVEL")
    void levels() {
        assertEquals(0, BodyRules.level(0.0D));
        assertEquals(1, BodyRules.level(BodyRules.threshold(1)));
        assertEquals(0, BodyRules.level(BodyRules.threshold(1) - 0.01D));
        assertEquals(TrainingBalance.MAX_LEVEL, BodyRules.level(1.0E9D));
        for (int l = 1; l < TrainingBalance.MAX_LEVEL; l++) {
            assertTrue(BodyRules.threshold(l + 1) - BodyRules.threshold(l) > BodyRules.threshold(l) - BodyRules.threshold(l - 1),
                    "each level costs more than the previous: " + l);
        }
        assertEquals(1.0D, BodyRules.progress(1.0E9D), 1e-9);
        assertEquals(0.5D, BodyRules.progress((BodyRules.threshold(1) + BodyRules.threshold(2)) / 2.0D), 1e-9);
    }

    @Test
    @DisplayName("Убывающая отдача за день: 200 приседаний в один день дают меньше, чем по 50 четыре дня")
    void dailyDiminishing() {
        BodyState oneDay = BodyState.NONE;
        for (int i = 0; i < 200; i++) {
            oneDay = BodyRules.apply(Exercise.SQUAT, TrainingBalance.QUALITY_GOOD, false, oneDay).withFatigue(0.0D);
        }
        BodyState fourDays = BodyState.NONE;
        for (int d = 1; d <= 4; d++) {
            fourDays = fourDays.onDay(d);
            for (int i = 0; i < 50; i++) {
                fourDays = BodyRules.apply(Exercise.SQUAT, TrainingBalance.QUALITY_GOOD, false, fourDays).withFatigue(0.0D);
            }
        }
        assertTrue(oneDay.points() < fourDays.points() * 0.75D, oneDay.points() + " vs " + fourDays.points());
        assertTrue(oneDay.points() > 80.0D && oneDay.points() < 160.0D, "a big day ≈ 130: " + oneDay.points());
        assertEquals(0.5D, BodyRules.dailyFactor(TrainingBalance.DAILY_SOFT), 1e-9);
    }

    @Test
    @DisplayName("Усталость: до мягкого порога не мешает, на пределе — пол; секта и такт дают больше")
    void fatigueAndQuality() {
        assertEquals(1.0D, BodyRules.fatigueFactor(0.5D), 1e-9);
        assertEquals(TrainingBalance.FATIGUE_FLOOR, BodyRules.fatigueFactor(1.0D), 1e-9);
        BodyState fresh = BodyState.NONE;
        double good = BodyRules.gain(Exercise.PUSHUP, TrainingBalance.QUALITY_GOOD, false, fresh);
        double off = BodyRules.gain(Exercise.PUSHUP, TrainingBalance.QUALITY_OFF, false, fresh);
        double sect = BodyRules.gain(Exercise.PUSHUP, TrainingBalance.QUALITY_GOOD, true, fresh);
        assertTrue(off < good && good < sect);
        assertEquals(TrainingBalance.SECT_BONUS, sect / good, 1e-9);
        assertTrue(BodyRules.gain(Exercise.PUSHUP_WEIGHTED, 1.0D, false, fresh) > good, "the slab pays more");
        BodyState after = BodyRules.apply(Exercise.PUSHUP_WEIGHTED, 1.0D, false, fresh);
        assertEquals(Exercise.PUSHUP_WEIGHTED.fatigue(), after.fatigue(), 1e-9);
    }

    @Test
    @DisplayName("Восстановление: еда и сон в секте быстрее, сон в секте снимает всё")
    void recovery() {
        assertTrue(BodyRules.afterMeal(0.6D, 5, true) < BodyRules.afterMeal(0.6D, 5, false));
        assertEquals(0.0D, BodyRules.afterMeal(0.1D, 8, false), 1e-9);
        assertEquals(0.0D, BodyRules.afterSleep(0.9D, true), 1e-9);
        assertEquals(0.9D * TrainingBalance.SLEEP_ELSEWHERE, BodyRules.afterSleep(0.9D, false), 1e-9);
        assertTrue(BodyRules.recoveryPerTick(true) > BodyRules.recoveryPerTick(false));
        // A full bar recovers by itself in under an hour of play.
        assertTrue(1.0D / BodyRules.recoveryPerTick(false) < 20 * 60 * 60);
    }

    @Test
    @DisplayName("Эффекты скромные: вся лестница меньше одного ранга; груз легче с закалкой")
    void effectsAreModest() {
        assertTrue(BodyRules.health(TrainingBalance.MAX_LEVEL) <= io.github.verycooltimo.murim.cultivation.Realm.HEALTH_PER_RANK);
        assertTrue(BodyRules.knockback(TrainingBalance.MAX_LEVEL) <= 0.25D);
        assertTrue(BodyRules.footworkStamina(TrainingBalance.MAX_LEVEL) <= 1.35D);
        assertEquals(1.0D, BodyRules.footworkStamina(0), 1e-9);
        assertTrue(BodyRules.carrySlow(10) > BodyRules.carrySlow(0), "less slow at level 10");
        assertTrue(BodyRules.carrySlow(10) < 0.0D);
    }

    @Test
    @DisplayName("Прорыв: пока баланс не задал ступени, тело его не держит")
    void breakthroughGateOff() {
        for (int r = 0; r <= 5; r++) {
            assertTrue(BodyRules.breakthroughReady(r, 0));
        }
    }

    @Test
    @DisplayName("Новый день обнуляет дневные счётчики, но не очки и не рекорды")
    void newDay() {
        BodyState s = new BodyState(BodyState.VERSION, 50.0D, 3L, 20.0D, 0.4D, 1200, 900, 2).onDay(4L);
        assertEquals(50.0D, s.points(), 1e-9);
        assertEquals(0.0D, s.today(), 1e-9);
        assertEquals(0, s.climbsToday());
        assertEquals(1200, s.bestClimb());
        assertEquals(1100, s.climbed(1100).bestClimb());
        assertEquals(1200, s.climbed(1300).bestClimb());
    }
}
