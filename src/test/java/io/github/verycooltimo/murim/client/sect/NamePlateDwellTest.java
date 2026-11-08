package io.github.verycooltimo.murim.client.sect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamePlateDwellTest {

    @Test
    @DisplayName("The plate shows only after 20 ticks of aim at the same person, fades in, and goes at once when the look moves")
    void dwell() {
        NamePlateDwell d = new NamePlateDwell();
        for (int i = 0; i < NamePlateDwell.DELAY; i++) {
            d.tick(7);
            assertEquals(-1, d.shown(), "too early at tick " + i);
            assertEquals(0.0F, d.alpha(7));
        }
        d.tick(7);
        assertEquals(7, d.shown());
        assertTrue(d.alpha(7) > 0.0F && d.alpha(7) < 1.0F, "fading in");
        for (int i = 0; i < NamePlateDwell.FADE; i++) d.tick(7);
        assertEquals(1.0F, d.alpha(7));
        // a slip of the aim of a few ticks keeps the plate (a walking person), a real look away takes it
        for (int i = 0; i < NamePlateDwell.GRACE; i++) {
            d.tick(-1);
            assertEquals(7, d.shown(), "kept through a slip at " + i);
        }
        d.tick(-1);
        assertEquals(-1, d.shown());
        assertEquals(0.0F, d.alpha(7));
    }

    @Test
    @DisplayName("Switching to another person restarts the second; a person merely nearby (never aimed at) never gets a plate")
    void switching() {
        NamePlateDwell d = new NamePlateDwell();
        for (int i = 0; i < 30; i++) d.tick(1);
        assertEquals(1, d.shown());
        d.tick(2);
        assertEquals(-1, d.shown());
        for (int i = 0; i < 19; i++) d.tick(2);
        assertEquals(-1, d.shown());
        d.tick(2);
        assertEquals(2, d.shown());
        assertEquals(0.0F, d.alpha(1));
    }
}
