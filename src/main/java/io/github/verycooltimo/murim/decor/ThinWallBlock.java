package io.github.verycooltimo.murim.decor;

import com.google.common.collect.ImmutableMap;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Half-thick wall panel (author 07.10: «в пол-ширины блока, чтобы как тонкая стена была»), 8 px thick, full height.
 *
 * <p>Two modes. EDGE (center=false): the wall fills the half of the block towards FACING, like a vertical slab;
 * placed against the clicked face. Corners work like stairs seen from above (the stair's upper step has exactly this
 * footprint): SHAPE is computed with {@link net.minecraft.world.level.block.StairBlock}'s rule, OUTER = the quarter
 * at FACING and the side the neighbour leans to, INNER = FACING half plus that side's half. CENTER (Shift-place): an
 * 8 px band through the middle across FACING; NORTH/EAST/SOUTH/WEST are its arms from the 8x8 middle post to the
 * block edges, so a corner is a clean L and a junction a T or cross. An arm along the wall's own line is on when a
 * centred thin wall is there or when nothing turns off the wall; an arm across it only towards a centred thin wall
 * that runs that way. Any thin wall texture joins any other (they are one set).
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/StairBlock.java (#getStairsShape, #canTakeShape,
 * #rotate, #mirror), IronBarsBlock.java / CrossCollisionBlock.java (arm booleans), state/BlockBehaviour.java
 * (#getOcclusionShape defaults to the shape; #useShapeForLightOcclusion), item/context/UseOnContext.java
 * (#isSecondaryUseActive = sneaking).
 */
public class ThinWallBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<ThinWallBlock> CODEC = simpleCodec(ThinWallBlock::new);
    public static final EnumProperty<StairsShape> SHAPE = BlockStateProperties.STAIRS_SHAPE;
    public static final BooleanProperty CENTER = BooleanProperty.create("center");
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty EAST = BlockStateProperties.EAST;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty WEST = BlockStateProperties.WEST;

    private final Map<BlockState, VoxelShape> shapes;

    public ThinWallBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SHAPE, StairsShape.STRAIGHT)
                .setValue(CENTER, false).setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false).setValue(WEST, false));
        ImmutableMap.Builder<BlockState, VoxelShape> b = ImmutableMap.builder();
        for (BlockState s : stateDefinition.getPossibleStates()) {
            b.put(s, makeShape(s));
        }
        shapes = b.build();
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    public static BooleanProperty arm(Direction d) {
        return switch (d) {
            case NORTH -> NORTH;
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            default -> WEST;
        };
    }

    /** The half of the block towards {@code d}, full height. */
    private static VoxelShape half(Direction d) {
        return switch (d) {
            case NORTH -> Block.box(0, 0, 0, 16, 16, 8);
            case SOUTH -> Block.box(0, 0, 8, 16, 16, 16);
            case WEST -> Block.box(0, 0, 0, 8, 16, 16);
            default -> Block.box(8, 0, 0, 16, 16, 16);
        };
    }

    private static VoxelShape armBox(Direction d) {
        return switch (d) {
            case NORTH -> Block.box(4, 0, 0, 12, 16, 4);
            case SOUTH -> Block.box(4, 0, 12, 12, 16, 16);
            case WEST -> Block.box(0, 0, 4, 4, 16, 12);
            default -> Block.box(12, 0, 4, 16, 16, 12);
        };
    }

    /** The side the corner turns to: LEFT shapes lean to the counter-clockwise side, like stairs. */
    private static Direction side(Direction facing, StairsShape shape) {
        return shape == StairsShape.INNER_LEFT || shape == StairsShape.OUTER_LEFT ? facing.getCounterClockWise() : facing.getClockWise();
    }

    private static VoxelShape makeShape(BlockState s) {
        Direction f = s.getValue(FACING);
        if (s.getValue(CENTER)) {
            VoxelShape out = Block.box(4, 0, 4, 12, 16, 12);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (s.getValue(arm(d))) {
                    out = Shapes.or(out, armBox(d));
                }
            }
            return out;
        }
        StairsShape shape = s.getValue(SHAPE);
        return switch (shape) {
            case STRAIGHT -> half(f);
            case INNER_LEFT, INNER_RIGHT -> Shapes.or(half(f), half(side(f, shape)));
            default -> Shapes.join(half(f), half(side(f, shape)), net.minecraft.world.phys.shapes.BooleanOp.AND);
        };
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state);
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    // ------------------------------------------------------------------ connections

    private static boolean isEdge(BlockState s) {
        return s.getBlock() instanceof ThinWallBlock && !s.getValue(CENTER);
    }

    private static boolean isCentre(BlockState s) {
        return s.getBlock() instanceof ThinWallBlock && s.getValue(CENTER);
    }

    /** The axis a wall runs along: across its facing. */
    private static Direction.Axis runAxis(BlockState s) {
        return s.getValue(FACING).getClockWise().getAxis();
    }

    /** StairBlock#getStairsShape with "stairs of the same half" read as "edge thin wall". */
    private static StairsShape edgeShape(BlockState state, BlockGetter level, BlockPos pos) {
        Direction f = state.getValue(FACING);
        BlockState front = level.getBlockState(pos.relative(f));
        if (isEdge(front)) {
            Direction d = front.getValue(FACING);
            if (d.getAxis() != f.getAxis() && canTakeShape(state, level, pos, d.getOpposite())) {
                return d == f.getCounterClockWise() ? StairsShape.OUTER_LEFT : StairsShape.OUTER_RIGHT;
            }
        }
        BlockState back = level.getBlockState(pos.relative(f.getOpposite()));
        if (isEdge(back)) {
            Direction d = back.getValue(FACING);
            if (d.getAxis() != f.getAxis() && canTakeShape(state, level, pos, d)) {
                return d == f.getCounterClockWise() ? StairsShape.INNER_LEFT : StairsShape.INNER_RIGHT;
            }
        }
        return StairsShape.STRAIGHT;
    }

    private static boolean canTakeShape(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
        BlockState s = level.getBlockState(pos.relative(face));
        return !isEdge(s) || s.getValue(FACING) != state.getValue(FACING);
    }

    private static BlockState connect(BlockState state, BlockGetter level, BlockPos pos) {
        if (!state.getValue(CENTER)) {
            return state.setValue(SHAPE, edgeShape(state, level, pos))
                    .setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false).setValue(WEST, false);
        }
        Direction.Axis own = runAxis(state);
        boolean anyCross = false;
        int inline = 0;
        boolean[] link = new boolean[4];
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState n = level.getBlockState(pos.relative(d));
            boolean l = d.getAxis() == own ? isCentre(n) : isCentre(n) && runAxis(n) == d.getAxis();
            link[d.get2DDataValue()] = l;
            anyCross |= l && d.getAxis() != own;
            inline += l && d.getAxis() == own ? 1 : 0;
        }
        // The own line is cut back only at a corner: a turn (cross link) with the line arriving from one side.
        BlockState out = state.setValue(SHAPE, StairsShape.STRAIGHT);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            boolean linked = link[d.get2DDataValue()];
            boolean on = d.getAxis() == own ? linked || !(anyCross && inline == 1) : linked;
            out = out.setValue(arm(d), on);
        }
        return out;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clicked = context.getClickedFace();
        boolean centre = context.isSecondaryUseActive();
        Direction facing = centre || clicked.getAxis().isVertical()
                ? context.getHorizontalDirection() : clicked.getOpposite();
        BlockState state = defaultBlockState().setValue(FACING, facing).setValue(CENTER, centre);
        return connect(state, context.getLevel(), context.getClickedPos());
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        return dir.getAxis().isHorizontal() ? connect(state, level, pos) : state;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        BlockState out = state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        for (Direction d : Direction.Plane.HORIZONTAL) {
            out = out.setValue(arm(rotation.rotate(d)), state.getValue(arm(d)));
        }
        return out;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        if (mirror == Mirror.NONE) {
            return state;
        }
        BlockState out = state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
        for (Direction d : Direction.Plane.HORIZONTAL) {
            out = out.setValue(arm(mirror.mirror(d)), state.getValue(arm(d)));
        }
        StairsShape s = state.getValue(SHAPE);
        StairsShape m = switch (s) {
            case INNER_LEFT -> StairsShape.INNER_RIGHT;
            case INNER_RIGHT -> StairsShape.INNER_LEFT;
            case OUTER_LEFT -> StairsShape.OUTER_RIGHT;
            case OUTER_RIGHT -> StairsShape.OUTER_LEFT;
            default -> s;
        };
        return out.setValue(SHAPE, m);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SHAPE, CENTER, NORTH, EAST, SOUTH, WEST);
    }
}
