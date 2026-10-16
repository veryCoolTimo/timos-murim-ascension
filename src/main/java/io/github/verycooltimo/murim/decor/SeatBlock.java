package io.github.verycooltimo.murim.decor;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** A block a player can sit on through a {@link SeatEntity} (cushion, chairs, stools, benches, mats). */
public interface SeatBlock {

    /** Height of the sitting surface in blocks above the block's floor; 0 or less when this state cannot be sat on. */
    double seatTop(BlockState state);

    /** The way a sitter faces, or null when the seat has no front. */
    default @Nullable Direction seatFront(BlockState state) {
        return null;
    }
}
