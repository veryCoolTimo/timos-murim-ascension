package io.github.verycooltimo.murim.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The combat HUD timing (author 18.10): slots only in a fight, in over 0.3 s, out smoothly after the fight. */
class CombatClockTest {

    private static void run(CombatClock clock, int ticks, boolean signalled) {
        for (int i = 0; i < ticks; i++) {
            clock.tick(signalled);
        }
    }

    @Test
    @DisplayName("Без боя HUD не появляется никогда: взмахи в воздух его не открывают")
    void nothingWithoutAFight() {
        CombatClock clock = new CombatClock();
        for (int i = 0; i < 2000; i++) {
            clock.tick(false);
            assertEquals(0.0F, clock.presence(0.5F), 0.0F);
            assertFalse(clock.active());
        }
    }

    @Test
    @DisplayName("Появляется за 0,3 с, держится 6 с, гаснет плавно")
    void inHoldOut() {
        CombatClock clock = new CombatClock();
        clock.engage();
        run(clock, CombatClock.FADE_IN_TICKS, false);
        assertEquals(1.0F, clock.presence(0.0F), 1.0E-6F, "fully in after 0.3 s");
        // Still fully shown while the fight is remembered (6 s from the engage).
        run(clock, CombatClock.HOLD_TICKS - CombatClock.FADE_IN_TICKS - 1, false);
        assertTrue(clock.active());
        assertEquals(1.0F, clock.presence(0.0F), 1.0E-6F);
        // The fight is over: out within FADE_OUT_TICKS, never jumping.
        float last = 1.0F;
        int ticks = 0;
        while (clock.presence(0.0F) > 0.0F && ticks < 1000) {
            clock.tick(false);
            float now = clock.presence(0.0F);
            assertTrue(now <= last + 1.0E-6F, "presence must fall monotonically");
            assertTrue(last - now < 0.2F, "presence jumped: " + last + " -> " + now);
            last = now;
            ticks++;
        }
        assertFalse(clock.active());
        assertTrue(ticks <= CombatClock.FADE_OUT_TICKS + 2, "fade-out took " + ticks + " ticks");
    }

    @Test
    @DisplayName("Новый признак боя продлевает память о нём с полных 6 с")
    void newSignRefreshes() {
        CombatClock clock = new CombatClock();
        clock.engage();
        run(clock, CombatClock.HOLD_TICKS - 5, false);
        clock.engage();
        run(clock, CombatClock.HOLD_TICKS - 1, false);
        assertTrue(clock.active(), "the second sign did not extend the fight");
        clock.tick(false);
        assertFalse(clock.active());
    }

    @Test
    @DisplayName("Непрерывный признак (захват цели, охотящийся моб) держит HUD, после него ещё 6 с")
    void continuousSign() {
        CombatClock clock = new CombatClock();
        run(clock, 500, true);
        assertEquals(1.0F, clock.presence(0.0F), 1.0E-6F);
        run(clock, CombatClock.HOLD_TICKS - 1, false);
        assertTrue(clock.active());
        clock.tick(false);
        assertFalse(clock.active());
    }

    @Test
    @DisplayName("reset гасит всё сразу: смена мира не оставляет слоты висеть")
    void resetClears() {
        CombatClock clock = new CombatClock();
        run(clock, 40, true);
        clock.reset();
        assertEquals(0.0F, clock.presence(0.0F), 0.0F);
        assertFalse(clock.active());
    }
}
