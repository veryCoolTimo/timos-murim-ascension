package io.github.verycooltimo.murim.client.vfx.fx;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FxCultivationMotesTest {
    @Test void quietBeforeTheEffectAndBoundedAfter() {
        var s = new FxCultivationMotes(1);
        for (int i = 0; i < 120; i++) s.step(1 / 60.0, 0, 1);
        assertTrue(s.motes.isEmpty());
        for (int i = 0; i < 60 * 20; i++) { s.step(1 / 60.0, 1, 1); assertTrue(s.motes.size() <= FxCultivationMotes.MAX); }
        assertTrue(s.motes.size() > 100, "a crowd: " + s.motes.size());
    }

    @Test void everythingStaysFiniteAndNearTheBody() {
        var s = new FxCultivationMotes(2);
        for (int i = 0; i < 60 * 20; i++) {
            s.step(1 / 60.0, 1, 1);
            for (var m : s.motes) {
                assertTrue(Double.isFinite(m.x + m.y + m.z + m.vx + m.vy + m.vz));
                assertTrue(Math.hypot(m.x, m.z) < 3.5 && m.y > -.1 && m.y < 5.6);
            }
        }
    }

    @Test void lowerQualityBearsFewerAndNeverRemovesLiveOnes() {
        var full = new FxCultivationMotes(3);
        var low = new FxCultivationMotes(3);
        for (int i = 0; i < 60 * 12; i++) { full.step(1 / 60.0, 1, 1); low.step(1 / 60.0, 1, .2); }
        assertTrue(low.motes.size() < full.motes.size() * .5);
        int before = full.motes.size();
        full.step(1 / 60.0, 1, .2);
        assertTrue(full.motes.size() >= before - 6, "no mass vanishing when the quality drops");
    }

    @Test void swirlAndRiseHappen() {
        var s = new FxCultivationMotes(4);
        for (int i = 0; i < 60 * 10; i++) s.step(1 / 60.0, 1, 1);
        double up = 0, turn = 0; int n = 0;
        for (var m : s.motes) { if (m.spark) continue; up += m.vy; turn += Math.abs(m.x * m.vz - m.z * m.vx) / Math.max(.3, Math.hypot(m.x, m.z)); n++; }
        assertTrue(up / n > .2, "rising on average");
        assertTrue(turn / n > .15, "turning round the axis");
    }

    @Test void coloursAreFromThePalette() {
        var s = new FxCultivationMotes(5);
        for (int i = 0; i < 600; i++) s.step(1 / 60.0, 1, 1);
        outer:
        for (var m : s.motes) {
            for (int c : FxCultivationMotes.PALETTE) if (c == m.rgb) continue outer;
            assertTrue(m.spark && (m.rgb == 0xB8A0FF), "unknown colour " + Integer.toHexString(m.rgb));
        }
    }

    @Test void sameSeedSameRun() {
        var a = FxCultivationMotes.at(6, 7);
        var b = FxCultivationMotes.at(6, 7);
        assertEquals(a.motes.size(), b.motes.size());
        assertEquals(a.motes.get(0).x, b.motes.get(0).x);
    }
}
