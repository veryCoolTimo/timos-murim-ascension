package io.github.verycooltimo.murim.client.vfx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultCrownRendererTest {
    @Test
    void columnIsBroadSymmetricAndFadesToZero() {
        assertEquals(0.35F, VaultCrownRenderer.columnAlpha(0), 0.00001F);
        assertTrue(VaultCrownRenderer.columnAlpha(0) * 0.9F >= 0.3F);
        float previous = VaultCrownRenderer.columnAlpha(0);
        for (int i = 1; i <= 100; i++) {
            double x = i / 100.0D;
            float alpha = VaultCrownRenderer.columnAlpha(x);
            assertTrue(alpha >= 0.0F);
            assertTrue(alpha <= previous);
            assertEquals(alpha, VaultCrownRenderer.columnAlpha(-x));
            previous = alpha;
        }
        assertEquals(0.0F, VaultCrownRenderer.columnAlpha(1));
        assertEquals(0.0F, VaultCrownRenderer.columnAlpha(-1));
        assertEquals(0.0F, VaultCrownRenderer.columnAlpha(1.01D));
        assertEquals(0.0F, VaultCrownRenderer.columnAlpha(-1.01D));
    }

    @Test
    void columnIsFortyPercentLighterAtMidRangeAndUnchangedBeyondEightBlocks() {
        for (double distance : new double[] {0, 1.5, 3, 4, 5}) {
            assertEquals(0.6F, VaultCrownRenderer.columnDistanceStrength(distance), 0.00001F);
        }
        assertEquals(0.8F, VaultCrownRenderer.columnDistanceStrength(6.5), 0.00001F);
        assertEquals(1, VaultCrownRenderer.columnDistanceStrength(8));
        assertEquals(1, VaultCrownRenderer.columnDistanceStrength(100));
        float previous = 0.6F;
        for (int i = 0; i <= 100; i++) {
            float strength = VaultCrownRenderer.columnDistanceStrength(5 + i * 0.03D);
            assertTrue(strength >= previous && strength <= 1);
            previous = strength;
        }
        // The full width and endpoint feather retain their profile under the same multiplier.
        for (double across : new double[] {-1, -0.85, -0.5, 0, 0.5, 0.85, 1}) {
            for (double height : new double[] {0, 0.1, 0.5, 0.925, 1}) {
                float original = VaultCrownRenderer.columnAlpha(across)
                        * VaultCrownRenderer.endFeather(height);
                assertEquals(original * 0.6F,
                        original * VaultCrownRenderer.columnDistanceStrength(4), 0.00001F);
                assertEquals(original, original * VaultCrownRenderer.columnDistanceStrength(8));
            }
        }
    }

    @Test
    void petalsAreSmallerAndFifteenPercentMoreTransparent() {
        assertEquals(0.075D, VaultCrownRenderer.PETAL_SIZE);
        assertEquals(0.65F * 0.85F, VaultCrownRenderer.PETAL_ALPHA, 0.00001F);
    }

    @Test
    void nearFadeClampsAndSmoothlyIncreasesAcrossTheTransition() {
        assertEquals(0, VaultCrownRenderer.nearCameraFade(0));
        assertEquals(0, VaultCrownRenderer.nearCameraFade(1.5));
        assertEquals(0.5F, VaultCrownRenderer.nearCameraFade(2.25), 0.00001F);
        assertEquals(1, VaultCrownRenderer.nearCameraFade(3));
        assertEquals(1, VaultCrownRenderer.nearCameraFade(100));
        float previous = 0;
        for (int i = 0; i <= 100; i++) {
            float fade = VaultCrownRenderer.nearCameraFade(1.5 + i * 0.015);
            assertTrue(fade >= previous && fade <= 1);
            previous = fade;
        }
        assertTrue(VaultCrownRenderer.nearCameraFade(1.51) < 0.001F);
        assertTrue(VaultCrownRenderer.nearCameraFade(2.99) > 0.999F);
    }

    @Test
    void endFeatherUsesSeparateBottomAndTopBands() {
        assertEquals(0, VaultCrownRenderer.endFeather(-0.1));
        assertEquals(0, VaultCrownRenderer.endFeather(0));
        assertEquals(0.5F, VaultCrownRenderer.endFeather(0.1), 0.00001F);
        assertEquals(1, VaultCrownRenderer.endFeather(0.2), 0.00001F);
        assertEquals(1, VaultCrownRenderer.endFeather(0.5), 0.00001F);
        assertEquals(1, VaultCrownRenderer.endFeather(0.85), 0.00001F);
        assertEquals(0.5F, VaultCrownRenderer.endFeather(0.925), 0.00001F);
        assertEquals(0, VaultCrownRenderer.endFeather(1));
        assertEquals(0, VaultCrownRenderer.endFeather(1.1));
        for (int i = 0; i < 100; i++) {
            double t = i / 100.0;
            assertTrue(VaultCrownRenderer.endFeather(t * 0.2)
                    <= VaultCrownRenderer.endFeather((t + 0.01) * 0.2));
            assertTrue(VaultCrownRenderer.endFeather(0.85 + t * 0.15)
                    >= VaultCrownRenderer.endFeather(0.85 + (t + 0.01) * 0.15));
        }
    }

    @Test
    void ceilingStopsAtFirstSolidBlock() {
        List<Integer> reads = new ArrayList<>();
        assertEquals(3, VaultCrownRenderer.ceilingHeight(offset -> {
            reads.add(offset);
            return offset == 3 || offset == 6;
        }));
        assertEquals(List.of(1, 2, 3), reads);
        assertEquals(1, VaultCrownRenderer.ceilingHeight(offset -> true));
    }

    @Test
    void ceilingScanIncludesEighthBlockAndNeverReadsBeyondIt() {
        List<Integer> reads = new ArrayList<>();
        assertEquals(8, VaultCrownRenderer.ceilingHeight(offset -> {
            reads.add(offset);
            return false;
        }));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), reads);
        assertEquals(8, VaultCrownRenderer.ceilingHeight(offset -> offset == 8));
    }
}
