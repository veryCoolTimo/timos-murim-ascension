package io.github.verycooltimo.murim.training;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Body numbers: levels, the initial grant, daily limits, effects stay modest, breakthrough gates. */
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
    @DisplayName("Начальный зачёт: половина прежних ставок, потолок — порог ступени 5, ниже нуля не бывает")
    void initialGrant() {
        assertEquals(0.0D, BodyRules.initialGrant(0.0D), 1e-9);
        assertEquals(100.0D * TrainingBalance.INITIAL_SHARE, BodyRules.initialGrant(100.0D), 1e-9);
        double cap = BodyRules.threshold(TrainingBalance.INITIAL_CAP_LEVEL);
        assertEquals(cap, BodyRules.initialGrant(1.0E9D), 1e-9);
        assertEquals(TrainingBalance.INITIAL_CAP_LEVEL, BodyRules.level(BodyRules.initialGrant(1.0E9D)));
        assertTrue(TrainingBalance.INITIAL_CAP_LEVEL < TrainingBalance.MAX_LEVEL, "the cap is not the top of the ladder");
        assertTrue(TrainingBalance.INITIAL_CAP_LEVEL >= 3 && TrainingBalance.INITIAL_CAP_LEVEL <= 6, "mid-ladder");
    }

    @Test
    @DisplayName("Дневной предел вида работы: полная ставка до «full», дальше затухание, никогда не больше full + extra")
    void dayLimit() {
        for (BodySource s : BodySource.values()) {
            assertEquals(0.0D, s.dayTotal(0.0D), 1e-9, s.id());
            assertEquals(s.full() / 2.0D, s.dayTotal(s.full() / 2.0D), 1e-9, s.id());
            assertEquals(s.full(), s.dayTotal(s.full()), 1e-9, s.id());
            assertTrue(s.dayTotal(s.full() * 2.0D) < s.full() * 2.0D, s.id() + " must fall off");
            assertTrue(s.dayTotal(1.0E9D) <= s.dayMax() + 1e-9, s.id() + " above its limit");
            assertTrue(s.dayTotal(1.0E9D) > s.dayMax() - 1e-6, s.id() + " must approach its limit");
            double prev = 0.0D;
            for (double raw = 0.0D; raw < s.dayMax() * 5.0D; raw += s.dayMax() / 40.0D) {
                assertTrue(s.dayTotal(raw) >= prev - 1e-12, s.id() + " must not go down");
                prev = s.dayTotal(raw);
            }
        }
        assertTrue(BodySource.JUMP.dayMax() < BodySource.LEGS.dayMax() / 4.0D, "a held jump key is the cheapest trick: tiny limit");
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
    @DisplayName("Прорыв: тело — жёсткие ворота, ступени 1 / 2 / 4 / 6, подступени Пика 7 / 9")
    void breakthroughGate() {
        int[] need = {0, 1, 2, 4, 6};
        for (int r = 0; r < need.length; r++) {
            assertEquals(need[r], BodyRules.breakthroughNeed(r), "rank " + r);
            assertTrue(BodyRules.breakthroughReady(r, need[r]));
            if (need[r] > 0) {
                assertFalse(BodyRules.breakthroughReady(r, need[r] - 1), "rank " + r + " below the stage");
            }
        }
        assertEquals(7, BodyRules.stageNeed(1));
        assertEquals(9, BodyRules.stageNeed(2));
        assertFalse(BodyRules.stageReady(1, 6));
        assertTrue(BodyRules.stageReady(2, 9));
        // Fixed expectations for the accepted 40 × L^1.7 curve; doc 43 uses rough estimates.
        assertEquals(40.000000D, BodyRules.threshold(1), 1e-6D);
        assertEquals(129.960383D, BodyRules.threshold(2), 1e-6D);
        assertEquals(422.242531D, BodyRules.threshold(4), 1e-6D);
        assertEquals(841.234581D, BodyRules.threshold(6), 1e-6D);
        assertEquals(1093.268058D, BodyRules.threshold(7), 1e-6D);
        assertEquals(1675.993220D, BodyRules.threshold(9), 1e-6D);
    }

    @Test
    @DisplayName("Новый день обнуляет дневные счётчики, но не очки и не рекорды")
    void newDay() {
        BodyState s = new BodyState(BodyState.VERSION, 50.0D, 3L, java.util.Map.of("legs", 20.0D), java.util.Map.of("walk", 5L),
                true, 1200, 900, 2).onDay(4L);
        assertEquals(50.0D, s.points(), 1e-9);
        assertEquals(0.0D, s.today(), 1e-9);
        assertTrue(s.seeded() && s.seen().containsKey("walk"), "the baseline and the marker survive the new day");
        assertEquals(0, s.climbsToday());
        assertEquals(1200, s.bestClimb());
        assertEquals(1100, s.climbed(1100).bestClimb());
        assertEquals(1200, s.climbed(1300).bestClimb());
    }
}
