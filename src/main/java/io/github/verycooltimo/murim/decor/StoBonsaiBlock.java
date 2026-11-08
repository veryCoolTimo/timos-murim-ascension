package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Potted bonsai on a wooden stand (refs int_03, int_09); the tree rises above the block, the shape is the stand and pot. */
public class StoBonsaiBlock extends Block {
    public static final MapCodec<StoBonsaiBlock> CODEC = simpleCodec(StoBonsaiBlock::new);
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 16, 14);

    public StoBonsaiBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(TabletopSupport.SINK, 0));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return TabletopSupport.lower(SHAPE, s);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return TabletopSupport.onSupport(defaultBlockState(), context.getLevel(), context.getClickedPos());
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        return dir == Direction.DOWN ? TabletopSupport.onSupport(state, level, pos)
                : super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TabletopSupport.SINK);
    }
}
