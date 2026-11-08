package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;

/** Access correction for an already generated office; the bake and repairs use the same clear lanes (ADR 0023). */
public final class SectOffice {
    private SectOffice() { }

    public static void ensureAccess(SectDisciple npc, SectLayoutFile file) {
        // The actual anchored armchair supplies the orientation; no world-coordinate assumption in an old save.
        for (var seat : file.spots()) {
            if (!"treasury".equals(seat.building()) || !"seat_armchair".equals(seat.kind())
                    || !seat.roles().contains("elder_treasury")) continue;
            Direction front = Direction.fromYRot(seat.yaw());
            Direction side = front.getClockWise();
            for (int sign : new int[] {-1, 1}) {
                for (int forward = 0; forward <= 2; forward++) {
                    for (int y = 0; y <= 1; y++) {
                        var cell = seat.pos().offset(side.getStepX() * sign * 2 + front.getStepX() * forward, y,
                                side.getStepZ() * sign * 2 + front.getStepZ() * forward);
                        if (!npc.level().isLoaded(cell)) continue;
                        var id = BuiltInRegistries.BLOCK.getKey(npc.level().getBlockState(cell).getBlock());
                        if ("murim".equals(id.getNamespace()) && (id.getPath().equals("sto_cabinet")
                                || id.getPath().equals("sto_chest") || id.getPath().startsWith("sto_bonsai"))) {
                            npc.level().setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                }
            }
        }
    }
}
