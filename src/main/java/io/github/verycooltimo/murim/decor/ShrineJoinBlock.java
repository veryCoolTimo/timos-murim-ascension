package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Long altar table that joins along its length: a neighbour of the same facing on the viewer's left or right turns the
 * ends into a continuous top (JOIN = single, left end, mid, right end). Tabletop items (candles, tablets, burner) stand
 * on it: their models start 1 px below their block, the altar is 15 px high.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/StairBlock.java (#updateShape recomputing a shape
 * property from neighbours).
 */
public class ShrineJoinBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<ShrineJoinBlock> CODEC = simpleCodec(p -> new ShrineJoinBlock(p, ShrineSpec.DUMMY));
    public static final EnumProperty<Join> JOIN = EnumProperty.create("join", Join.class);

    public enum Join implements StringRepresentable {
        SINGLE("single"), LEFT("left"), MID("mid"), RIGHT("right");

        private final String name;

        Join(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    private final VoxelShape[][][] shapes;

    public ShrineJoinBlock(Properties properties, ShrineSpec spec) {
        super(properties);
        this.shapes = ShrineBlock.buildShapes(spec);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(JOIN, Join.SINGLE));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    private Join joinAt(BlockGetter level, BlockPos pos, Direction facing) {
        Direction right = facing.getCounterClockWise();
        boolean onRight = same(level.getBlockState(pos.relative(right)), facing);
        boolean onLeft = same(level.getBlockState(pos.relative(right.getOpposite())), facing);
        return onRight && onLeft ? Join.MID : onRight ? Join.LEFT : onLeft ? Join.RIGHT : Join.SINGLE;
    }

    private boolean same(BlockState s, Direction facing) {
        return s.is(this) && s.getValue(FACING) == facing;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing).setValue(JOIN, joinAt(context.getLevel(), context.getClickedPos(), facing));
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        if (dir.getAxis().isHorizontal()) {
            return state.setValue(JOIN, joinAt(level, pos, state.getValue(FACING)));
        }
        return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes[0][0][state.getValue(FACING).get2DDataValue()];
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, JOIN);
    }
}
