package io.github.verycooltimo.murim.cultivation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Правила пилюль 25.10: фиксированное ци, ранг, нагрузка, состав, привыкание, окно (author's numbers). */
class PillRulesTest {

    @Test
    @DisplayName("Ци пилюль фиксировано: обычные 25-50, средние 200-500, высшие 1500-3500, у каждой свой ранг")
    void theTableOfThePills() {
        for (PillKind k : PillKind.values()) {
            if (k.utility()) {
                assertEquals(0, k.qi(), k + " is a utility pill");
                continue;
            }
            assertNotNull(k.flow(), k + " has no stream");
            assertTrue(k.rank() >= Realm.THIRD && k.rank() <= Realm.TRANSCENDENT, k + " rank " + k.rank());
            int qi = k.qi();
            boolean common = qi >= 25 && qi <= 50;
            boolean middle = qi >= 200 && qi <= 500;
            boolean high = qi >= 1500 && qi <= 3500;
            assertTrue(common || middle || high, k + " carries " + qi + " qi, none of the three bands");
        }
        assertEquals(35, PillKind.VITALITY.qi());
        assertEquals(250, PillKind.SNOW_PLUM.qi());
        assertEquals(1500, PillKind.ORIGIN_ENERGY.qi());
        assertEquals(1800, PillKind.THOUSAND_POISON.qi());
        assertEquals(2200, PillKind.BLUE.qi());
        assertEquals(2800, PillKind.BLOOD.qi());
        assertEquals(3500, PillKind.BEAUTY_TEAR.qi());
        assertEquals(Realm.THIRD, PillKind.VITALITY.rank());
        assertEquals(Realm.SECOND, PillKind.SNOW_PLUM.rank());
        assertEquals(Realm.PEAK, PillKind.ORIGIN_ENERGY.rank());
        assertEquals(Realm.PEAK, PillKind.THOUSAND_POISON.rank());
        assertEquals(Realm.TRANSCENDENT, PillKind.BLUE.rank());
        assertEquals(Realm.TRANSCENDENT, PillKind.BLOOD.rank());
        assertEquals(Realm.TRANSCENDENT, PillKind.BEAUTY_TEAR.rank());
        for (PillKind herb : new PillKind[] {PillKind.THOUSAND_GINSENG, PillKind.THOUSAND_LINGZHI}) {
            assertTrue(herb.qi() >= 300 && herb.qi() <= 350 && herb.rank() == Realm.SECOND && herb.herb());
        }
    }

    @Test
    @DisplayName("Правило ранга: на 2 ниже разрывает, на 1 ниже x0,5, свой x1, на 1 выше x1,1, на 2 и выше x1,2")
    void theRankRule() {
        assertEquals(0.0D, PillRules.mult(-2));
        assertEquals(0.0D, PillRules.mult(-4));
        assertEquals(0.5D, PillRules.mult(-1));
        assertEquals(1.0D, PillRules.mult(0));
        assertEquals(1.1D, PillRules.mult(1));
        assertEquals(1.2D, PillRules.mult(2));
        assertEquals(1.2D, PillRules.mult(5));
        assertEquals(-3, PillRules.delta(Realm.THIRD, PillKind.ORIGIN_ENERGY));
    }

    @Test
    @DisplayName("План: нагрузка - сумма ци на правило ранга; рвёт пилюля на 2 выше или нагрузка больше полутора стен")
    void thePlan() {
        PillRules.Plan own = PillRules.plan(List.of(PillKind.SNOW_PLUM), Realm.SECOND, 777.0D, 1.0D);
        assertEquals(250.0D, own.load(), 1.0E-9D);
        assertFalse(own.torn() || own.hard());
        PillRules.Plan hard = PillRules.plan(List.of(PillKind.SNOW_PLUM), Realm.THIRD, 432.0D, 1.0D);
        assertEquals(125.0D, hard.load(), 1.0E-9D);
        assertTrue(hard.hard() && !hard.torn());
        PillRules.Plan tooHigh = PillRules.plan(List.of(PillKind.ORIGIN_ENERGY), Realm.THIRD, 432.0D, 1.0D);
        assertTrue(tooHigh.torn() && tooHigh.tooHigh() && !tooHigh.overload());
        // the load: a big body bears 1.5 walls and not a qi more
        PillRules.Plan edge = PillRules.plan(List.of(PillKind.ORIGIN_ENERGY, PillKind.THOUSAND_POISON), Realm.PEAK, 2200.0D, 1.0D);
        assertFalse(edge.torn(), "3300 is exactly 1.5 walls of 2200");
        PillRules.Plan over = PillRules.plan(List.of(PillKind.ORIGIN_ENERGY, PillKind.THOUSAND_POISON), Realm.PEAK, 2199.0D, 1.0D);
        assertTrue(over.torn() && over.overload() && !over.tooHigh());
        // the load counts every pill with its own rank rule
        PillRules.Plan mixed = PillRules.plan(List.of(PillKind.VITALITY, PillKind.SNOW_PLUM), Realm.FIRST, 1.0E6D, 1.0D);
        assertEquals(35 * 1.2D + 250 * 1.1D, mixed.load(), 1.0E-9D);
        // the tolerance weakens the load
        assertEquals(125.0D, PillRules.plan(List.of(PillKind.SNOW_PLUM), Realm.SECOND, 777.0D, 0.5D).load(), 1.0E-9D);
    }

    @Test
    @DisplayName("Состав: чистое усвоение (от 80 %) даёт +10 % за каждую лишнюю пилюлю")
    void theComboBonus() {
        PillRules.Plan one = PillRules.plan(List.of(PillKind.SNOW_PLUM), Realm.SECOND, 1.0E6D, 1.0D);
        assertEquals(250.0D, one.gain(1.0D), 1.0E-9D);
        PillRules.Plan two = PillRules.plan(List.of(PillKind.SNOW_PLUM, PillKind.THOUSAND_GINSENG), Realm.SECOND, 1.0E6D, 1.0D);
        assertEquals(550.0D * 1.1D, two.gain(1.0D), 1.0E-9D);
        assertEquals(550.0D * 0.8D * 1.1D, two.gain(0.8D), 1.0E-9D);
        assertEquals(550.0D * 0.79D, two.gain(0.79D), 1.0E-9D, "under 80 % there is no bonus");
        PillRules.Plan three = PillRules.plan(List.of(PillKind.SNOW_PLUM, PillKind.THOUSAND_GINSENG, PillKind.THOUSAND_LINGZHI),
                Realm.SECOND, 1.0E6D, 1.0D);
        assertEquals(900.0D * 1.2D, three.gain(1.0D), 1.0E-9D);
    }

    @Test
    @DisplayName("Привыкание: x0,75 с пределом x0,25, виды независимы, ступень за 6000 тиков")
    void theTolerance() {
        PillState none = PillState.NONE;
        List<PillKind> snow = List.of(PillKind.SNOW_PLUM);
        assertEquals(1.0D, PillRules.tolerance(none, snow, 100L));
        PillState once = none.afterIntake(snow, 100L);
        assertEquals(0.75D, PillRules.tolerance(once, snow, 200L));
        PillState twice = once.afterIntake(snow, 200L);
        assertEquals(0.5625D, PillRules.tolerance(twice, snow, 300L));
        // Other kinds keep separate tolerance.
        assertEquals(1.0D, PillRules.tolerance(twice, List.of(PillKind.VITALITY), 300L));
        PillState other = twice.afterIntake(List.of(PillKind.VITALITY), 300L);
        assertEquals(0.5625D, PillRules.tolerance(other, snow, 400L));
        assertEquals(0.75D, PillRules.tolerance(other, List.of(PillKind.VITALITY), 400L));
        // several pills together: no tolerance and the streak is gone
        assertEquals(1.0D, PillRules.tolerance(twice, List.of(PillKind.SNOW_PLUM, PillKind.VITALITY), 300L));
        PillState together = twice.afterIntake(List.of(PillKind.SNOW_PLUM, PillKind.VITALITY), 300L);
        assertEquals(1.0D, PillRules.tolerance(together, snow, 400L));
        // Recovery removes one step at each 6000-tick boundary.
        assertEquals(0.5625D, PillRules.tolerance(twice, snow, 200L + PillRules.TOLERANCE_RECOVERY_TICKS - 1L));
        assertEquals(0.75D, PillRules.tolerance(twice, snow, 200L + PillRules.TOLERANCE_RECOVERY_TICKS));
        assertEquals(1.0D, PillRules.tolerance(twice, snow, 200L + 2 * PillRules.TOLERANCE_RECOVERY_TICKS));
        assertEquals(0.25D, PillRules.toleranceFactor(2000));
    }

    @Test
    @DisplayName("Окно: до трёх разных пилюль, продление и потолок")
    void theWindow() {
        assertEquals(PillRules.Refusal.NONE, PillRules.canAdd(List.of(), PillKind.SNOW_PLUM));
        assertEquals(PillRules.Refusal.SAME_KIND, PillRules.canAdd(List.of(PillKind.SNOW_PLUM), PillKind.SNOW_PLUM));
        assertEquals(PillRules.Refusal.NONE, PillRules.canAdd(List.of(PillKind.SNOW_PLUM), PillKind.VITALITY));
        assertEquals(PillRules.Refusal.FULL, PillRules.canAdd(
                List.of(PillKind.SNOW_PLUM, PillKind.VITALITY, PillKind.BLUE), PillKind.BLOOD));
        assertEquals(1000L + PillRules.WINDOW_TICKS, PillRules.deadline(1000L, 0L, 1000L, true));
        long second = PillRules.deadline(1000L, 1000L + PillRules.WINDOW_TICKS, 1100L, false);
        assertEquals(1000L + PillRules.WINDOW_TICKS + PillRules.WINDOW_EXTEND, second);
        assertEquals(1000L + PillRules.WINDOW_MAX, PillRules.deadline(1000L, 1000L + PillRules.WINDOW_MAX - 10L, 1000L + PillRules.WINDOW_MAX - 20L, false));
    }

    @Test
    @DisplayName("Подлёт только у высшей пилюли и только за безупречное усвоение")
    void theLift() {
        assertTrue(PillRules.lifts(List.of(PillKind.ORIGIN_ENERGY), true));
        assertFalse(PillRules.lifts(List.of(PillKind.ORIGIN_ENERGY), false));
        assertFalse(PillRules.lifts(List.of(PillKind.SNOW_PLUM, PillKind.VITALITY), true));
        assertTrue(PillRules.lifts(List.of(PillKind.VITALITY, PillKind.BLUE), true));
    }

    @Test
    @DisplayName("Привыкание и окно сохраняются: кодек читает старые сохранения без полей привыкания")
    void theStateCodec() {
        PillState s = PillState.NONE.afterIntake(List.of(PillKind.SNOW_PLUM), 500L)
                .afterIntake(List.of(PillKind.VITALITY), 550L).afterIntake(List.of(PillKind.SNOW_PLUM), 600L);
        com.google.gson.JsonElement json = PillState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, s).getOrThrow();
        PillState back = PillState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(s, back);
        assertEquals(2, back.streakCount());
        assertEquals(1, back.streakOf(PillKind.VITALITY, 600L));
        com.google.gson.JsonObject lastKindOnly = json.deepCopy().getAsJsonObject();
        lastKindOnly.remove("tolerances");
        PillState migrated = PillState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, lastKindOnly).getOrThrow();
        assertEquals(2, migrated.streakOf(PillKind.SNOW_PLUM, 600L));
        // an old save (with the nodes mask and no streak) still reads
        com.google.gson.JsonObject old = new com.google.gson.JsonObject();
        old.addProperty("opened", 5);
        PillState legacy = PillState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, old).getOrThrow();
        assertEquals(PillState.NONE, legacy);
    }
}
