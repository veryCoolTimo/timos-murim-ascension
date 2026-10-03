package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Шкала и геометрия Ливня Цветущей Сливы: тайминги укладываются в анимацию 10 с, проход — за спину. */
class RainRulesTest {

    @Test
    void timelineFitsTheAnimation() {
        // Анимация 10 с = 200 тиков; замах — начало IMPACT (60), ливень до 140, хвост до 200.
        assertEquals(60, RainRules.RELEASE);
        assertEquals(200, RainRules.END);
        assertTrue(RainRules.contact(2) < RainRules.AFTER);
        assertTrue(RainRules.PEAKS[RainRules.PEAKS.length - 1] < RainRules.HOLD_B);
        assertEquals(RainRules.PEAKS.length, RainRules.ORDER.length);
        for (int i = 1; i < RainRules.PEAKS.length; i++) {
            assertTrue(RainRules.PEAKS[i] > RainRules.PEAKS[i - 1], "пики уколов по возрастанию");
        }
        // Пауза перед ливнем — настоящая: нити стоят до первой волны.
        assertTrue(RainRules.COHORTS[0] - RainRules.STILL >= 4);
    }

    @Test
    void sixDirectionsEachCycle() {
        for (int cycle = 0; cycle < 3; cycle++) {
            boolean[] seen = new boolean[6];
            for (int k = cycle * 6; k < cycle * 6 + 6; k++) {
                seen[RainRules.ORDER[k]] = true;
            }
            for (boolean s : seen) {
                assertTrue(s, "в каждом цикле все шесть сторон");
            }
        }
    }

    @Test
    void passEndsBehindTheTarget() {
        Vec3 from = new Vec3(0.0D, 64.0D, 0.0D);
        Vec3 target = new Vec3(0.0D, 64.0D, 7.0D);
        Vec3 end = RainRules.behind(from, target, 0.6D);
        // За спиной: дальше цели по линии подхода на 2,2 + полширины, сбоку — не сквозь неё.
        assertEquals(7.0D + RainRules.BEHIND + 0.3D, end.z, 1.0E-6D);
        assertTrue(Math.abs(end.x) > 0.1D && Math.abs(end.x) < 1.0D);
    }

    @Test
    void layersGrow() {
        assertEquals(0.0D, RainRules.density(0));
        assertEquals(0, RainRules.shells(2));
        assertEquals(3, RainRules.shells(7));
        assertTrue(RainRules.power(7) > RainRules.power(3));
        assertTrue(RainRules.petals(3) && !RainRules.petals(2));
    }
}
