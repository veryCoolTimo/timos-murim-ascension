package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Interior seating and tables (refs docs/design/reference/interior/int_01, 02, 05, 06, 07, 09, 19; generator
 * tools/art/int_seat.py): chairs, stools, cushions and mats you can sit on, linear pieces that join end to end (bench,
 * low desk, writing desk), a long table joining on all four sides, tea tables and a dais step. Boxes are given in
 * pixels for a model facing south (the generator's frame) and turned with {@link HallFurniture#turn}.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/HorizontalDirectionalBlock.java, StairBlock.java
 * (facing from the placer), PipeBlock.java (side booleans); seats via {@link SeatEntity}.
 */
public final class IntBlocks {

    private IntBlocks() {
    }

    /** A box in model pixels: x0 y0 z0 x1 y1 z1. */
    private static double[] b(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new double[] {x0, y0, z0, x1, y1, z1};
    }

    private static VoxelShape shape(Direction facing, double[]... boxes) {
        VoxelShape out = Shapes.empty();
        for (double[] v : boxes) {
            out = Shapes.or(out, HallFurniture.turn(v[0], v[1], v[2], v[3], v[4], v[5], facing));
        }
        return out;
    }

    private static Map<Direction, VoxelShape> shapes(double[]... boxes) {
        Map<Direction, VoxelShape> map = new EnumMap<>(Direction.class);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            map.put(d, shape(d, boxes));
        }
        return map;
    }

    // ------------------------------------------------------------------ single seats

    /** Chair, armchair, stool, floor cushion and mat: one block, FACING is the way the sitter looks. */
    public static class SeatPiece extends HorizontalDirectionalBlock implements SeatBlock {

        public enum Kind {
            CHAIR(9.0, shapes(b(2, 7, 2, 14, 9, 14), b(2, 0, 2, 3.6, 7, 3.6), b(12.4, 0, 2, 14, 7, 3.6),
                    b(2, 0, 12.4, 3.6, 7, 14), b(12.4, 0, 12.4, 14, 7, 14), b(2, 9, 2, 14, 16, 3.6))),
            ARMCHAIR(10.0, shapes(b(1.5, 8, 1.5, 14.5, 10, 14.5), b(1.5, 0, 1.5, 3.5, 8, 3.5), b(12.5, 0, 1.5, 14.5, 8, 3.5),
                    b(1.5, 0, 12.5, 3.5, 8, 14.5), b(12.5, 0, 12.5, 14.5, 8, 14.5), b(1.5, 10, 1.5, 14.5, 16, 3.5),
                    b(1.5, 10, 3.5, 3.5, 14, 13.5), b(12.5, 10, 3.5, 14.5, 14, 13.5))),
            STOOL(8.5, shapes(b(3, 6.5, 3, 13, 8.5, 13), b(3.4, 0, 3.4, 5.4, 6.5, 5.4), b(10.6, 0, 3.4, 12.6, 6.5, 5.4),
                    b(3.4, 0, 10.6, 5.4, 6.5, 12.6), b(10.6, 0, 10.6, 12.6, 6.5, 12.6))),
            CUSHION(2.5, shapes(b(1.5, 0, 1.5, 14.5, 2.5, 14.5))),
            MAT(1.0, shapes(b(1, 0, 0, 15, 1, 16)));

            final double top;
            final Map<Direction, VoxelShape> shapes;

            Kind(double topPx, Map<Direction, VoxelShape> shapes) {
                this.top = topPx;
                this.shapes = shapes;
            }
        }

        public static final MapCodec<SeatPiece> CODEC = simpleCodec(p -> new SeatPiece(p, Kind.CHAIR));
        private final Kind kind;

        public SeatPiece(Properties properties, Kind kind) {
            super(properties);
            this.kind = kind;
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        public double seatTop(BlockState state) {
            return kind.top / 16.0D;
        }

        @Override
        public @Nullable Direction seatFront(BlockState state) {
            return state.getValue(FACING);
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            return SeatEntity.sit(level, pos, player) ? InteractionResult.CONSUME : InteractionResult.PASS;
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return kind.shapes.get(state.getValue(FACING));
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
            return state.rotate(mirror.getRotation(state.getValue(FACING)));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }
    }

    // ------------------------------------------------------------------ linear pieces (bench, desks)

    /**
     * Bench, low desk and writing desk: the length runs along the model's x, FACING is the front. A piece of the same
     * kind and facing on the +x or -x side joins: PX / NX are true towards that neighbour, and the end boards (legs)
     * are drawn only at a free end, so a row of benches reads as one long bench.
     */
    public static class Linear extends HorizontalDirectionalBlock implements SeatBlock {

        public static final BooleanProperty PX = BooleanProperty.create("px");
        public static final BooleanProperty NX = BooleanProperty.create("nx");

        public enum Kind {
            BENCH(9.0, new double[][] {b(0, 7, 3, 16, 9, 13), b(0, 5, 5, 16, 7, 6.2)},
                    new double[][] {b(13.2, 0, 3.6, 15.2, 7, 12.4)}, new double[][] {b(0.8, 0, 3.6, 2.8, 7, 12.4)}),
            LOW_DESK(0.0, new double[][] {b(0, 7, 3, 16, 9, 13), b(0, 4.5, 5, 16, 7, 7)},
                    new double[][] {b(12.8, 0, 4, 14.8, 7, 12)}, new double[][] {b(1.2, 0, 4, 3.2, 7, 12)}),
            WRITING_DESK(0.0, new double[][] {b(0, 11, 2, 16, 13, 14), b(0, 9, 3, 16, 11, 4), b(0, 2, 12, 16, 11, 13.2)},
                    new double[][] {b(13, 0, 3, 15, 11, 13)}, new double[][] {b(1, 0, 3, 3, 11, 13)});

            final double top;
            final double[][] common;
            final double[][] endPx;
            final double[][] endNx;
            final Map<BlockState, VoxelShape> cache = new HashMap<>();

            Kind(double topPx, double[][] common, double[][] endPx, double[][] endNx) {
                this.top = topPx;
                this.common = common;
                this.endPx = endPx;
                this.endNx = endNx;
            }
        }

        public static final MapCodec<Linear> CODEC = simpleCodec(p -> new Linear(p, Kind.BENCH));
        private final Kind kind;

        public Linear(Properties properties, Kind kind) {
            super(properties);
            this.kind = kind;
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH).setValue(PX, false).setValue(NX, false));
            for (BlockState s : stateDefinition.getPossibleStates()) {
                Direction f = s.getValue(FACING);
                VoxelShape v = shape(f, kind.common);
                if (!s.getValue(PX)) {
                    v = Shapes.or(v, shape(f, kind.endPx));
                }
                if (!s.getValue(NX)) {
                    v = Shapes.or(v, shape(f, kind.endNx));
                }
                kind.cache.put(s, v);
            }
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        /** Neighbour on the model's +x: facing.getCounterClockWise() (the desk convention). */
        private static Direction towardsPx(Direction facing) {
            return facing.getCounterClockWise();
        }

        private boolean joins(BlockState self, BlockState other, boolean px) {
            return other.is(this) && other.getValue(FACING) == self.getValue(FACING);
        }

        private BlockState joined(BlockState state, LevelAccessor level, BlockPos pos) {
            Direction p = towardsPx(state.getValue(FACING));
            return state.setValue(PX, joins(state, level.getBlockState(pos.relative(p)), true))
                    .setValue(NX, joins(state, level.getBlockState(pos.relative(p.getOpposite())), false));
        }

        @Override
        public double seatTop(BlockState state) {
            return kind.top / 16.0D;
        }

        @Override
        public @Nullable Direction seatFront(BlockState state) {
            return state.getValue(FACING);
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
            BlockState s = defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
            return joined(s, context.getLevel(), context.getClickedPos());
        }

        @Override
        protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                         BlockPos pos, BlockPos neighbourPos) {
            if (dir.getAxis().isHorizontal()) {
                return joined(state, level, pos);
            }
            return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (kind.top <= 0.0D) {
                return InteractionResult.PASS;
            }
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            return SeatEntity.sit(level, pos, player) ? InteractionResult.CONSUME : InteractionResult.PASS;
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return kind.cache.get(state);
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
            return state.rotate(mirror.getRotation(state.getValue(FACING)));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING, PX, NX);
        }
    }

    // ------------------------------------------------------------------ long table

    /** Standard-height table (top 14 px) joining on all four sides, like {@link DiningTableBlock} but for chairs and benches. */
    public static class LongTable extends Block {

        public static final MapCodec<LongTable> CODEC = simpleCodec(LongTable::new);
        private final Map<BlockState, VoxelShape> shapes = new HashMap<>();

        public LongTable(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(PipeBlock.NORTH, false).setValue(PipeBlock.EAST, false)
                    .setValue(PipeBlock.SOUTH, false).setValue(PipeBlock.WEST, false));
            for (BlockState s : stateDefinition.getPossibleStates()) {
                VoxelShape v = Block.box(0, 12, 0, 16, 14, 16);
                boolean n = s.getValue(PipeBlock.NORTH), e = s.getValue(PipeBlock.EAST), so = s.getValue(PipeBlock.SOUTH), w = s.getValue(PipeBlock.WEST);
                if (!n && !w) {
                    v = Shapes.or(v, Block.box(0.8, 0, 0.8, 3.2, 12, 3.2));
                }
                if (!n && !e) {
                    v = Shapes.or(v, Block.box(12.8, 0, 0.8, 15.2, 12, 3.2));
                }
                if (!so && !e) {
                    v = Shapes.or(v, Block.box(12.8, 0, 12.8, 15.2, 12, 15.2));
                }
                if (!so && !w) {
                    v = Shapes.or(v, Block.box(0.8, 0, 12.8, 3.2, 12, 15.2));
                }
                shapes.put(s, v);
            }
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
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
                out = out.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(rotation.rotate(d)), state.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d)));
            }
            return out;
        }

        @Override
        protected BlockState mirror(BlockState state, Mirror mirror) {
            BlockState out = state;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                out = out.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(mirror.mirror(d)), state.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d)));
            }
            return out;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(PipeBlock.NORTH, PipeBlock.EAST, PipeBlock.SOUTH, PipeBlock.WEST);
        }
    }

    // ------------------------------------------------------------------ tea tables

    /** Low square or round tea table with a tea set on top; FACING turns the set. */
    public static class TeaTable extends HorizontalDirectionalBlock {

        public static final MapCodec<TeaTable> CODEC = simpleCodec(p -> new TeaTable(p, false));
        private static final Map<Direction, VoxelShape> SQUARE = shapes(b(1, 6, 1, 15, 8, 15), b(1.5, 0, 1.5, 3.5, 6, 3.5),
                b(12.5, 0, 1.5, 14.5, 6, 3.5), b(1.5, 0, 12.5, 3.5, 6, 14.5), b(12.5, 0, 12.5, 14.5, 6, 14.5));
        private static final Map<Direction, VoxelShape> ROUND = shapes(b(1, 6, 1, 15, 8, 15), b(5.5, 0, 5.5, 10.5, 6, 10.5),
                b(1.5, 0, 6.5, 14.5, 1.6, 9.5), b(6.5, 0, 1.5, 9.5, 1.6, 14.5));
        private final boolean round;

        public TeaTable(Properties properties, boolean round) {
            super(properties);
            this.round = round;
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return (round ? ROUND : SQUARE).get(state.getValue(FACING));
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
            return state.rotate(mirror.getRotation(state.getValue(FACING)));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }
    }

    // ------------------------------------------------------------------ dais

    /** Low raised step (8 px, walkable without jumping like a slab) for the master's seat. */
    public static class Dais extends Block {

        public static final MapCodec<Dais> CODEC = simpleCodec(Dais::new);
        private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 8, 16);

        public Dais(Properties properties) {
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

        @Override
        protected boolean isPathfindable(BlockState state, PathComputationType type) {
            return false;
        }
    }
}
