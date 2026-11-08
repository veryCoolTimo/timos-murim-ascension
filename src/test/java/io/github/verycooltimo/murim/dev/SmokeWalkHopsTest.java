package io.github.verycooltimo.murim.dev;

import org.junit.jupiter.api.Test;

import static io.github.verycooltimo.murim.dev.SmokeWalkHops.Fluid.*;
import static org.junit.jupiter.api.Assertions.*;

class SmokeWalkHopsTest {
    private static SmokeWalkHops.Surface land(double y) { return new SmokeWalkHops.Surface(y, LAND); }

    @Test void findsGroundUnderHighTrunkAndClosedCanopy() {
        var ground = SmokeWalkHops.ground(64, 90, y -> y == 63 ? SmokeWalkHops.Block.SOLID
                : y >= 80 ? SmokeWalkHops.Block.TRUNK
                : y >= 68 ? SmokeWalkHops.Block.LEAVES : SmokeWalkHops.Block.PASSABLE);
        assertEquals(64, ground.y());
        assertEquals("", ground.obstruction());
    }

    @Test void searchesAboveAndBelowPlayerWithinTwelveBlocks() {
        for (int floor : new int[]{51, 63, 75}) {
            var ground = SmokeWalkHops.ground(64, 100, y -> y == floor ? SmokeWalkHops.Block.SOLID
                    : SmokeWalkHops.Block.PASSABLE);
            assertEquals(floor + 1, ground.y());
            assertEquals("", ground.obstruction());
        }
        assertEquals("blocked_solid", SmokeWalkHops.ground(64, 100,
                y -> y == 76 ? SmokeWalkHops.Block.SOLID : SmokeWalkHops.Block.PASSABLE).obstruction());
    }

    @Test void neverUsesTreeTopAsGroundAndClassifiesObstructions() {
        assertEquals("blocked_trunk", SmokeWalkHops.ground(64, 70,
                y -> y >= 63 && y < 70 ? SmokeWalkHops.Block.TRUNK : SmokeWalkHops.Block.PASSABLE).obstruction());
        assertEquals("blocked_leaves", SmokeWalkHops.blocked(SmokeWalkHops.Block.PASSABLE, SmokeWalkHops.Block.LEAVES));
        assertEquals("blocked_trunk", SmokeWalkHops.blocked(SmokeWalkHops.Block.TRUNK, SmokeWalkHops.Block.LEAVES));
        assertEquals("blocked_solid", SmokeWalkHops.blocked(SmokeWalkHops.Block.SOLID, SmokeWalkHops.Block.PASSABLE));
        assertEquals("blocked_solid", SmokeWalkHops.blocked(SmokeWalkHops.Block.OBSTACLE, SmokeWalkHops.Block.PASSABLE));
    }

    @Test void reportsEachForestObstruction() {
        for (String reason : java.util.List.of("blocked_trunk", "blocked_leaves", "blocked_solid")) {
            var result = SmokeWalkHops.select(.5, 64, .5, 500, .5,
                    (x, z) -> new SmokeWalkHops.Surface(64, LAND, reason), hop -> false);
            assertNull(result.hop());
            assertEquals(28, result.rejections().get(reason).intValue());
        }
    }

    @Test void cutsOnlyLeaves() {
        for (var block : SmokeWalkHops.Block.values()) {
            assertEquals(block == SmokeWalkHops.Block.LEAVES, SmokeWalkHops.cutLeaves(block));
        }
    }

    @Test void picksReachableLandHop() {
        var result = SmokeWalkHops.select(.5, 64, .5, 500, .5, (x, z) -> land(64), hop -> true);
        assertFalse(result.fallback());
        assertEquals(32.5, result.hop().x());
        assertEquals(.5, result.hop().z());
        assertEquals(64, result.hop().y());
    }

    @Test void sidestepsWaterBeforeSwimming() {
        var result = SmokeWalkHops.select(.5, 64, .5, 500, .5,
                (x, z) -> new SmokeWalkHops.Surface(64, x >= 4 && Math.abs(z) < 2 ? WATER : LAND), hop -> true);
        assertFalse(result.hop().swimming());
        assertTrue(Math.abs(result.hop().z() - .5) >= 8);
    }

    @Test void shortensBeforeCliffEvenWhenEndpointBeyondRavineIsLand() {
        var result = SmokeWalkHops.select(.5, 64, .5, 500, .5,
                (x, z) -> land(x >= 20 && x <= 22 ? 55 : 64), hop -> true);
        assertFalse(result.fallback());
        assertEquals(16.5, result.hop().x());
    }

    @Test void triesAnotherHopWhenNavigationRejectsFirst() {
        var result = SmokeWalkHops.select(.5, 64, .5, 500, .5,
                (x, z) -> land(64), hop -> hop.z() > 1);
        assertFalse(result.fallback());
        assertEquals(8.5, result.hop().z());
    }

    @Test void usesEightBlockHopAtLoadedFrontier() {
        var result = SmokeWalkHops.select(.5, 64, .5, 500, .5,
                (x, z) -> x > 10 ? null : land(64), hop -> true);
        assertFalse(result.fallback());
        assertEquals(8.5, result.hop().x());
    }

    @Test void allowsInlandSwimmingWhenLandCannotBeReached() {
        var result = SmokeWalkHops.select(.5, 64, .5, 500, .5,
                (x, z) -> new SmokeWalkHops.Surface(64, WATER), hop -> true);
        assertFalse(result.fallback());
        assertTrue(result.hop().swimming());
    }

    @Test void fallsBackToBestSafeHopWhenAllPathsFail() {
        var result = SmokeWalkHops.select(.5, 64, .5, 500, .5, (x, z) -> land(64), hop -> false);
        assertTrue(result.fallback());
        assertEquals(32.5, result.hop().x());
        for (int failures = 0; failures < 6; failures++) assertFalse(SmokeWalkHops.exhausted(failures));
        assertTrue(SmokeWalkHops.exhausted(6));
    }

    @Test void refusesOceanLavaAndUnloadedTerrainEvenForSteering() {
        for (SmokeWalkHops.Terrain terrain : java.util.List.<SmokeWalkHops.Terrain>of(
                (x, z) -> new SmokeWalkHops.Surface(64, OCEAN),
                (x, z) -> new SmokeWalkHops.Surface(64, LAVA),
                (x, z) -> null)) {
            var result = SmokeWalkHops.select(.5, 64, .5, 500, .5, terrain,
                    hop -> { fail("Unsafe candidate sent to navigation"); return true; });
            assertTrue(result.fallback());
            assertNull(result.hop());
        }
    }
    @Test void entersDescentOnlyOnLeavesOverPassableSpace() {
        assertTrue(SmokeWalkHops.canopy(true, true, true)); // Leaf over air or another collision-free block.
        assertFalse(SmokeWalkHops.canopy(true, true, false)); // Bush directly on solid ground.
        assertFalse(SmokeWalkHops.canopy(false, true, true)); // Must be standing on the leaf.
        assertFalse(SmokeWalkHops.canopy(true, false, true));
    }

    @Test void exitsAfterThreeSecondsWithoutDownwardProgress() {
        var progress = new SmokeWalkHops.DescentProgress(77, 100);
        assertFalse(progress.stalled(159));
        assertTrue(progress.stalled(160));
        assertTrue(progress.stalled(260)); // Also guarantees the eight-second upper bound.
        progress = progress.sample(78, 120).sample(77, 140);
        assertTrue(progress.stalled(160)); // Upward motion cannot extend the deadline.
        progress = progress.sample(76, 150);
        assertFalse(progress.stalled(209));
        assertTrue(progress.stalled(210));
    }

    @Test void minesCanopySupportOrEnclosingLeavesButWaitsWhileFalling() {
        assertTrue(SmokeWalkHops.descend(true, true, false));
        assertTrue(SmokeWalkHops.descend(true, false, true));
        assertFalse(SmokeWalkHops.descend(true, false, false));
        assertFalse(SmokeWalkHops.descend(false, true, false));
    }

    @Test void steeringRequiresTwoBlocksFromItsOwnStart() {
        assertFalse(SmokeWalkHops.steeringProgress(-8.5, -4.5, -8.5, -4.5));
        assertFalse(SmokeWalkHops.steeringProgress(-8.5, -4.5, -6.51, -4.5));
        assertTrue(SmokeWalkHops.steeringProgress(-8.5, -4.5, -6.5, -4.5));
        assertTrue(SmokeWalkHops.steeringProgress(0, 0, 1.2, 1.6));
        assertFalse(SmokeWalkHops.steeringProgress(0, 0, 1, 1));
    }

    @Test void descentStaysActiveUntilSolidNonLeafGround() {
        assertTrue(SmokeWalkHops.descending(false, true, false));
        assertTrue(SmokeWalkHops.descending(true, false, false));
        assertFalse(SmokeWalkHops.descending(true, true, true));
        assertFalse(SmokeWalkHops.descending(false, false, false));
    }

    @Test void descentGuardAllowsSixteenBlocksOnlyAboveHalfHealth() {
        assertEquals("", SmokeWalkHops.fallFailure(true, 16, 10.01, 20));
        assertEquals("fall_over_16_blocks", SmokeWalkHops.fallFailure(true, 16.01, 20, 20));
        assertEquals("canopy_health_at_or_below_half", SmokeWalkHops.fallFailure(true, 4, 10, 20));
        assertEquals("canopy_health_at_or_below_half", SmokeWalkHops.fallFailure(true, 0, 0, 20));
        assertEquals("canopy_health_at_or_below_half", SmokeWalkHops.fallFailure(true, 4, 20, 40));
    }

    @Test void walkingGuardAndPlannedDropsKeepThreeBlockLimit() {
        assertEquals("", SmokeWalkHops.fallFailure(false, 3, 8, 20));
        assertEquals("fall_over_3_blocks", SmokeWalkHops.fallFailure(false, 3.01, 20, 20));
        assertFalse(SmokeWalkHops.unsafeDrop(64, 61));
        assertTrue(SmokeWalkHops.unsafeDrop(64, 60.99));
        assertFalse(SmokeWalkHops.unsafeDrop(64, 65));
    }

    @Test void localCliffPrefersASideHop() {
        var result = SmokeWalkHops.select(.5, 64, .5, 20, .5,
                (x, z) -> land(x >= 4 && Math.abs(z) < 2 ? 55 : 64), hop -> true);
        assertNotNull(result.hop());
        assertFalse(result.fallback());
        assertTrue(Math.abs(result.hop().z() - .5) >= 8);
    }

    @Test void explainsEveryFailedSearch() {
        var terrains = java.util.List.<SmokeWalkHops.Terrain>of(
                (x, z) -> null,
                (x, z) -> new SmokeWalkHops.Surface(64, OCEAN),
                (x, z) -> land(50),
                (x, z) -> new SmokeWalkHops.Surface(64, LAND, true),
                (x, z) -> land(64));
        var reasons = java.util.List.of("unloaded", "water", "drop", "blocked_solid", "no_path");
        for (int i = 0; i < terrains.size(); i++) {
            var result = SmokeWalkHops.select(.5, 64, .5, 500, .5, terrains.get(i), hop -> false);
            assertTrue(result.fallback());
            assertEquals(28, result.rejections().get(reasons.get(i)).intValue());
            assertEquals(1, result.rejections().size());
        }
    }
}
