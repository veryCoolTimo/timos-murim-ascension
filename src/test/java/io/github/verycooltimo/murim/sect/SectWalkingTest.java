package io.github.verycooltimo.murim.sect;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Heights the pathfinder and the walker disagree about: a body on a half slab or a rug (17.10 day simulation). */
class SectWalkingTest {

    @Test
    @DisplayName("A body on a half slab stands in the slab block; the cell it stands in for the pathfinder is the one above")
    void feetCellOnSlab() {
        assertEquals(new BlockPos(-1067, 226, 4222), SectWalking.feetCell(new Vec3(-1066.5D, 225.5D, 4222.5D)));
        assertEquals(new BlockPos(5, 64, 5), SectWalking.feetCell(new Vec3(5.5D, 64.0D, 5.5D)));
        assertEquals(new BlockPos(5, 65, 5), SectWalking.feetCell(new Vec3(5.5D, 64.075D, 5.5D)));
    }

    @Test
    @DisplayName("A path made for the block above a slab is the path for a point on the slab")
    void sameTargetTolerance() {
        assertTrue(SectWalking.sameTarget(new BlockPos(-1066, 226, 4222), new Vec3(-1065.5D, 225.5D, 4222.5D)));
        assertTrue(SectWalking.sameTarget(new BlockPos(-1066, 225, 4222), new Vec3(-1065.5D, 225.5D, 4222.5D)));
        assertFalse(SectWalking.sameTarget(new BlockPos(-1065, 226, 4222), new Vec3(-1065.5D, 225.5D, 4222.5D)));
        assertFalse(SectWalking.sameTarget(new BlockPos(-1066, 229, 4222), new Vec3(-1065.5D, 225.5D, 4222.5D)));
    }
}
