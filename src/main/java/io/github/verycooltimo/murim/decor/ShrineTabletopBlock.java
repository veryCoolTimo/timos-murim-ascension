package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Only the three portable offerings have a sink state; floor and wall shrine decor keep their original states. */
public final class ShrineTabletopBlock extends ShrineBlock {
    public static final MapCodec<ShrineTabletopBlock> CODEC = simpleCodec(p -> new ShrineTabletopBlock(p, ShrineSpec.DUMMY));

    public ShrineTabletopBlock(Properties properties, ShrineSpec spec) {
        super(properties, spec);
        registerDefaultState(defaultBlockState().setValue(TabletopSupport.SINK, 0));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return TabletopSupport.onSupport(super.getStateForPlacement(context), context.getLevel(), context.getClickedPos());
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        return dir == Direction.DOWN ? TabletopSupport.onSupport(state, level, pos)
                : super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return TabletopSupport.lower(super.getShape(state, level, pos, context), state);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        ambient(spec, 0, level, pos, random, state.getValue(TabletopSupport.SINK) / 16.0D);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(TabletopSupport.SINK);
    }
}
