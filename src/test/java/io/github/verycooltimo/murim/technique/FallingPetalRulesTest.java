package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallingPetalRulesTest {

    @Test
    @DisplayName("Три удара в порядке, сумма урона 3,0, удары внутри шкалы")
    void strikesOrdered() {
        assertEquals(3, FallingPetalRules.STRIKES.length);
        assertEquals(FallingPetalRules.STRIKES.length, FallingPetalRules.DAMAGE.length);
        assertEquals(FallingPetalRules.STRIKES.length, FallingPetalRules.VANISH.length);
        assertEquals(FallingPetalRules.STRIKES.length, FallingPetalRules.SIDES.length);
        assertEquals(FallingPetalRules.STRIKES.length, FallingPetalRules.ARCS.length);
        double sum = 0.0D;
        for (int i = 0; i < FallingPetalRules.STRIKES.length; i++) {
            sum += FallingPetalRules.DAMAGE[i];
            if (i > 0) {
                assertTrue(FallingPetalRules.STRIKES[i] > FallingPetalRules.STRIKES[i - 1]);
            }
            assertTrue(FallingPetalRules.STRIKES[i] + 2 < FallingPetalRules.END);
            assertEquals(i, FallingPetalRules.strikeAt(FallingPetalRules.STRIKES[i]));
        }
        assertEquals(-1, FallingPetalRules.strikeAt(FallingPetalRules.STRIKES[0] + 1));
        assertEquals(3.0D, sum, 1.0E-9D);
        assertEquals(80, FallingPetalRules.STANCE + FallingPetalRules.END + 1, "Шкала совпадает с анимацией 4,0 с (80 тиков)");
    }

    @Test
    @DisplayName("Исчезновение, появление за спиной и бросок в удар идут по порядку, мишень успевает развернуться")
    void blinkTimeline() {
        for (int k = 0; k < FallingPetalRules.STRIKES.length; k++) {
            int appear = FallingPetalRules.VANISH[k] + FallingPetalRules.APPEAR_DELAY;
            int lunge = FallingPetalRules.STRIKES[k] - FallingPetalRules.LUNGE_STEP_TICKS;
            assertTrue(appear <= lunge, "удар " + k + ": появление позже броска в удар");
            assertTrue(lunge < FallingPetalRules.STRIKES[k]);
            if (k > 0) {
                int prevTurnEnd = FallingPetalRules.STRIKES[k - 1] + 2 + FallingPetalRules.TURN_TICKS;
                assertTrue(FallingPetalRules.VANISH[k] >= prevTurnEnd,
                        "удар " + k + ": исчезновение раньше, чем мишень закончила разворот к мастеру");
            }
        }
        assertTrue(FallingPetalRules.CUT >= 1 && FallingPetalRules.HOLD >= 1);
    }

    @Test
    @DisplayName("Появление за спиной цели: сзади, сзади-слева, сзади-справа; финал — позади, не вплотную")
    void behindPoints() {
        Vec3 t = new Vec3(10.0D, 64.0D, 10.0D);
        Vec3 f = new Vec3(0.0D, 0.0D, 1.0D);
        double half = 0.3D;
        Vec3 left = FallingPetalRules.left(f);
        for (int k = 0; k < FallingPetalRules.SIDES.length; k++) {
            Vec3 p = FallingPetalRules.behind(k, t, f, half);
            Vec3 rel = p.subtract(t);
            assertTrue(rel.dot(f) < 0.0D, "точка " + k + " должна быть за спиной цели");
            assertEquals(half + FallingPetalRules.BEHIND, rel.length(), 1.0E-9D, "расстояние до цели");
            assertEquals(0.0D, rel.y, 1.0E-9D);
        }
        assertEquals(0.0D, FallingPetalRules.behind(0, t, f, half).subtract(t).dot(left), 1.0E-9D, "первая точка строго сзади");
        assertTrue(FallingPetalRules.behind(1, t, f, half).subtract(t).dot(left) > 1.0D, "вторая — сзади-слева");
        assertTrue(FallingPetalRules.behind(2, t, f, half).subtract(t).dot(left) < -1.0D, "третья — сзади-справа");
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
