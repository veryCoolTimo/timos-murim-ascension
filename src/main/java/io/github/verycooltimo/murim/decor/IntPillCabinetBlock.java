package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Two full-height drawer panels. StoScreenBlock handles placement, support, paired removal and creative breaking. */
public final class IntPillCabinetBlock extends StoScreenBlock {
    public static final MapCodec<IntPillCabinetBlock> CODEC = simpleCodec(IntPillCabinetBlock::new);

    public IntPillCabinetBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        boolean partner = state.getValue(HALF) == DoubleBlockHalf.LOWER ? direction == Direction.UP : direction == Direction.DOWN;
        if (partner && neighbour.is(this)
                && neighbour.getValue(FACING) != state.getValue(FACING)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return StoShapes.turn(0, 0, 5, 16, 16, 16, state.getValue(FACING));
    }
}
