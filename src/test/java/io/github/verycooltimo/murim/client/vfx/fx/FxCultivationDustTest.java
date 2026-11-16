package io.github.verycooltimo.murim.client.vfx.fx;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FxCultivationDustTest {
    @Test void lowRingAroundTheBodyAndFinite() {
        for (double t = 1; t < 14; t += .37) for (var p : FxCultivationDust.at(t, 1, 1)) {
            assertTrue(Double.isFinite(p.x() + p.y() + p.z() + p.size() + p.alpha()));
            assertTrue(p.y() < .8 && p.y() > 0, "low: " + p.y());
            double r = Math.hypot(p.x(), p.z());
            assertTrue(r > FxShellWrap.R0 && r < 3.6, "ring radius " + r);
            assertTrue(p.alpha() >= 0 && p.alpha() <= .6, "subtle: " + p.alpha());
        }
    }
    @Test void nothingBeforeTheEffectAndFewerWhenSlow() {
        assertTrue(FxCultivationDust.at(5, 0, 1).isEmpty());
        long full = FxCultivationDust.at(7, 1, 1).stream().filter(p -> p.alpha() > 0).count();
        long low = FxCultivationDust.at(7, 1, .2).stream().filter(p -> p.alpha() > 0).count();
        assertTrue(low < full);
    }
    @Test void noPuffPopsBetweenFrames() {
        for (int i = 0; i < FxCultivationDust.MAX; i++) {
            double prev = -1;
            for (double t = 1.5; t < 14; t += 1 / 60.0) {
                var l = FxCultivationDust.at(t, 1, 1);
                double a = l.get(i).alpha();
                if (prev >= 0) assertTrue(Math.abs(a - prev) < .03, "puff " + i + " jumps at " + t);
                prev = a;
            }
        }
    }
}
