package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallingPetalRulesTest {

    @Test
    @DisplayName("Пять ударов в порядке, сумма урона 3,0, удары внутри шкалы")
    void strikesOrdered() {
        double sum = 0.0D;
        for (int i = 0; i < FallingPetalRules.STRIKES.length; i++) {
            sum += FallingPetalRules.DAMAGE[i];
            if (i > 0) {
                assertTrue(FallingPetalRules.STRIKES[i] > FallingPetalRules.STRIKES[i - 1]);
            }
            assertTrue(FallingPetalRules.STRIKES[i] + 2 < FallingPetalRules.END);
        }
        assertEquals(3.0D, sum, 1.0E-9D);
        assertEquals(84, FallingPetalRules.STANCE + FallingPetalRules.END + 1, "Шкала совпадает с анимацией 4,2 с");
    }

    @Test
    @DisplayName("Возврат вдвое быстрее реза: «меч возвращается быстрее, чем бьёт»")
    void returnFasterThanCut() {
        assertTrue(FallingPetalRules.RETURN * 2 <= FallingPetalRules.CUT);
    }

    @Test
    @DisplayName("Рывки не перекрываются, финал — сразу за спиной цели")
    void dashesAndFinish() {
        for (int i = 1; i < FallingPetalRules.DASHES.length; i++) {
            int[] a = FallingPetalRules.DASHES[i - 1];
            assertTrue(a[0] + a[1] <= FallingPetalRules.DASHES[i][0], "рывок " + i + " начинается раньше конца прошлого");
        }
        Vec3 t = new Vec3(10.0D, 64.0D, 10.0D);
        Vec3 f = new Vec3(0.0D, 0.0D, 1.0D);
        Vec3 end = FallingPetalRules.point(FallingPetalRules.DASHES.length - 1, t, f, 0.3D);
        double along = end.subtract(t).dot(f);
        assertTrue(along > 0.8D && along < 2.0D, "финал сразу за спиной: 0,8–2 блока, а не " + along);
        Vec3 entry = FallingPetalRules.point(0, t, f, 0.3D);
        assertTrue(entry.subtract(t).dot(f) < 0.0D, "вход — перед целью");
        assertTrue(FallingPetalRules.point(FallingPetalRules.PIVOT, t, f, 0.3D).subtract(t).dot(FallingPetalRules.left(f)) > 1.0D,
                "разворот — слева от цели");
    }

    @Test
    @DisplayName("Острие на дуге удара: в начале и в конце по разные стороны")
    void tipSweeps() {
        Vec3 base = Vec3.ZERO;
        Vec3 f = new Vec3(0.0D, 0.0D, 1.0D);
        for (double[] arc : FallingPetalRules.ARCS) {
            Vec3 a = FallingPetalRules.tip(base, f, arc, 0.0D);
            Vec3 b = FallingPetalRules.tip(base, f, arc, 1.0D);
            double sa = a.dot(FallingPetalRules.left(f));
            double sb = b.dot(FallingPetalRules.left(f));
            assertTrue(sa * sb < 0.0D, "дуга не пересекает ось взгляда");
        }
    }
}
