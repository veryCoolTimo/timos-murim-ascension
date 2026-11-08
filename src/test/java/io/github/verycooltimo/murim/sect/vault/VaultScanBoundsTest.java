package io.github.verycooltimo.murim.sect.vault;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VaultScanBoundsTest {
    @Test void discoveryAndExitOnlyScanWithinTheirMargins() {
        var bounds = new BoundingBox(100, 50, 200, 140, 70, 260);
        for (int margin : new int[] {3, 8}) {
            assertTrue(HuaVault.near(bounds, new BlockPos(120, 60, 230), margin));
            assertTrue(HuaVault.near(bounds, new BlockPos(100 - margin, 50 - margin, 200 - margin), margin));
            assertTrue(HuaVault.near(bounds, new BlockPos(140 + margin, 70 + margin, 260 + margin), margin));
            assertFalse(HuaVault.near(bounds, new BlockPos(99 - margin, 60, 230), margin));
            assertFalse(HuaVault.near(bounds, new BlockPos(120, 71 + margin, 230), margin));
            assertFalse(HuaVault.near(bounds, new BlockPos(120, 60, 261 + margin), margin));
            assertFalse(HuaVault.near(bounds, BlockPos.ZERO, margin));
        }
    }
}
