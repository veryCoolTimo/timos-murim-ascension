package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Купол Цветущей Сливы: шкала в анимации 5,05 с, посадка стволов, сектор, пересечение дуги. */
class DomeRulesTest {

    @Test
    void timelineFitsTheAnimation() {
        // windup 30 + impact 1 + recovery 60 + dissipation 10 = 101 тик = анимация 5,05 с.
        assertEquals(30, DomeRules.RAISE);
        assertEquals(91, DomeRules.HOLD_END);
        assertEquals(101, DomeRules.END);
        for (int layer = 1; layer <= 8; layer++) {
            for (int k = 0; k < DomeRules.trunks(layer); k++) {
                assertTrue(DomeRules.plantTick(layer, k) + DomeRules.GROW <= DomeRules.RAISE,
                        "ствол " + k + " слоя " + layer + " стоит к смыканию");
            }
        }
    }

    @Test
    void everyTrunkIsPlantedOnce() {
        for (int layer = 1; layer <= 8; layer++) {
            int n = DomeRules.trunks(layer);
            boolean[] seen = new boolean[n];
            for (int o = 0; o < n; o++) {
                int k = DomeRules.plantOrder(layer, o);
                assertFalse(seen[k], "ствол посажен дважды");
                seen[k] = true;
            }
            // Первым — центр.
            assertEquals(n / 2, DomeRules.plantOrder(layer, 0));
        }
    }

    @Test
    void sectorCoversFrontOnly() {
        int layer = 7;
        assertTrue(DomeRules.covered(layer, 0.0D, DomeRules.RAISE));
        assertTrue(DomeRules.covered(layer, 70.0D, DomeRules.RAISE));
        assertFalse(DomeRules.covered(layer, 100.0D, DomeRules.RAISE), "сбоку не защищает");
        assertFalse(DomeRules.covered(layer, 180.0D, DomeRules.RAISE), "сзади не защищает");
        assertFalse(DomeRules.covered(layer, 0.0D, DomeRules.SWINGS[0]), "ствол ещё растёт");
        assertFalse(DomeRules.covered(layer, 0.0D, DomeRules.HOLD_END), "подпитка кончилась");
    }

    @Test
    void arrowFromTheFrontCrossesTheArc() {
        DomeRules.Frame f = new DomeRules.Frame(Vec3.ZERO, new Vec3(0.0D, 0.0D, 1.0D), 0.0D);
        Vec3 a = new Vec3(0.0D, 1.5D, 4.0D);
        Vec3 b = new Vec3(0.0D, 1.5D, 1.0D);
        double u = DomeRules.crossing(7, f, a, b, DomeRules.RAISE);
        assertTrue(u > 0.0D && u < 1.0D);
        // Из-за спины — нет.
        assertTrue(DomeRules.crossing(7, f, new Vec3(0.0D, 1.5D, -4.0D), new Vec3(0.0D, 1.5D, -1.0D), DomeRules.RAISE) < 0.0D);
        // Изнутри наружу — нет (свои стрелы не держит).
        assertTrue(DomeRules.crossing(7, f, b, a, DomeRules.RAISE) < 0.0D);
        // Высоко над кроной — проходит.
        assertTrue(DomeRules.crossing(7, f, new Vec3(0.0D, 9.0D, 4.0D), new Vec3(0.0D, 9.0D, 1.0D), DomeRules.RAISE) < 0.0D);
    }

    @Test
    void tiltedFrameFacesTheSky() {
        DomeRules.Frame f = new DomeRules.Frame(Vec3.ZERO, new Vec3(0.0D, 0.0D, 1.0D), 40.0D);
        Vec3 ahead = f.ahead();
        assertTrue(ahead.y > 0.5D && ahead.z > 0.5D);
        double[] l = f.local(f.world(0.3D, 2.0D, 1.7D));
        assertEquals(0.3D, l[0], 1.0E-9D);
        assertEquals(2.0D, l[1], 1.0E-9D);
        assertEquals(1.7D, l[2], 1.0E-9D);
    }

    @Test
    void poolGrowsWithLayer() {
        assertEquals(0.0D, DomeRules.pool(0, 20.0D));
        assertTrue(DomeRules.pool(7, 20.0D) > DomeRules.pool(1, 20.0D));
        assertEquals(36.0D, DomeRules.pool(7, 20.0D), 1.0E-9D);
    }
}
