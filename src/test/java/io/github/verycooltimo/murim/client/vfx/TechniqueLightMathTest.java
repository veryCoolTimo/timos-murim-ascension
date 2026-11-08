package io.github.verycooltimo.murim.client.vfx;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TechniqueLightMathTest {
    @Test void peakIntensityHasVisibleGroundAndAlbedoContribution() {
        assertTrue(TechniqueLightMath.groundAlpha(1, 0, 4) >= .6F);
        assertTrue(TechniqueLightMath.bodyGain(1) >= .8F);
        assertEquals(.21F, TechniqueLightMath.groundAlpha(.3F, 0, 4), .0001F);
        assertEquals(.3F, TechniqueLightMath.bodyGain(.3F), .0001F);
        assertEquals(.175F, TechniqueLightMath.groundAlpha(1, 2, 4), .0001F);
        assertEquals(0F, TechniqueLightMath.groundAlpha(1, 4, 4));
        assertEquals(0F, TechniqueLightMath.groundAlpha(0, 0, 4));
        assertEquals(0F, TechniqueLightMath.bodyGain(0));
    }
    @Test void quadraticFalloffHasExactCenterHalfAndEdge() {
        assertEquals(1F, TechniqueLightMath.falloff(0, 4));
        assertEquals(.25F, TechniqueLightMath.falloff(2, 4));
        assertEquals(0F, TechniqueLightMath.falloff(4, 4));
        assertEquals(0F, TechniqueLightMath.falloff(5, 4));
        assertEquals(0F, TechniqueLightMath.falloff(1, 0));
    }
    @Test void envelopeInterpolatesAttackHoldAndSixTickRelease() {
        assertEquals(.25F, TechniqueLightMath.envelope(.5F, 2, 3, 6));
        assertEquals(1F, TechniqueLightMath.envelope(4.5F, 2, 3, 6));
        assertEquals(.5F, TechniqueLightMath.envelope(8F, 2, 3, 6));
        assertEquals(0F, TechniqueLightMath.envelope(11F, 2, 3, 6));
        assertEquals(1F, TechniqueLightMath.envelope(0F, 0, 54, 6));
        assertEquals(0F, TechniqueLightMath.envelope(60F, 0, 54, 6));
    }
    @Test void ninthLightEvictsFarthestAndResultsAreNearestFirst() {
        var sources = new ArrayList<>(List.of(9, 7, 6, 5, 4, 3, 2, 1));
        assertTrue(TechniqueLightMath.admit(sources, 0, Integer::doubleValue));
        assertEquals(8, sources.size());
        assertFalse(TechniqueLightMath.admit(sources, 100, Integer::doubleValue));
        assertEquals(8, sources.size());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7),
                TechniqueLightMath.nearest(sources, Integer::doubleValue, TechniqueLightMath.CAP));
        assertEquals(List.of(7, 6, 5, 4, 3, 2, 1, 0),
                TechniqueLightMath.nearest(sources, v -> Math.abs(v - 10), TechniqueLightMath.CAP));
    }
    @Test void stepFixtureSplitsAtLedgeWithoutGameTest() {
        var cells = TechniqueLightMath.cells(0, 0, 2, (x, z) -> x < 0 ? 0 : 1);
        assertTrue(cells.stream().anyMatch(p -> p.y() == 0));
        assertTrue(cells.stream().anyMatch(p -> p.y() == 1));
        for (var patch : cells) {
            assertEquals(.5, patch.x1() - patch.x0());
            assertEquals(.5, patch.z1() - patch.z0());
            assertFalse(patch.x0() < 0 && patch.x1() > 0, "A quad bridges the step");
            assertEquals(patch.x1() <= 0 ? 0 : 1, patch.y());
        }
    }
    @Test void unsupportedCellsAreOmitted() {
        var cells = TechniqueLightMath.cells(0, 0, 2, (x, z) -> x < 0 ? Double.NaN : 1);
        assertFalse(cells.isEmpty());
        assertTrue(cells.stream().allMatch(p -> p.x0() >= 0));
    }
}
