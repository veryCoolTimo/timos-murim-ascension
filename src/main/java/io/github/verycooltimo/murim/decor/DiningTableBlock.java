package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Low dining table (dining hall, docs/design/23-mount-hua-sect.md «длинные столы»): a table next to another table joins
 * it, so a row or a block of tables reads as one long table. NORTH/EAST/SOUTH/WEST are true towards a joined
 * neighbour; the multipart model (tools/art/hall_furniture.py) draws an edge and apron on every free side and a leg at
 * every corner whose two sides are free.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/PipeBlock.java#PROPERTY_BY_DIRECTION,
 * CrossCollisionBlock.java (#updateShape, #rotate, #mirror for four side booleans).
 */
public class DiningTableBlock extends Block {

    public static final MapCodec<DiningTableBlock> CODEC = simpleCodec(DiningTableBlock::new);
    public static final BooleanProperty NORTH = PipeBlock.NORTH;
    public static final BooleanProperty EAST = PipeBlock.EAST;
    public static final BooleanProperty SOUTH = PipeBlock.SOUTH;
    public static final BooleanProperty WEST = PipeBlock.WEST;

    private static final VoxelShape TOP = Block.box(0, 8, 0, 16, 10, 16);
    private final Map<BlockState, VoxelShape> shapes = new HashMap<>();

    public DiningTableBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false).setValue(WEST, false));
        for (BlockState s : stateDefinition.getPossibleStates()) {
            shapes.put(s, makeShape(s));
        }
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    private static boolean side(BlockState s, Direction d) {
        return s.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d));
    }

    private static VoxelShape makeShape(BlockState s) {
        VoxelShape shape = TOP;
        boolean n = side(s, Direction.NORTH), e = side(s, Direction.EAST), so = side(s, Direction.SOUTH), w = side(s, Direction.WEST);
        if (!n && !w) {
            shape = Shapes.or(shape, Block.box(0.6, 0, 0.6, 3.8, 8, 3.8));
        }
        if (!n && !e) {
            shape = Shapes.or(shape, Block.box(12.2, 0, 0.6, 15.4, 8, 3.8));
        }
        if (!so && !e) {
            shape = Shapes.or(shape, Block.box(12.2, 0, 12.2, 15.4, 8, 15.4));
        }
        if (!so && !w) {
            shape = Shapes.or(shape, Block.box(0.6, 0, 12.2, 3.8, 8, 15.4));
        }
        return shape;
    }

    private BlockState joined(BlockState state, LevelAccessor level, BlockPos pos) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            state = state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d), level.getBlockState(pos.relative(d)).is(this));
        }
        return state;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return joined(defaultBlockState(), context.getLevel(), context.getClickedPos());
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        if (dir.getAxis().isHorizontal()) {
            return state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(dir), neighbour.is(this));
        }
        return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state);
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        BlockState out = state;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            out = out.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(rotation.rotate(d)), side(state, d));
        }
        return out;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        BlockState out = state;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            out = out.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(mirror.mirror(d)), side(state, d));
        }
        return out;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST);
    }
}
