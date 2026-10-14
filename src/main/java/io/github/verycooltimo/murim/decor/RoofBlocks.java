package io.github.verycooltimo.murim.decor;

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
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Block classes of the roof kit ({@link RoofTiles}; models and states from tools/art/roof_blocks.py). The tiles have
 * a direction: the barrel rows run along the slope, so the full block and the slab carry a horizontal AXIS taken
 * from where the placer looks, and the stairs use their own models without uvlock.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/RotatedPillarBlock.java (#rotate of an axis),
 * SlabBlock.java (#createBlockStateDefinition, #getStateForPlacement), HorizontalDirectionalBlock.java,
 * IronBarsBlock.java (#updateShape: connection booleans from the neighbour),
 * state/properties/BlockStateProperties.java#HORIZONTAL_AXIS.
 */
public final class RoofBlocks {

    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;

    private RoofBlocks() {
    }

    static Direction.Axis lookAxis(BlockPlaceContext context) {
        return context.getHorizontalDirection().getAxis();
    }

    static Direction.Axis rotate(Direction.Axis axis, Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90
                ? (axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X) : axis;
    }

    /** Full tile block: rows along AXIS (the direction the placer looked, i.e. down the slope they face). */
    public static class Tiles extends Block {
        public static final MapCodec<Tiles> CODEC = simpleCodec(Tiles::new);

        public Tiles(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X));
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState().setValue(AXIS, lookAxis(context));
        }

        @Override
        protected BlockState rotate(BlockState state, Rotation rotation) {
            return state.setValue(AXIS, RoofBlocks.rotate(state.getValue(AXIS), rotation));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(AXIS);
        }
    }

    /** Tile slab with the rows along AXIS; a second slab keeps the axis of the first. */
    public static class Slab extends SlabBlock {
        public static final MapCodec<Slab> CODEC = simpleCodec(Slab::new);

        public Slab(Properties properties) {
            super(properties);
            registerDefaultState(defaultBlockState().setValue(AXIS, Direction.Axis.X));
        }

        @Override
        public MapCodec<? extends SlabBlock> codec() {
            return CODEC;
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
            BlockState state = super.getStateForPlacement(context);
            if (state == null || state.getValue(TYPE) == SlabType.DOUBLE) {
                return state;
            }
            return state.setValue(AXIS, lookAxis(context));
        }

        @Override
        protected BlockState rotate(BlockState state, Rotation rotation) {
            return state.setValue(AXIS, RoofBlocks.rotate(state.getValue(AXIS), rotation));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(AXIS);
        }
    }

    /**
     * Eave course: FACING points up the roof (the way the placer looks, like stairs), the round tile ends sit on the
     * opposite edge. Thin: the plate ends at the height of the stairs' lower step.
     */
    public static class Eave extends HorizontalDirectionalBlock {
        public static final MapCodec<Eave> CODEC = simpleCodec(Eave::new);
        private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 4, 16);

        public Eave(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        public static BlockState orient(BlockState state, BlockGetter level, BlockPos pos) {
            Direction facing = state.getValue(FACING);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState n = level.getBlockState(pos.relative(d));
                if (n.getBlock() instanceof Slope && n.getValue(Slope.FACING) == d) {
                    return state.setValue(FACING, d);
                }
            }
            // Along the eave line: a straight neighbour supplies its roof-facing direction.
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState n = level.getBlockState(pos.relative(d));
                if (n.getBlock() instanceof Eave && !(n.getBlock() instanceof Corner)
                        && n.getValue(FACING).getAxis() != d.getAxis()) {
                    facing = n.getValue(FACING);
                    break;
                }
            }
            return state.setValue(FACING, facing);
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
            return orient(defaultBlockState().setValue(FACING, context.getHorizontalDirection()), context.getLevel(), context.getClickedPos());
        }

        @Override
        protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                         BlockPos pos, BlockPos neighbourPos) {
            return orient(state, level, pos);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return SHAPE;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }
    }

    /**
     * Flying-eave corner. The outer corner lies opposite FACING and opposite FACING's clockwise neighbour (FACING east:
     * corner at the north-west, the roof to the south-east), so FACING and its clockwise neighbour are the two
     * directions the placer looks into the roof along.
     */
    public static class Corner extends Eave {
        public static final MapCodec<Corner> CODEC = simpleCodec(Corner::new);

        public Corner(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        // The raised corner beam/horn remains inside its own cell.
        private static final VoxelShape EAST = net.minecraft.world.phys.shapes.Shapes.or(
                Block.box(0, 0, 0, 16, 4, 16), Block.box(0, 4, 0, 6, 13, 6),
                Block.box(6, 4, 6, 12, 10, 12));
        private static final VoxelShape SOUTH = net.minecraft.world.phys.shapes.Shapes.or(
                Block.box(0, 0, 0, 16, 4, 16), Block.box(10, 4, 0, 16, 13, 6),
                Block.box(4, 4, 6, 10, 10, 12));
        private static final VoxelShape WEST = net.minecraft.world.phys.shapes.Shapes.or(
                Block.box(0, 0, 0, 16, 4, 16), Block.box(10, 4, 10, 16, 13, 16),
                Block.box(4, 4, 4, 10, 10, 10));
        private static final VoxelShape NORTH = net.minecraft.world.phys.shapes.Shapes.or(
                Block.box(0, 0, 0, 16, 4, 16), Block.box(0, 4, 10, 6, 13, 16),
                Block.box(6, 4, 4, 12, 10, 10));

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            // Canonical corner is north-west. Rotate with FACING.
            return switch (state.getValue(FACING)) {
                case EAST -> EAST;
                case SOUTH -> SOUTH;
                case WEST -> WEST;
                default -> NORTH;
            };
        }

        /** The facing whose corner lies behind a placer looking along {@code look}. */
        public static Direction facingFor(Vec3 look) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                Direction cw = d.getClockWise();
                if (d.getStepX() * look.x + d.getStepZ() * look.z >= 0 && cw.getStepX() * look.x + cw.getStepZ() * look.z >= 0) {
                    return d;
                }
            }
            return Direction.EAST;
        }

        public static BlockState orientCorner(BlockState state, BlockGetter level, BlockPos pos) {
            Direction best = state.getValue(FACING);
            int score = 0;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                Direction cw = d.getClockWise();
                BlockState a = level.getBlockState(pos.relative(d));
                BlockState b = level.getBlockState(pos.relative(cw));
                int candidate = (edgeFacing(a, cw) ? 1 : 0) + (edgeFacing(b, d) ? 1 : 0);
                if (candidate > score) { best = d; score = candidate; }
            }
            return state.setValue(FACING, best);
        }

        private static boolean edgeFacing(BlockState state, Direction facing) {
            return (state.getBlock() instanceof Eave && !(state.getBlock() instanceof Corner)
                    || state.getBlock() instanceof Slope) && state.getValue(FACING) == facing;
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
            Direction facing = context.getPlayer() == null ? context.getHorizontalDirection() : facingFor(context.getPlayer().getLookAngle());
            return orientCorner(defaultBlockState().setValue(FACING, facing), context.getLevel(), context.getClickedPos());
        }

        @Override
        protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                         BlockPos pos, BlockPos neighbourPos) {
            return orientCorner(state, level, pos);
        }

        @Override
        protected BlockState mirror(BlockState state, Mirror mirror) {
            if (mirror == Mirror.NONE) {
                return state;
            }
            // a mirror turns the pair (F, cw F) into (m F, ccw m F); the new F is the one whose clockwise is m F
            return state.setValue(FACING, mirror.mirror(state.getValue(FACING).getClockWise()));
        }
    }

    /** Main ridge along AXIS; NEGATIVE / POSITIVE: joined to a ridge piece on that side (an open end gets an end tile). */
    public static class Ridge extends Block {
        public static final MapCodec<Ridge> CODEC = simpleCodec(Ridge::new);
        public static final BooleanProperty NEGATIVE = BooleanProperty.create("negative");
        public static final BooleanProperty POSITIVE = BooleanProperty.create("positive");
        // roof_polish.py: one cream rail, 4.5 px wide and 5 px high.
        private static final VoxelShape SHAPE_X = Block.box(0, 0, 5.75, 16, 5, 10.25);
        private static final VoxelShape SHAPE_Z = Block.box(5.75, 0, 0, 10.25, 5, 16);

        public Ridge(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X)
                    .setValue(NEGATIVE, false).setValue(POSITIVE, false));
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        static boolean joins(BlockState other, Direction.Axis axis) {
            return other.getBlock() instanceof Ridge && other.getValue(AXIS) == axis || other.getBlock() instanceof RidgeEnd
                    && other.getValue(RidgeEnd.FACING).getAxis() == axis;
        }

        /** Joined on side d: a ridge piece beside it, or slopes one block down carrying a ridge along this axis. */
        static boolean joinsAt(BlockGetter level, BlockPos pos, Direction d) {
            BlockPos n = pos.relative(d);
            if (joins(level.getBlockState(n), d.getAxis())) {
                return true;
            }
            BlockState below = level.getBlockState(n.below());
            return below.getBlock() instanceof Slope && below.getValue(Slope.RIDGE) != ApexRidge.NONE
                    && below.getValue(Slope.FACING).getAxis() != d.getAxis() && Slope.apex(below) == 16;
        }

        private BlockState connect(BlockState state, BlockGetter level, BlockPos pos) {
            Direction.Axis axis = state.getValue(AXIS);
            Direction neg = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE);
            return state.setValue(NEGATIVE, joinsAt(level, pos, neg)).setValue(POSITIVE, joinsAt(level, pos, neg.getOpposite()));
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
            BlockGetter level = context.getLevel();
            BlockPos pos = context.getClickedPos();
            Direction.Axis axis = lookAxis(context);
            // continue a ridge that is already there
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState n = level.getBlockState(pos.relative(d));
                if (n.getBlock() instanceof Ridge && n.getValue(AXIS) == d.getAxis()) {
                    axis = d.getAxis();
                    break;
                }
            }
            return connect(defaultBlockState().setValue(AXIS, axis), level, pos);
        }

        @Override
        protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                         BlockPos pos, BlockPos neighbourPos) {
            Direction.Axis axis = state.getValue(AXIS);
            if (dir.getAxis() != axis) {
                return state;
            }
            return state.setValue(dir.getAxisDirection() == Direction.AxisDirection.NEGATIVE ? NEGATIVE : POSITIVE,
                    joinsAt(level, pos, dir));
        }

        @Override
        protected void updateIndirectNeighbourShapes(BlockState state, LevelAccessor level, BlockPos pos, int flags, int recursionLeft) {
            refreshApexNeighbours(level, pos, flags, recursionLeft);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return state.getValue(AXIS) == Direction.Axis.X ? SHAPE_X : SHAPE_Z;
        }

        @Override
        protected BlockState rotate(BlockState state, Rotation rotation) {
            Direction.Axis axis = state.getValue(AXIS);
            boolean neg = state.getValue(NEGATIVE);
            boolean pos = state.getValue(POSITIVE);
            Direction negDir = rotation.rotate(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE));
            boolean flip = negDir.getAxisDirection() == Direction.AxisDirection.POSITIVE;
            return state.setValue(AXIS, negDir.getAxis()).setValue(NEGATIVE, flip ? pos : neg).setValue(POSITIVE, flip ? neg : pos);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(AXIS, NEGATIVE, POSITIVE);
        }
    }

    private static void refreshApexNeighbours(LevelAccessor level, BlockPos pos, int flags, int recursionLeft) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = pos.relative(d).below();
            BlockState slope = level.getBlockState(n);
            if (slope.getBlock() instanceof Slope) {
                Block.updateOrDestroy(slope, Block.updateFromNeighbourShapes(slope, level, n), level, n, flags, recursionLeft);
            }
        }
    }

    /** Raised ridge end; FACING points along the ridge it closes (toward the ridge). */
    public static class RidgeEnd extends HorizontalDirectionalBlock {
        public static final MapCodec<RidgeEnd> CODEC = simpleCodec(RidgeEnd::new);
        private static final VoxelShape CAP_X = Block.box(0, 0, 5.75, 16, 5, 10.25);
        private static final VoxelShape CAP_Z = Block.box(5.75, 0, 0, 10.25, 5, 16);
        // Modest curved end blade: its outline and every model face stay within this cell.
        private static final VoxelShape EAST = net.minecraft.world.phys.shapes.Shapes.or(CAP_X,
                Block.box(2, 3, 5.75, 12, 11, 10.25));
        private static final VoxelShape WEST = net.minecraft.world.phys.shapes.Shapes.or(CAP_X,
                Block.box(4, 3, 5.75, 14, 11, 10.25));
        private static final VoxelShape SOUTH = net.minecraft.world.phys.shapes.Shapes.or(CAP_Z,
                Block.box(5.75, 3, 2, 10.25, 11, 12));
        private static final VoxelShape NORTH = net.minecraft.world.phys.shapes.Shapes.or(CAP_Z,
                Block.box(5.75, 3, 4, 10.25, 11, 14));

        public RidgeEnd(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.EAST));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        private static BlockState orient(BlockState state, BlockGetter level, BlockPos pos) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState n = level.getBlockState(pos.relative(d));
                if (n.getBlock() instanceof Ridge && n.getValue(AXIS) == d.getAxis()) {
                    return state.setValue(FACING, d);
                }
                BlockPos below = pos.relative(d).below();
                BlockState slope = level.getBlockState(below);
                if (Slope.apex(slope) == 16 && slope.getValue(Slope.FACING).getAxis() != d.getAxis()
                        && Slope.meets(slope, level, below)) {
                    return state.setValue(FACING, d);
                }
            }
            return state;
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
            return orient(defaultBlockState().setValue(FACING, context.getHorizontalDirection()), context.getLevel(), context.getClickedPos());
        }

        @Override
        protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                         BlockPos pos, BlockPos neighbourPos) {
            return orient(state, level, pos);
        }

        @Override
        protected void updateIndirectNeighbourShapes(BlockState state, LevelAccessor level, BlockPos pos, int flags, int recursionLeft) {
            refreshApexNeighbours(level, pos, flags, recursionLeft);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return switch (state.getValue(FACING)) {
                case EAST -> EAST;
                case WEST -> WEST;
                case SOUTH -> SOUTH;
                default -> NORTH;
            };
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }
    }

    /** Projecting gable rake end in its own downslope cell; same facing/side as the panel uphill. */
    public static class RakeEnd extends Facing {
        public static final MapCodec<RakeEnd> CODEC = simpleCodec(RakeEnd::new);
        public static final EnumProperty<Side> SIDE = EaveRise.SIDE;
        private static final VoxelShape SHAPE_X = Block.box(0, 0, 5.75, 16, 11, 10.25);
        private static final VoxelShape SHAPE_Z = Block.box(5.75, 0, 0, 10.25, 11, 16);

        public RakeEnd(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.EAST).setValue(SIDE, Side.LEFT));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState panel = context.getLevel().getBlockState(context.getClickedPos().relative(d));
                if (panel.getBlock() instanceof GablePanel && panel.getValue(FACING) == d) {
                    return defaultBlockState().setValue(FACING, d).setValue(SIDE, panel.getValue(GablePanel.SIDE));
                }
            }
            Direction facing = context.getHorizontalDirection();
            Vec3 click = context.getClickLocation().subtract(context.getClickedPos().getX(),
                    context.getClickedPos().getY(), context.getClickedPos().getZ());
            Direction left = facing.getCounterClockWise();
            double along = (click.x - 0.5) * left.getStepX() + (click.z - 0.5) * left.getStepZ();
            return defaultBlockState().setValue(FACING, facing).setValue(SIDE, along >= 0 ? Side.LEFT : Side.RIGHT);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return state.getValue(FACING).getAxis() == Direction.Axis.X ? SHAPE_X : SHAPE_Z;
        }

        @Override
        protected BlockState rotate(BlockState state, Rotation rotation) {
            return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        }

        @Override
        protected BlockState mirror(BlockState state, Mirror mirror) {
            if (mirror == Mirror.NONE) return state;
            return state.setValue(FACING, mirror.mirror(state.getValue(FACING)))
                    .setValue(SIDE, state.getValue(SIDE) == Side.LEFT ? Side.RIGHT : Side.LEFT);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING, SIDE);
        }
    }

    /** Hip-roof apex: the hip ridges meet under a tapering cap. */
    public static class Finial extends Block {
        public static final MapCodec<Finial> CODEC = simpleCodec(Finial::new);
        private static final VoxelShape SHAPE = Block.box(5, 0, 5, 11, 14, 11);

        public Finial(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return SHAPE;
        }
    }

    /**
     * Low-pitch tiles (1:2) for the concave lower part of a roof. A stair in states and corner logic: HALF bottom is
     * the lower half of the rise (0 to 8 px across the block), HALF top the upper half (8 to 16), both rising toward
     * FACING; SHAPE turns it into a hip or a valley next to other stairs.
     */
    public static class LowSlope extends Slope {
        private static final VoxelShape LOWER = Block.box(0, 0, 0, 16, 8, 16);

        public LowSlope(BlockState base, Properties properties) {
            super(base, properties);
        }

        @Override
        int apexHeight(BlockState state) {
            if (state.getValue(SHAPE) != net.minecraft.world.level.block.state.properties.StairsShape.STRAIGHT) {
                return 0;
            }
            return state.getValue(HALF) == net.minecraft.world.level.block.state.properties.Half.TOP ? 16 : 8;
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return state.getValue(HALF) == net.minecraft.world.level.block.state.properties.Half.BOTTOM
                    ? LOWER : net.minecraft.world.phys.shapes.Shapes.block();
        }
    }

    /** Where a ridge drawn over two slopes meeting back to back is open (gets a round end tile). */
    public enum ApexRidge implements net.minecraft.util.StringRepresentable {
        NONE("none"), RUN("run"), OPEN_LEFT("open_left"), OPEN_RIGHT("open_right"), OPEN_BOTH("open_both");

        private final String name;

        ApexRidge(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /**
     * Roof slope (the tile stairs; {@link LowSlope} extends it). When two slopes meet back to back at the top - this
     * one's high edge against a slope facing the other way with the same top height - both draw half of a ridge over
     * the joint, so even a small roof without ridge blocks gets one. RIDGE also says which ends of the run are open:
     * the run continues into a neighbour with a ridge of the same facing, or into a roof_ridge / roof_ridge_end
     * standing one block up beside it. LEFT is {@code FACING.getCounterClockWise()}.
     *
     * <p>API: reference/minecraft-src/net/minecraft/world/level/block/StairBlock.java (#updateShape, #getStateForPlacement),
     * state/BlockBehaviour.java#updateIndirectNeighbourShapes (the ridge blocks one up and to the side are diagonal
     * neighbours, as for RedStoneWireBlock).
     */
    public static class Slope extends net.minecraft.world.level.block.StairBlock {
        public static final EnumProperty<ApexRidge> RIDGE = EnumProperty.create("ridge", ApexRidge.class);

        public Slope(BlockState base, Properties properties) {
            super(base, properties);
            registerDefaultState(defaultBlockState().setValue(RIDGE, ApexRidge.NONE));
        }

        /** Height (px) of the high edge where a ridge can sit, 0 when this state cannot carry one. */
        int apexHeight(BlockState state) {
            return state.getValue(HALF) == net.minecraft.world.level.block.state.properties.Half.BOTTOM
                    && state.getValue(SHAPE) == net.minecraft.world.level.block.state.properties.StairsShape.STRAIGHT ? 16 : 0;
        }

        static int apex(BlockState state) {
            return state.getBlock() instanceof Slope slope ? slope.apexHeight(state) : 0;
        }

        /** Whether the slope at pos meets a slope back to back at the top. */
        static boolean meets(BlockState state, BlockGetter level, BlockPos pos) {
            int h = apex(state);
            if (h == 0) {
                return false;
            }
            Direction f = state.getValue(FACING);
            BlockState other = level.getBlockState(pos.relative(f));
            return apex(other) == h && other.getValue(FACING) == f.getOpposite();
        }

        /** The run continues on side d: a meeting slope of the same facing, or a ridge block one up. */
        static boolean continues(BlockState state, BlockGetter level, BlockPos pos, Direction d) {
            BlockPos n = pos.relative(d);
            BlockState ns = level.getBlockState(n);
            if (ns.getBlock() instanceof Slope && ns.getValue(FACING) == state.getValue(FACING) && apex(ns) == apex(state)
                    && meets(ns, level, n)) {
                return true;
            }
            return apex(state) == 16 && Ridge.joins(level.getBlockState(n.above()), d.getAxis());
        }

        BlockState withRidge(BlockState state, BlockGetter level, BlockPos pos) {
            if (!meets(state, level, pos)) {
                return state.setValue(RIDGE, ApexRidge.NONE);
            }
            Direction left = state.getValue(FACING).getCounterClockWise();
            boolean l = continues(state, level, pos, left);
            boolean r = continues(state, level, pos, left.getOpposite());
            return state.setValue(RIDGE, l && r ? ApexRidge.RUN : l ? ApexRidge.OPEN_RIGHT : r ? ApexRidge.OPEN_LEFT
                    : ApexRidge.OPEN_BOTH);
        }

        private static boolean cornerPartner(BlockState state, BlockState other) {
            return other.getBlock() instanceof Slope && state.getBlock().getClass() == other.getBlock().getClass()
                    && state.getValue(HALF) == other.getValue(HALF);
        }

        static net.minecraft.world.level.block.state.properties.StairsShape slopeShape(BlockState state, BlockGetter level, BlockPos pos) {
            Direction f = state.getValue(FACING);
            for (boolean outer : new boolean[] {true, false}) {
                BlockState n = level.getBlockState(pos.relative(outer ? f : f.getOpposite()));
                if (!cornerPartner(state, n)) continue;
                Direction turn = n.getValue(FACING);
                BlockState side = level.getBlockState(pos.relative(outer ? turn.getOpposite() : turn));
                if (turn.getAxis() == f.getAxis() || cornerPartner(state, side) && side.getValue(FACING) == f) continue;
                boolean left = turn == f.getCounterClockWise();
                return outer ? (left ? net.minecraft.world.level.block.state.properties.StairsShape.OUTER_LEFT
                        : net.minecraft.world.level.block.state.properties.StairsShape.OUTER_RIGHT)
                        : (left ? net.minecraft.world.level.block.state.properties.StairsShape.INNER_LEFT
                        : net.minecraft.world.level.block.state.properties.StairsShape.INNER_RIGHT);
            }
            return net.minecraft.world.level.block.state.properties.StairsShape.STRAIGHT;
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            BlockState state = super.getStateForPlacement(context);
            return state == null ? null : withRidge(state.setValue(SHAPE, slopeShape(state, context.getLevel(), context.getClickedPos())), context.getLevel(), context.getClickedPos());
        }

        @Override
        protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                         BlockPos pos, BlockPos neighbourPos) {
            BlockState updated = super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
            return withRidge(updated.setValue(SHAPE, slopeShape(updated, level, pos)), level, pos);
        }

        /** Ridge blocks one up and to the side see this slope as part of their run: refresh them. */
        @Override
        protected void updateIndirectNeighbourShapes(BlockState state, LevelAccessor level, BlockPos pos, int flags,
                                                     int recursionLeft) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos up = pos.relative(d).above();
                BlockState us = level.getBlockState(up);
                if (us.getBlock() instanceof Ridge || us.getBlock() instanceof RidgeEnd) {
                    Block.updateOrDestroy(us, Block.updateFromNeighbourShapes(us, level, up), level, up, flags, recursionLeft);
                }
            }
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(RIDGE);
        }
    }

    /** Which side of the eave line the corner is on, for a viewer outside looking in along FACING. */
    public enum Side implements net.minecraft.util.StringRepresentable {
        LEFT("left"), RIGHT("right");

        private final String name;

        Side(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /**
     * Eave lip lifting toward a corner: SIDE left means the corner is at {@code FACING.getCounterClockWise()}. Picks
     * the side of a neighbouring corner piece, else the half of the block that was clicked.
     */
    public static class EaveRise extends Eave {
        public static final MapCodec<EaveRise> CODEC = simpleCodec(EaveRise::new);
        public static final EnumProperty<Side> SIDE = EnumProperty.create("side", Side.class);

        public EaveRise(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SIDE, Side.LEFT));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        /** The side for an eave facing {@code facing}: toward a corner piece next to it, else toward the click. */
        public static Side sideFor(Direction facing, BlockGetter level, BlockPos pos, Vec3 clickInBlock) {
            Direction left = facing.getCounterClockWise();
            if (level.getBlockState(pos.relative(left)).getBlock() instanceof Corner) {
                return Side.LEFT;
            }
            if (level.getBlockState(pos.relative(left.getOpposite())).getBlock() instanceof Corner) {
                return Side.RIGHT;
            }
            double along = (clickInBlock.x - 0.5) * left.getStepX() + (clickInBlock.z - 0.5) * left.getStepZ();
            return along >= 0 ? Side.LEFT : Side.RIGHT;
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
            Direction facing = orient(defaultBlockState().setValue(FACING, context.getHorizontalDirection()), context.getLevel(), context.getClickedPos()).getValue(FACING);
            BlockPos pos = context.getClickedPos();
            Vec3 click = context.getClickLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
            return defaultBlockState().setValue(FACING, facing).setValue(SIDE, sideFor(facing, context.getLevel(), pos, click));
        }

        @Override
        protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                         BlockPos pos, BlockPos neighbourPos) {
            BlockState updated = super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
            Direction left = updated.getValue(FACING).getCounterClockWise();
            if (level.getBlockState(pos.relative(left)).getBlock() instanceof Corner) return updated.setValue(SIDE, Side.LEFT);
            if (level.getBlockState(pos.relative(left.getOpposite())).getBlock() instanceof Corner) return updated.setValue(SIDE, Side.RIGHT);
            return updated;
        }

        @Override
        protected BlockState mirror(BlockState state, Mirror mirror) {
            if (mirror == Mirror.NONE) {
                return state;
            }
            return state.setValue(FACING, mirror.mirror(state.getValue(FACING)))
                    .setValue(SIDE, state.getValue(SIDE) == Side.LEFT ? Side.RIGHT : Side.LEFT);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING, SIDE);
        }
    }

    /** Soffit under an overhang: boards and red rafters running along FACING, hung at the top of the block. */
    public static class Rafters extends HorizontalDirectionalBlock {
        public static final MapCodec<Rafters> CODEC = simpleCodec(Rafters::new);
        private static final VoxelShape SHAPE = Block.box(0, 13, 0, 16, 16, 16);

        public Rafters(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return SHAPE;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }
    }

    /**
     * Timber and wall pieces of a big roof that only need a facing: a bracket set (dougong) of the row under the
     * eave, the wall ridge where a lower eave skirt meets the storey above. FACING points to the wall / building
     * (the way the placer looks, like stairs), the front faces the placer.
     */
    public static class Facing extends HorizontalDirectionalBlock {
        public static final MapCodec<Facing> CODEC = simpleCodec(Facing::new);

        public Facing(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }
    }

    /** Corner bracket set: the corner lies behind the placer, as for {@link Corner}. */
    public static class BracketCorner extends Facing {
        public static final MapCodec<BracketCorner> CODEC = simpleCodec(BracketCorner::new);

        public BracketCorner(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
            if (context.getPlayer() == null) {
                return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
            }
            return defaultBlockState().setValue(FACING, Corner.facingFor(context.getPlayer().getLookAngle()));
        }

        @Override
        protected BlockState mirror(BlockState state, Mirror mirror) {
            return mirror == Mirror.NONE ? state : state.setValue(FACING, mirror.mirror(state.getValue(FACING).getClockWise()));
        }
    }

    /** Architrave: the beam band under the bracket sets, 10 px deep across FACING's axis. */
    public static class Architrave extends Facing {
        public static final MapCodec<Architrave> CODEC = simpleCodec(Architrave::new);
        private static final VoxelShape SHAPE_X = Block.box(3, 0, 0, 13, 16, 16);
        private static final VoxelShape SHAPE_Z = Block.box(0, 0, 3, 16, 16, 13);

        public Architrave(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return state.getValue(FACING).getAxis() == Direction.Axis.X ? SHAPE_X : SHAPE_Z;
        }
    }

    /** Shape of a gable infill: under steep stairs, or under the lower / upper half of the low tiles. */
    public enum GableShape implements net.minecraft.util.StringRepresentable {
        STEEP("steep"), LOW_LOWER("low_lower"), LOW_UPPER("low_upper");

        private final String name;

        GableShape(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /**
     * White plaster gable infill (ref build2/ref_05): a wedge under a roof slope, 3 px below it so the verge tiles lie
     * over the plaster. FACING and SHAPE copy a roof slope beside it when there is one (the gable stands at the end of
     * the slope's run), else steep, rising the way the placer looks.
     */
    public static class Gable extends Facing {
        public static final MapCodec<Gable> CODEC = simpleCodec(Gable::new);
        public static final EnumProperty<GableShape> SHAPE = EnumProperty.create("shape", GableShape.class);
        private static final VoxelShape LOWER = Block.box(0, 0, 0, 16, 8, 16);

        public Gable(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SHAPE, GableShape.STEEP));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState n = context.getLevel().getBlockState(context.getClickedPos().relative(d));
                if (n.getBlock() instanceof Slope && n.getValue(Slope.FACING).getAxis() != d.getAxis()) {
                    GableShape shape = !(n.getBlock() instanceof LowSlope) ? GableShape.STEEP
                            : n.getValue(Slope.HALF) == net.minecraft.world.level.block.state.properties.Half.TOP
                            ? GableShape.LOW_UPPER : GableShape.LOW_LOWER;
                    return defaultBlockState().setValue(FACING, n.getValue(Slope.FACING)).setValue(SHAPE, shape);
                }
            }
            return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return state.getValue(SHAPE) == GableShape.LOW_LOWER ? LOWER : net.minecraft.world.phys.shapes.Shapes.block();
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING, SHAPE);
        }
    }

    /** Small Hua plum plaque on a gable: mounted inside its cell against FACING. */
    public static class Emblem extends Facing {
        public static final MapCodec<Emblem> CODEC = simpleCodec(Emblem::new);
        private static final VoxelShape EAST = Block.box(14.7, 4, 4, 15.8, 12, 12);
        private static final VoxelShape WEST = Block.box(0.2, 4, 4, 1.3, 12, 12);
        private static final VoxelShape SOUTH = Block.box(4, 4, 14.7, 12, 12, 15.8);
        private static final VoxelShape NORTH = Block.box(4, 4, 0.2, 12, 12, 1.3);

        public Emblem(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return switch (state.getValue(FACING)) {
                case EAST -> EAST;
                case WEST -> WEST;
                case SOUTH -> SOUTH;
                default -> NORTH;
            };
        }
    }

    /** Profile of a tile course (codex 07.10 concave roof), from the eave up: flat, foot, gentle, low_high, knee. */
    public enum CourseProfile implements net.minecraft.util.StringRepresentable {
        FLAT("flat", 2), FOOT("foot", 4), GENTLE("gentle", 8), LOW_HIGH("low_high", 16), KNEE("knee", 16);

        private final String name;
        private final int top;

        CourseProfile(String name, int top) {
            this.name = name;
            this.top = top;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /**
     * A course of roof tiles for the gentle lower rows of a concave roof (docs/design/research/roof-vs-refs-codex-0710.md):
     * the profile heights continue each other from the eave lip to the steep stairs, so the slope has no break.
     * Stair states and corner logic (hips, valleys) as {@link Slope}; it never carries the apex ridge.
     */
    public static class Course extends Slope {
        public static final EnumProperty<CourseProfile> PROFILE = EnumProperty.create("profile", CourseProfile.class);
        private static final VoxelShape[] SHAPES = new VoxelShape[CourseProfile.values().length];

        static {
            for (CourseProfile p : CourseProfile.values()) {
                SHAPES[p.ordinal()] = Block.box(0, 0, 0, 16, p.top, 16);
            }
        }

        public Course(BlockState base, Properties properties) {
            super(base, properties);
            registerDefaultState(defaultBlockState().setValue(PROFILE, CourseProfile.GENTLE));
        }

        @Override
        int apexHeight(BlockState state) {
            return 0;
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return SHAPES[state.getValue(PROFILE).ordinal()];
        }

        /** A neighbour that shapes a course: a course of the same profile and half (others lie at other heights). */
        private static boolean partner(BlockState self, BlockState other) {
            return other.getBlock() instanceof Course && other.getValue(PROFILE) == self.getValue(PROFILE)
                    && other.getValue(HALF) == self.getValue(HALF);
        }

        /**
         * StairBlock#getStairsShape (private in 1.21.1, same rules) with only {@link #partner} neighbours: all courses of
         * a roof lie on one level, and a vanilla stair would turn the straight course next to a hip into a second hip.
         */
        static net.minecraft.world.level.block.state.properties.StairsShape shapeFor(BlockState state, BlockGetter level, BlockPos pos) {
            Direction dir = state.getValue(FACING);
            BlockState behind = level.getBlockState(pos.relative(dir));
            if (partner(state, behind)) {
                Direction d1 = behind.getValue(FACING);
                BlockState side = level.getBlockState(pos.relative(d1.getOpposite()));
                if (d1.getAxis() != dir.getAxis() && !(partner(state, side) && side.getValue(FACING) == dir)) {
                    return d1 == dir.getCounterClockWise() ? net.minecraft.world.level.block.state.properties.StairsShape.OUTER_LEFT
                            : net.minecraft.world.level.block.state.properties.StairsShape.OUTER_RIGHT;
                }
            }
            BlockState front = level.getBlockState(pos.relative(dir.getOpposite()));
            if (partner(state, front)) {
                Direction d2 = front.getValue(FACING);
                BlockState side = level.getBlockState(pos.relative(d2));
                if (d2.getAxis() != dir.getAxis() && !(partner(state, side) && side.getValue(FACING) == dir)) {
                    return d2 == dir.getCounterClockWise() ? net.minecraft.world.level.block.state.properties.StairsShape.INNER_LEFT
                            : net.minecraft.world.level.block.state.properties.StairsShape.INNER_RIGHT;
                }
            }
            return net.minecraft.world.level.block.state.properties.StairsShape.STRAIGHT;
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            BlockState state = super.getStateForPlacement(context);
            return state == null ? null : state.setValue(SHAPE, shapeFor(state, context.getLevel(), context.getClickedPos()));
        }

        @Override
        protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                         BlockPos pos, BlockPos neighbourPos) {
            // Always the course rules: Slope#updateShape counts courses of any profile as corner partners, so an update
            // from above or below (a gable panel placed on the course) left that shape and turned the straight course
            // beside a hip into a second hip (roof v5 in-game frames, 10.10).
            BlockState s = super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
            return s.setValue(SHAPE, shapeFor(s, level, pos));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(PROFILE);
        }
    }

    /** Top edge of a gable panel; UNDER_STEEP continues the sloping contour into the cell below STEEP. */
    public enum PanelProfile implements net.minecraft.util.StringRepresentable {
        STEEP("steep"), KNEE("knee"), LOW_HIGH("low_high"), FULL("full"), PEAK("peak"), UNDER_STEEP("under_steep");

        private final String name;

        PanelProfile(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /**
     * Framed white gable panel: a 4.5 px plaster plane behind the dark contour against
     * the end of the roof slope beside it. FACING is that slope's facing, SIDE the side the slope is on (left =
     * FACING's counter-clockwise neighbour), PROFILE its top edge, EMBLEM the small plum rosette on its face. Placement
     * copies a slope (stairs or course) found beside it.
     */
    public static class GablePanel extends Facing {
        public static final MapCodec<GablePanel> CODEC = simpleCodec(GablePanel::new);
        public static final EnumProperty<Side> SIDE = EaveRise.SIDE;
        public static final EnumProperty<PanelProfile> PROFILE = EnumProperty.create("profile", PanelProfile.class);
        public static final BooleanProperty EMBLEM = BooleanProperty.create("emblem");
        /** The gable's bottom course: carries the dark band along the gable's foot. */
        public static final BooleanProperty BASE = BooleanProperty.create("base");

        public GablePanel(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SIDE, Side.LEFT)
                    .setValue(PROFILE, PanelProfile.FULL).setValue(EMBLEM, false).setValue(BASE, false));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState n = context.getLevel().getBlockState(context.getClickedPos().relative(d));
                if (n.getBlock() instanceof Slope && n.getValue(Slope.FACING).getAxis() != d.getAxis()) {
                    Direction f = n.getValue(Slope.FACING);
                    PanelProfile p = n.getBlock() instanceof Course c
                            ? (n.getValue(Course.PROFILE) == CourseProfile.KNEE ? PanelProfile.KNEE : PanelProfile.LOW_HIGH)
                            : PanelProfile.STEEP;
                    return defaultBlockState().setValue(FACING, f).setValue(PROFILE, p)
                            .setValue(SIDE, f.getCounterClockWise() == d ? Side.LEFT : Side.RIGHT);
                }
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState above = context.getLevel().getBlockState(context.getClickedPos().relative(d).above());
                if (above.getBlock() instanceof Slope && !(above.getBlock() instanceof LowSlope)
                        && !(above.getBlock() instanceof Course) && above.getValue(Slope.FACING).getAxis() != d.getAxis()) {
                    Direction f = above.getValue(Slope.FACING);
                    return defaultBlockState().setValue(FACING, f).setValue(PROFILE, PanelProfile.UNDER_STEEP)
                            .setValue(SIDE, f.getCounterClockWise() == d ? Side.LEFT : Side.RIGHT);
                }
            }
            return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            Direction f = state.getValue(FACING);
            Direction plate = state.getValue(SIDE) == Side.LEFT ? f.getCounterClockWise() : f.getClockWise();
            return switch (plate) {
                case NORTH -> Block.box(0, 0, 0, 16, 16, 10.25);
                case SOUTH -> Block.box(0, 0, 5.75, 16, 16, 16);
                case WEST -> Block.box(0, 0, 0, 10.25, 16, 16);
                default -> Block.box(5.75, 0, 0, 16, 16, 16);
            };
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING, SIDE, PROFILE, EMBLEM, BASE);
        }
    }
}
