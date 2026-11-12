package io.github.verycooltimo.murim.sect;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stuck watch of a walk without a route plan measures the way left along the path (penance guard, 11.11). */
class SectWalkingProgressTest {

    @Test
    @DisplayName("a detour that leads away from the goal still counts as headway")
    void detourIsHeadway() {
        Vec3 goal = new Vec3(0.5D, 238.0D, 28.5D);
        // cave mouth behind the houses: north 8 blocks, east 3, then south down the alley (cell centres are +0.5)
        List<Vec3> way = List.of(new Vec3(0, 236, -8), new Vec3(3, 236, -8), new Vec3(3, 236, 20), new Vec3(0, 238, 28));
        Vec3 start = new Vec3(0.5D, 236.0D, 0.5D);
        Vec3 farNorth = new Vec3(0.5D, 236.0D, -7.5D);
        double straightStart = Math.hypot(goal.x - start.x, goal.z - start.z);
        double straightNorth = Math.hypot(goal.x - farNorth.x, goal.z - farNorth.z);
        assertTrue(straightNorth > straightStart, "fixture: the straight distance must grow on the detour");
        double alongStart = SectWalking.alongPath(start, way, goal);
        double alongNorth = SectWalking.alongPath(farNorth, way.subList(1, way.size()), goal);
        assertTrue(alongNorth < alongStart - 1.0D, "the way left must shrink while he walks away from the goal: " + alongNorth + " vs " + alongStart);
    }
}
