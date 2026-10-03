package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScatterRulesTest {

    private static final Vec3 CENTRE = new Vec3(10.0D, 65.0D, 10.0D);

    @Test
    @DisplayName("7-й слой: шесть клонов по четыре удара — 24 удара, все до взмаха мастера")
    void twentyFourStrokes() {
        int n = ScatterRules.clones(7) * ScatterRules.strokes(7);
        assertEquals(24, n);
        for (int layer = 1; layer <= 8; layer++) {
            for (int i = 0; i < ScatterRules.clones(layer); i++) {
                for (int j = 0; j < ScatterRules.strokes(layer); j++) {
                    double end = ScatterRules.strokeStart(layer, i, j) + ScatterRules.PASS;
                    assertTrue(end < ScatterRules.FINAL, "удар " + i + "/" + j + " слоя " + layer + " заходит на взмах");
                    assertTrue(ScatterRules.strokeStart(layer, i, j) >= ScatterRules.STRIKE0);
                }
            }
        }
        assertTrue(ScatterRules.FINAL + ScatterRules.FINAL_TICKS < ScatterRules.SCATTER);
        assertEquals(180, ScatterRules.END, "шкала = анимация 9 с");
    }

    @Test
    @DisplayName("Каждая хорда клона проходит сквозь хитбокс цели у центра (зомби, на земле и в воздухе)")
    void chordsCrossTarget() {
        AABB zombie = new AABB(CENTRE.x - 0.3D, CENTRE.y - 0.975D, CENTRE.z - 0.3D, CENTRE.x + 0.3D, CENTRE.y + 0.975D, CENTRE.z + 0.3D);
        for (boolean grounded : new boolean[] {true, false}) {
            for (double base : new double[] {0.0D, 1.3D, -2.4D}) {
                for (int i = 0; i < 6; i++) {
                    for (int j = 0; j < 4; j++) {
                        Vec3[] ch = ScatterRules.stroke(CENTRE, base, i, j, grounded);
                        Vec3 c = ScatterExecutor.closest(ch[0], ch[1], CENTRE);
                        assertTrue(c.distanceTo(CENTRE) < 1.2D, "хорда " + i + "/" + j + " мимо: " + c.distanceTo(CENTRE));
                        assertTrue(ScatterExecutor.hits(zombie.inflate(ScatterRules.BLADE), ch[0], ch[1]));
                        if (grounded) {
                            assertTrue(ch[0].y > CENTRE.y - 1.0D && ch[1].y > CENTRE.y - 1.0D, "клон уходит под землю");
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Набор задетых у каждого удара свой: отрезок вдали от цели её не задевает")
    void missFarAway() {
        AABB far = new AABB(CENTRE.add(6.0D, -1.0D, 6.0D), CENTRE.add(6.6D, 1.0D, 6.6D));
        Vec3[] ch = ScatterRules.stroke(CENTRE, 0.0D, 0, 0, true);
        assertFalse(ScatterExecutor.hits(far.inflate(ScatterRules.BLADE), ch[0], ch[1]));
    }

    @Test
    @DisplayName("Клон непрерывен: выход из скопления → прыжок → заход → хорды, без телепортов")
    void cloneContinuous() {
        Vec3 feet = new Vec3(0.0D, 64.0D, 0.0D);
        Vec3 jump = ScatterRules.jump(feet, CENTRE, 1);
        for (int i = 0; i < 6; i++) {
            Vec3 cl = ScatterRules.cluster(feet, 0.0D, i);
            Vec3 last = ScatterRules.clone(cl, jump, CENTRE, 0.0D, 7, i, ScatterRules.RELEASE, true);
            for (double t = ScatterRules.RELEASE + 0.25D; t < ScatterRules.SCATTER; t += 0.25D) {
                Vec3 p = ScatterRules.clone(cl, jump, CENTRE, 0.0D, 7, i, t, true);
                assertTrue(p.distanceTo(last) < 1.6D, "скачок клона " + i + " на тике " + t + ": " + p.distanceTo(last));
                last = p;
            }
        }
    }

    @Test
    @DisplayName("Прыжок вбок: ~3 блока поперёк линии на цель, к цели не ближе 4,5 блока")
    void jumpSideways() {
        Vec3 feet = new Vec3(0.0D, 64.0D, 0.0D);
        Vec3 j = ScatterRules.jump(feet, new Vec3(0.0D, 65.0D, 7.0D), 1);
        assertEquals(3.0D, Math.abs(j.x), 1.0E-6D);
        assertTrue(j.z <= 2.5D + 1.0E-6D);
    }
}
