package io.github.verycooltimo.murim.decor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Shared pixel offset for props occupying the cell above a low table. */
public final class TabletopSupport {
    // Eight pixels are needed for a bottom slab (top = 8); low desks use seven.
    public static final IntegerProperty SINK = IntegerProperty.create("sink", 0, 8);

    private TabletopSupport() {
    }

    private static VoxelShape surface(BlockGetter level, BlockPos below) {
        BlockState state = level.getBlockState(below);
        return Shapes.or(state.getVisualShape(level, below, CollisionContext.empty()),
                state.getCollisionShape(level, below));
    }

    public static int sink(BlockGetter level, BlockPos pos) {
        VoxelShape surface = surface(level, pos.below());
        if (surface.isEmpty()) {
            return 0;
        }
        double top = surface.max(Direction.Axis.Y) * 16.0D;
        return top >= 8 && top < 16 ? (int) Math.round(16 - top) : 0;
    }

    public static BlockState onSupport(BlockState state, BlockGetter level, BlockPos pos) {
        return state.setValue(SINK, sink(level, pos));
    }

    public static VoxelShape lower(VoxelShape shape, BlockState state) {
        return shape.move(0, -state.getValue(SINK) / 16.0D, 0);
    }

    /** Books still need support under their centre, including a recessed tabletop. */
    public static boolean supportsBooks(LevelReader level, BlockPos pos) {
        if (Block.canSupportCenter(level, pos.below(), Direction.UP)) {
            return true;
        }
        VoxelShape surface = surface(level, pos.below());
        return !surface.isEmpty() && surface.max(Direction.Axis.Y) >= 0.5D
                && surface.max(Direction.Axis.Y) < 1.0D
                && Shapes.joinIsNotEmpty(surface, Block.box(7, 0, 7, 9, 16, 9), BooleanOp.AND);
    }
}
