package io.github.verycooltimo.murim.client.vfx;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FullBloomShapeTest {
    @Test void masteryGatesMaterialsIndependentlyOfExecutionTier() {
        for (int tier = 0; tier <= 8; tier++) {
            for (int stars = 0; stars <= 12; stars++) {
                if (stars < 10) {
                    assertEquals(0, FullBloomVfx.Shape.roots(tier, stars));
                    assertEquals(0, FullBloomVfx.Shape.branches(tier, stars));
                    assertEquals(0, FullBloomVfx.Shape.clusters(tier, stars));
                    for (var kind : new PlumLayers.Kind[]{PlumLayers.Kind.ROOTS, PlumLayers.Kind.BRANCH, PlumLayers.Kind.FLOWER})
                        assertFalse(FullBloomVfx.Shape.has(stars, kind));
                }
                if (stars < 11) assertEquals(0, FullBloomVfx.Shape.crown(tier, stars));
                if (stars < 8) assertEquals(0F, FullBloomVfx.Shape.light(stars));
                if (stars < 5 || tier == 0) assertEquals(0, FullBloomVfx.Shape.quadCeiling(tier, stars));
                assertTrue(FullBloomVfx.Shape.quadCeiling(tier, stars) <= 1067);
            }
        }
        assertTrue(FullBloomVfx.Shape.crown(8, 11) > 0);
        assertEquals(48, FullBloomVfx.Shape.crown(8, 12));
        assertEquals(15, FullBloomVfx.Shape.clusters(8, 12));
        assertEquals(8, FullBloomVfx.Shape.roots(8, 12));
        assertEquals(1F, FullBloomVfx.Shape.light(12));
    }
    @Test void sourceRowsAndPopulationGrowToTheOriginalApex() {
        assertEquals(0, FullBloomVfx.Shape.looseLimit(8));
        assertEquals(12, FullBloomVfx.Shape.looseLimit(9));
        assertEquals(240, FullBloomVfx.Shape.looseLimit(12));
        // VOLUME: 84 roots + 28 trunk + 35 branch + 16 blossoms + 320 loose
        // petals + 24 ground + 14 contact + 3 source quads = 524 (no crown yet).
        assertEquals(4, FullBloomVfx.Shape.roots(8, 10));
        assertEquals(5, FullBloomVfx.Shape.branches(8, 10));
        assertEquals(8, FullBloomVfx.Shape.clusters(8, 10));
        assertEquals(0, FullBloomVfx.Shape.crown(8, 10));
        assertEquals(160, FullBloomVfx.Shape.looseLimit(10));
        // At star 11: 126 roots + 28 trunk + 35 branch + 189 canopy wood
        // + 24 blossoms + 48 crown + 400 loose + 41 ground/contact/source = 891.
        int[] ceilings = {0, 0, 0, 0, 0, 24, 27, 27, 27, 51, 524, 891, 1067};
        int previous = 0;
        for (int stars = 0; stars <= 12; stars++) {
            int ceiling = FullBloomVfx.Shape.quadCeiling(8, stars);
            assertEquals(ceilings[stars], ceiling, "stars " + stars);
            assertTrue(ceiling >= previous);
            previous = ceiling;
        }
        assertEquals(1067, previous);
        assertTrue(FullBloomVfx.Shape.roots(8, 10) < FullBloomVfx.Shape.roots(8, 12));
        assertTrue(FullBloomVfx.Shape.clusters(8, 10) < FullBloomVfx.Shape.clusters(8, 11));
        assertFalse(FullBloomVfx.Shape.has(5, PlumLayers.Kind.TRAIL));
        assertTrue(FullBloomVfx.Shape.has(6, PlumLayers.Kind.TRAIL));
        assertFalse(FullBloomVfx.Shape.has(6, PlumLayers.Kind.GHOST));
        assertTrue(FullBloomVfx.Shape.has(7, PlumLayers.Kind.GHOST));
    }
    @Test void zeroIsEmptyAndEveryRowAddsWithoutChangingTheLook() {
        assertEquals(0, FullBloomVfx.Shape.roots(0));
        assertEquals(0, FullBloomVfx.Shape.flowers(0));
        assertEquals(0, FullBloomVfx.Shape.crown(0));
        assertEquals(0, FullBloomVfx.Shape.reach(0));
        assertEquals(0, FullBloomVfx.Shape.quadCeiling(0));
        for (int tier = 1; tier <= 8; tier++) {
            assertTrue(FullBloomVfx.Shape.roots(tier) >= FullBloomVfx.Shape.roots(tier - 1));
            assertTrue(FullBloomVfx.Shape.flowers(tier) >= FullBloomVfx.Shape.flowers(tier - 1));
            assertTrue(FullBloomVfx.Shape.crown(tier) >= FullBloomVfx.Shape.crown(tier - 1));
            assertTrue(FullBloomVfx.Shape.reach(tier) >= 3 && FullBloomVfx.Shape.reach(tier) <= 7);
            assertTrue(FullBloomVfx.Shape.quadCeiling(tier) <= 1064);
        }
        assertEquals(0, FullBloomVfx.Shape.crown(5));
        assertEquals(16, FullBloomVfx.Shape.crown(6));
        assertEquals(48, FullBloomVfx.Shape.crown(8));
        assertEquals(12, FullBloomVfx.Shape.flowers(8));
    }
    @Test void clustersRespectTheExistingRowsAndQuadBudget() {
        int[] ceilings = {0, 518, 651, 715, 744, 779, 1000, 1032, 1064};
        for (int tier = 0; tier <= 8; tier++) {
            assertEquals(FullBloomVfx.Shape.nodes(tier) * FullBloomVfx.Shape.blossomsPerNode(tier), FullBloomVfx.Shape.clusters(tier));
            assertEquals(ceilings[tier], FullBloomVfx.Shape.quadCeiling(tier), "tier " + tier);
        }
        assertEquals(0, FullBloomVfx.Shape.clusters(2));
        assertEquals(15, FullBloomVfx.Shape.clusters(8));
        assertEquals(5, FullBloomVfx.Shape.nodes(8));
        for (int tier = 3; tier <= 8; tier++) {
            assertTrue(FullBloomVfx.Shape.blossomsPerNode(tier) >= 2);
            assertTrue(FullBloomVfx.Shape.blossomsPerNode(tier) <= 4);
        }
    }
    @Test void holdSheddingAndWoodFadeAreSeparateAndTipFirst() {
        assertEquals(70, FullBloomVfx.Shape.settleStart(26, 20));
        assertEquals(110, FullBloomVfx.Shape.settleStart(110, 20));
        assertEquals(1, FullBloomVfx.Shape.blossomFade(-1));
        assertEquals(1, FullBloomVfx.Shape.blossomFade(0));
        assertEquals(0, FullBloomVfx.Shape.blossomFade(40));
        assertEquals(1, FullBloomVfx.Shape.woodFade(40, 1));
        assertEquals(0, FullBloomVfx.Shape.woodFade(50, 1));
        assertEquals(1, FullBloomVfx.Shape.woodFade(50, 0));
        assertEquals(0, FullBloomVfx.Shape.woodFade(70, 0));
        for (int i = 0; i <= 100; i++) {
            double u = i / 100.;
            assertTrue(FullBloomVfx.Shape.trunkDiameter(u) >= .12 - 1e-9);
            assertTrue(FullBloomVfx.Shape.trunkDiameter(u) <= .55);
        }
    }
    @Test void trunkReachesCanopyAndEntireBarkStaysBehindCaster() {
        assertEquals(0, FullBloomVfx.Shape.trunkY(0));
        assertEquals(7.8, FullBloomVfx.Shape.trunkY(4));
        assertEquals(1.05, FullBloomVfx.Shape.trunkY(1));
        for (int node = 0; node <= 4; node++) {
            assertTrue(FullBloomVfx.Shape.trunkRear(node) + .35 / 2 < -.6);
            if (node > 0) assertTrue(FullBloomVfx.Shape.trunkY(node) > FullBloomVfx.Shape.trunkY(node - 1));
        }
    }
    @Test void entireCrownIncludingFoldStaysAboveHeads() {
        for (int i = 0; i < 48; i++) {
            double plane = new double[]{1, .8, .65}[i % 3];
            double size = (.58 + i % 3 * .15) * plane;
            double lowest = FullBloomVfx.Shape.crownY(i) - size / Math.sqrt(2) - size * .13;
            assertTrue(lowest >= 4, "crown node " + i);
        }
    }
    @Test void woodTapersAndEnvelopesNeverOvershoot() {
        assertEquals(.25, FullBloomVfx.Shape.diameter(0), 1e-9);
        assertEquals(.08, FullBloomVfx.Shape.diameter(1), 1e-9);
        double previous = 1;
        for (int i = 0; i <= 100; i++) {
            double diameter = FullBloomVfx.Shape.diameter(i / 100.);
            assertTrue(diameter >= .08 - 1e-9 && diameter <= previous);
            previous = diameter;
            double envelope = FullBloomVfx.Shape.smooth(i / 100.);
            assertTrue(envelope >= 0 && envelope <= 1);
        }
        assertEquals(0, FullBloomVfx.Shape.smooth(-1));
        assertEquals(1, FullBloomVfx.Shape.smooth(2));
    }
}
