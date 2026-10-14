package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Thread-bound books and ledgers (ref_09/ref_10 piles), 1-4 piles in one block: using another stack on it adds a pile,
 * like candles and sea pickles; drops as many items as it has piles (loot from tools/art/hall_furniture.py). Needs a
 * floor that holds its centre. Pile layout per count: STACKS in the generator.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/CandleBlock.java (#canBeReplaced,
 * #getStateForPlacement), SeaPickleBlock.java (#canSurvive, #updateShape), Block.java#canSupportCenter.
 */
public class BookStackBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<BookStackBlock> CODEC = simpleCodec(BookStackBlock::new);
    public static final int MAX = 4;
    public static final IntegerProperty STACKS = IntegerProperty.create("stacks", 1, MAX);

    /** Pile footprints (centre x, z, height px) in the south frame, matching STACKS in the generator. */
    private static final double[][][] PILES = {
            {{8.0, 8.0, 4.25}},
            {{4.6, 7.0, 5.1}, {11.4, 9.0, 2.55}},
            {{4.6, 4.8, 3.4}, {11.4, 6.0, 5.1}, {6.5, 12.0, 1.7}},
            {{4.4, 4.4, 5.1}, {11.6, 4.8, 2.55}, {4.8, 11.8, 1.7}, {11.6, 11.6, 4.25}},
    };
    private static final Map<Integer, VoxelShape> SHAPES = new HashMap<>();

    static {
        for (int n = 1; n <= MAX; n++) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                VoxelShape s = Shapes.empty();
                for (double[] p : PILES[n - 1]) {
                    s = Shapes.or(s, HallFurniture.turn(Math.max(0, p[0] - 2.65), 0, Math.max(0, p[1] - 2.9),
                            Math.min(16, p[0] + 2.65), p[2], Math.min(16, p[1] + 2.9), d));
                }
                SHAPES.put(n * 8 + d.get2DDataValue(), s);
            }
        }
    }

    public BookStackBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH).setValue(STACKS, 1));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState here = context.getLevel().getBlockState(context.getClickedPos());
        if (here.is(this)) {
            return here.setValue(STACKS, Math.min(MAX, here.getValue(STACKS) + 1));
        }
        BlockState s = defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        return s.canSurvive(context.getLevel(), context.getClickedPos()) ? s : null;
    }

    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        return !context.isSecondaryUseActive() && context.getItemInHand().is(asItem()) && state.getValue(STACKS) < MAX
                || super.canBeReplaced(state, context);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return Block.canSupportCenter(level, pos.below(), Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        if (dir == Direction.DOWN && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(STACKS) * 8 + state.getValue(FACING).get2DDataValue());
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
        builder.add(FACING, STACKS);
    }
}
