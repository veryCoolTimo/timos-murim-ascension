package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Шкала и геометрия Ливня Цветов: тайминги в анимации 3,5 с, пикирование сквозь центр цели. */
class ShowerRulesTest {

    @Test
    void timelineFitsTheAnimation() {
        // Анимация 3,5 с = 70 тиков; windup 28 + impact 2 + recovery 36 = 66, ещё 4 — рассеяние.
        assertEquals(28, ShowerRules.RELEASE);
        assertEquals(66, ShowerRules.RELEASE + ShowerRules.END);
        assertTrue(ShowerRules.RISE_TICKS < ShowerRules.DIVE, "зависание между взлётом и пикированием");
        assertTrue(ShowerRules.SLIDE > ShowerRules.DIVE + ShowerRules.DIVE_TICKS - 1);
        assertTrue(ShowerRules.DIVE + ShowerRules.HIT_WINDOW + ShowerRules.CUTS[ShowerRules.CUTS.length - 1] < ShowerRules.END);
    }

    @Test
    void apexHangsAboveAndBeforeTheTarget() {
        Vec3 o = new Vec3(0.0D, 64.0D, 0.0D);
        Vec3 target = new Vec3(0.0D, 64.0D, 10.0D);
        Vec3 apex = ShowerRules.apex(o, target, 7);
        assertEquals(10.0D - ShowerRules.BACK, apex.z, 1.0E-6D);
        assertEquals(64.0D + ShowerRules.height(7), apex.y, 1.0E-6D);
        // Цель в небе — зависание над ней, а не над землёй.
        Vec3 air = ShowerRules.apex(o, target.add(0.0D, 5.0D, 0.0D), 7);
        assertEquals(69.0D + ShowerRules.height(7), air.y, 1.0E-6D);
    }

    @Test
    void diveGoesThroughTheTargetCentre() {
        Vec3 from = new Vec3(0.0D, 68.0D, 7.0D);
        Vec3 centre = new Vec3(0.0D, 64.95D, 10.0D);
        Vec3 exit = ShowerRules.exit(from, centre, 0.6D);
        // Тело (0,9 над ступнями) проходит через центр цели и выходит за неё.
        double d = ShowerRules.segmentDistance(centre, from.add(0.0D, 0.9D, 0.0D), exit.add(0.0D, 0.9D, 0.0D));
        assertTrue(d < 1.0E-6D, "путь тела через центр цели");
        assertTrue(exit.z > centre.z + 1.5D, "выход за цель");
    }

    @Test
    void layersGrow() {
        assertEquals(0, ShowerRules.streaks(0));
        assertEquals(3, ShowerRules.streaks(1));
        assertEquals(12, ShowerRules.streaks(7));
        assertTrue(ShowerRules.spread(7) > ShowerRules.spread(2));
        assertTrue(ShowerRules.power(7) > ShowerRules.power(3));
        assertTrue(ShowerRules.petals(3) && !ShowerRules.petals(2));
        assertTrue(ShowerRules.afterimages(2) && !ShowerRules.afterimages(1));
    }
}
