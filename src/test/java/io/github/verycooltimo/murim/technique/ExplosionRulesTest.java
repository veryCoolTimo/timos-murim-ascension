package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Шкала и геометрия Взрыва Цветущей Сливы: тайминги в анимации 4 с, выброс к цели, в том числе в небо. */
class ExplosionRulesTest {

    @Test
    void timelineFitsTheAnimation() {
        // Анимация 4,0 с = 80 тиков; замах (windup) 28 = прыжок, контакт на 1,6 с (32).
        assertEquals(28, ExplosionRules.LUNGE);
        assertEquals(32, ExplosionRules.CONTACT);
        assertEquals(80, ExplosionRules.END);
        // Все взмахи — до удержания, удержание — до прыжка, надпись — в паузе.
        int lastStroke = ExplosionRules.STANCE + ExplosionRules.STROKE_GAP * (ExplosionRules.STROKES - 1);
        assertTrue(lastStroke < ExplosionRules.HOLD);
        assertTrue(ExplosionRules.HOLD < ExplosionRules.LUNGE);
        assertTrue(ExplosionRules.CAPTION >= ExplosionRules.HOLD && ExplosionRules.CAPTION < ExplosionRules.LUNGE);
        // Дорезы и оглушение — до конца техники.
        for (int g : ExplosionRules.GRIND) {
            assertTrue(ExplosionRules.CONTACT + g < ExplosionRules.END);
        }
        assertTrue(ExplosionRules.STUN_DELAY < ExplosionRules.GRIND[0]);
    }

    @Test
    void layersGrow() {
        assertEquals(0.0D, ExplosionRules.range(0));
        for (int l = 2; l <= 8; l++) {
            assertTrue(ExplosionRules.range(l) > ExplosionRules.range(l - 1));
            assertTrue(ExplosionRules.stakes(l) >= ExplosionRules.stakes(l - 1));
            assertTrue(ExplosionRules.power(l) > ExplosionRules.power(l - 1));
        }
        assertTrue(ExplosionRules.range(7) >= 16.0D, "слой 7 достаёт на 16 блоков");
        assertEquals(1, ExplosionRules.stakes(7) % 2, "нечётно — середина под мечом");
        assertTrue(!ExplosionRules.petals(2) && ExplosionRules.petals(3), "лепестки — с 3-го слоя");
    }

    @Test
    void blastReachesGroundTargetInFront() {
        Vec3 feet = new Vec3(0.0D, 64.0D, 0.0D);
        Vec3 wall = ExplosionRules.wallCentre(feet, new Vec3(0.0D, 0.0D, 1.0D));
        Vec3 strike = wall.add(0.0D, ExplosionRules.STRIKE_Y, 0.0D);
        Vec3 target = new Vec3(0.5D, 65.0D, 7.0D);
        Vec3 axis = ExplosionRules.axis(strike, new Vec3(0.0D, 0.0D, 1.0D), target);
        // Фронт ещё не дошёл — промах; дошёл — попадание.
        assertTrue(ExplosionRules.inside(strike, axis, target, 1.0D, 7, 0.4D) < 0.0D);
        assertTrue(ExplosionRules.inside(strike, axis, target, ExplosionRules.front(4, 7), 7, 0.4D) >= 0.0D);
        // Сзади мастера — не задевает; сбоку далеко — тоже.
        assertTrue(ExplosionRules.inside(strike, axis, new Vec3(0.0D, 65.0D, -4.0D), 16.0D, 7, 0.4D) < 0.0D);
        assertTrue(ExplosionRules.inside(strike, axis, new Vec3(14.0D, 65.0D, 6.0D), 16.0D, 7, 0.4D) < 0.0D);
        // Подбежавший вплотную к мастеру (между ним и стеной) — под ударом.
        assertTrue(ExplosionRules.inside(strike, axis, new Vec3(0.0D, 65.0D, 1.2D), 1.0D, 7, 0.4D) >= 0.0D);
    }

    @Test
    void blastTiltsToTargetInTheSky() {
        Vec3 feet = new Vec3(0.0D, 64.0D, 0.0D);
        Vec3 wall = ExplosionRules.wallCentre(feet, new Vec3(0.0D, 0.6D, 1.0D));
        Vec3 strike = wall.add(0.0D, ExplosionRules.STRIKE_Y, 0.0D);
        Vec3 sky = new Vec3(0.0D, 70.0D, 9.0D);
        Vec3 axis = ExplosionRules.axis(strike, new Vec3(0.0D, 0.0D, 1.0D), sky);
        assertTrue(axis.y > 0.4D, "ось выброса наклонена вверх к цели");
        assertTrue(ExplosionRules.inside(strike, axis, sky, 16.0D, 7, 0.5D) >= 0.0D);
        // Цель ушла далеко вбок — ось остаётся по нормали стены.
        Vec3 aside = ExplosionRules.axis(strike, new Vec3(0.0D, 0.0D, 1.0D), new Vec3(12.0D, 65.0D, 3.0D));
        assertEquals(1.0D, aside.z, 1.0E-9D);
    }
}
