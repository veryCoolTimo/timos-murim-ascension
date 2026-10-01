package io.github.verycooltimo.murim.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Давление ауры (docs/design/01 «Давление ци», 19 §3ж). */
class AuraPressureTest {

    @Test
    @DisplayName("Равный и слабее не давят")
    void equalOrWeaker() {
        assertEquals(0.0F, AuraPressure.of(2, 2, 1.0D));
        assertEquals(0.0F, AuraPressure.of(1, 3, 1.0D));
    }

    @Test
    @DisplayName("Чем больше разница рангов, тем сильнее и дальше")
    void growsWithGap() {
        float previous = 0.0F;
        for (int gap = 1; gap <= 6; gap++) {
            float p = AuraPressure.of(gap, 0, 1.0D);
            assertTrue(p > previous || p == 1.0F, "разница " + gap);
            previous = p;
        }
        assertTrue(AuraPressure.range(4) > AuraPressure.range(1));
    }

    @Test
    @DisplayName("Слабеет с расстоянием и пропадает за радиусом — отход работает")
    void fallsWithDistance() {
        float near = AuraPressure.of(4, 0, 2.0D);
        float mid = AuraPressure.of(4, 0, 9.0D);
        assertTrue(near > mid && mid > 0.0F);
        assertEquals(0.0F, AuraPressure.of(4, 0, AuraPressure.range(4) + 0.1D));
    }

    @Test
    @DisplayName("Ввод не отнимается: даже полное давление оставляет скорость")
    void neverFreezes() {
        assertTrue(1.0D + AuraPressure.speedPenalty(1.0F) > 0.1D);
    }
}
