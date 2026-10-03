package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Шкала и геометрия Взрыва Цветущей Сливы: долгий замах → удар → взрыв лепестков, 2,5 с. */
class ExplosionRulesTest {

    @Test
    void timelineFitsTheAnimation() {
        // Анимация 2,5 с = 50 тиков; замах (windup) 28 тиков, удар — 1,4 с, контакт — 1,55 с (31).
        assertEquals(28, ExplosionRules.LUNGE);
        assertEquals(31, ExplosionRules.CONTACT);
        assertEquals(50, ExplosionRules.END);
        // Замах долгий (≥ 0,8 с) и заканчивается до удара; надпись — пока меч наверху.
        assertTrue(ExplosionRules.WINDUP_END - ExplosionRules.STANCE >= 16);
        assertTrue(ExplosionRules.WINDUP_END <= ExplosionRules.LUNGE);
        assertTrue(ExplosionRules.CAPTION >= ExplosionRules.STANCE && ExplosionRules.CAPTION < ExplosionRules.LUNGE);
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
            assertTrue(ExplosionRules.power(l) > ExplosionRules.power(l - 1));
            assertTrue(ExplosionRules.density(l) > ExplosionRules.density(l - 1));
        }
        assertTrue(ExplosionRules.range(7) >= 16.0D, "слой 7 достаёт на 16 блоков");
        assertTrue(!ExplosionRules.petals(2) && ExplosionRules.petals(3), "лепестки — с 3-го слоя");
    }

    @Test
    void strikePointIsInFrontOfTheMaster() {
        Vec3 feet = new Vec3(0.0D, 64.0D, 0.0D);
        Vec3 strike = ExplosionRules.strikePoint(feet, new Vec3(0.0D, 0.5D, 1.0D));
        // После полушага клинок бьёт в 1,5 блока перед мастером, на высоте груди.
        assertEquals(ExplosionRules.STEP + ExplosionRules.STRIKE_DIST, strike.z, 1.0E-9D);
        assertEquals(0.0D, strike.x, 1.0E-9D);
        assertEquals(64.0D + ExplosionRules.STRIKE_Y, strike.y, 1.0E-9D);
    }

    @Test
    void blastReachesGroundTargetInFront() {
        Vec3 feet = new Vec3(0.0D, 64.0D, 0.0D);
        Vec3 strike = ExplosionRules.strikePoint(feet, new Vec3(0.0D, 0.0D, 1.0D));
        Vec3 target = new Vec3(0.5D, 65.0D, 7.0D);
        Vec3 axis = ExplosionRules.axis(strike, new Vec3(0.0D, 0.0D, 1.0D), target);
        assertTrue(ExplosionRules.inside(strike, axis, target, 1.0D, 7, 0.4D) < 0.0D, "фронт ещё не дошёл");
        assertTrue(ExplosionRules.inside(strike, axis, target, ExplosionRules.front(4, 7), 7, 0.4D) >= 0.0D);
        // Сзади мастера и далеко сбоку — не задевает.
        assertTrue(ExplosionRules.inside(strike, axis, new Vec3(0.0D, 65.0D, -3.0D), 16.0D, 7, 0.4D) < 0.0D);
        assertTrue(ExplosionRules.inside(strike, axis, new Vec3(12.0D, 65.0D, 5.0D), 16.0D, 7, 0.4D) < 0.0D);
        // Подбежавший вплотную к мастеру — под ударом клинка.
        assertTrue(ExplosionRules.inside(strike, axis, new Vec3(0.0D, 65.0D, 1.0D), 1.0D, 7, 0.4D) >= 0.0D);
    }

    @Test
    void blastTiltsToTargetInTheSky() {
        Vec3 feet = new Vec3(0.0D, 64.0D, 0.0D);
        Vec3 aim = new Vec3(0.0D, 0.6D, 1.0D);
        Vec3 strike = ExplosionRules.strikePoint(feet, aim);
        Vec3 sky = new Vec3(0.0D, 70.0D, 9.0D);
        Vec3 axis = ExplosionRules.axis(strike, aim, sky);
        assertTrue(axis.y > 0.4D, "ось выброса наклонена вверх к цели");
        assertTrue(ExplosionRules.inside(strike, axis, sky, 16.0D, 7, 0.5D) >= 0.0D);
        // Цель ушла далеко вбок — ось остаётся по направлению удара.
        Vec3 aside = ExplosionRules.axis(strike, new Vec3(0.0D, 0.0D, 1.0D), new Vec3(12.0D, 65.0D, 3.0D));
        assertEquals(1.0D, aside.z, 1.0E-9D);
    }
}
