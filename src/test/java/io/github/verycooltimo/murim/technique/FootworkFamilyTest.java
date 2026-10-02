package io.github.verycooltimo.murim.technique;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FootworkFamilyTest {

    @Test
    void huashanHasNoWallsAndEarlyAirTurn() {
        FootworkFamily h = FootworkFamily.HUASHAN;
        assertFalse(h.canRun(0));
        assertTrue(h.canRun(1));
        assertTrue(h.canLeap(2));
        for (int layer = 0; layer < h.layers(); layer++) {
            assertEquals(0, h.wallKicks(layer));
        }
        assertFalse(h.canAirTurn(2));
        assertTrue(h.canAirTurn(3));
        assertEquals(2, h.tier(4));
    }

    @Test
    void windGodUnlocksBySpec() {
        FootworkFamily w = FootworkFamily.WIND_GOD;
        assertEquals(8, w.layers());
        assertTrue(w.canRun(0));
        assertFalse(w.canLeap(1));
        assertTrue(w.canLeap(2));
        assertEquals(0, w.wallKicks(3));
        assertEquals(1, w.wallKicks(4));
        assertEquals(2, w.wallKicks(7));
        assertFalse(w.canAirTurn(6));
        assertTrue(w.canAirTurn(7));
        assertEquals(4, w.tier(7));
        assertEquals(0, w.tier(1));
    }

    @Test
    void tiersNeverDecrease() {
        for (FootworkFamily f : FootworkFamily.values()) {
            for (int layer = 1; layer < f.layers(); layer++) {
                assertTrue(f.tier(layer) >= f.tier(layer - 1));
            }
        }
    }
}
