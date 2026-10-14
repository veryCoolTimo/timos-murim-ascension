package io.github.verycooltimo.murim.world.hua;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Marble balustrade of Mount Hua. A vanilla fence plus diagonal arms (author 06.10: «сделай, чтобы была возможность
 * делать забор по диагонали тоже») and sloped arms along 1:1 stairs («чтобы работала диагональ сверху вниз»).
 *
 * <p>Horizontal diagonal: the diagonal neighbour is a balustrade and neither block is connected through the two
 * cardinal cells between them, so an L-corner stays an L and a staircase line runs straight on the diagonal; two arms
 * meet at the shared block corner.
 *
 * <p>Slope: the block one step to a side and one up (or down) is a balustrade and neither of the two has a flat fence
 * connection on that side (a fence or gate on the same level wins; a plain solid block, such as the wall the upper
 * balustrade stands on, does not). The flat arm on a sloped side is switched off. A block keeps one rising and one
 * falling slope ({@code slope_up}, {@code slope_down}: none/north/east/south/west), which covers straight and turning
 * stair runs and keeps the state count at 32 x 16 x 25 = 12,800 instead of 41,472 for a per-side up/down enum.
 *
 * <p>Vanilla neighbour updates never reach diagonal or stepped cells. {@code updateIndirectNeighbourShapes} is the hook
 * redstone wire uses for its diagonal connections; {@code Level#markAndNotifyBlock} calls it for the old and the new
 * state, so placing, breaking and every state change re-evaluate those neighbours.
 * API: reference/minecraft-src/net/minecraft/world/level/Level.java#markAndNotifyBlock,
 * reference/minecraft-src/net/minecraft/world/level/block/RedStoneWireBlock.java#updateIndirectNeighbourShapes,
 * reference/minecraft-src/net/minecraft/world/level/block/FenceBlock.java.
 * All arms are block state properties, so they sync to the client with the block state.
 */
public class HuaBalustradeBlock extends FenceBlock {

    public static final MapCodec<FenceBlock> CODEC = simpleCodec(HuaBalustradeBlock::new);

    /** A diagonal arm, named after its two cardinal directions. */
    public enum Diagonal {
        NORTH_EAST(Direction.NORTH, Direction.EAST),
        SOUTH_EAST(Direction.SOUTH, Direction.EAST),
        SOUTH_WEST(Direction.SOUTH, Direction.WEST),
        NORTH_WEST(Direction.NORTH, Direction.WEST);

        public final Direction a;
        public final Direction b;
        public final BooleanProperty property;

        Diagonal(Direction a, Direction b) {
            this.a = a;
            this.b = b;
            this.property = BooleanProperty.create(a.getName() + "_" + b.getName());
        }

        public BlockPos offset(BlockPos pos) {
            return pos.relative(this.a).relative(this.b);
        }

        static Diagonal of(Direction x, Direction y) {
            for (Diagonal d : values()) {
                if ((d.a == x && d.b == y) || (d.a == y && d.b == x)) {
                    return d;
                }
            }
            throw new IllegalArgumentException(x + "+" + y);
        }
    }

    /** Side of a sloped arm, or none. */
    public enum Slope implements StringRepresentable {
        NONE(null), NORTH(Direction.NORTH), EAST(Direction.EAST), SOUTH(Direction.SOUTH), WEST(Direction.WEST);

        public final Direction direction;

        Slope(Direction direction) {
            this.direction = direction;
        }

        public static Slope of(Direction d) {
            return d == null ? NONE : switch (d) {
                case NORTH -> NORTH;
                case EAST -> EAST;
                case SOUTH -> SOUTH;
                case WEST -> WEST;
                default -> NONE;
            };
        }

        @Override
        public String getSerializedName() {
            return this.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final BooleanProperty NORTH_EAST = Diagonal.NORTH_EAST.property;
    public static final BooleanProperty SOUTH_EAST = Diagonal.SOUTH_EAST.property;
    public static final BooleanProperty SOUTH_WEST = Diagonal.SOUTH_WEST.property;
    public static final BooleanProperty NORTH_WEST = Diagonal.NORTH_WEST.property;
    public static final EnumProperty<Slope> SLOPE_UP = EnumProperty.create("slope_up", Slope.class);
    public static final EnumProperty<Slope> SLOPE_DOWN = EnumProperty.create("slope_down", Slope.class);

    /** Outline (height 16) and collision (height 24, like FenceBlock) shapes per mask of diagonal arms. */
    private final VoxelShape[] diagonalOutline = new VoxelShape[16];
    private final VoxelShape[] diagonalCollision = new VoxelShape[16];
    /** Slope shapes by [rising 0 / falling 1][horizontal direction 2D index]. */
    private final VoxelShape[][] slopeOutline = new VoxelShape[2][4];
    private final VoxelShape[][] slopeCollision = new VoxelShape[2][4];

    public HuaBalustradeBlock(BlockBehaviour.Properties properties) {
        super(properties);
        BlockState def = this.defaultBlockState().setValue(SLOPE_UP, Slope.NONE).setValue(SLOPE_DOWN, Slope.NONE);
        for (Diagonal d : Diagonal.values()) {
            def = def.setValue(d.property, false);
        }
        this.registerDefaultState(def);
        for (int mask = 0; mask < 16; mask++) {
            VoxelShape outline = Shapes.empty();
            VoxelShape collision = Shapes.empty();
            for (Diagonal d : Diagonal.values()) {
                if ((mask & (1 << d.ordinal())) != 0) {
                    outline = Shapes.or(outline, steps(d, 16.0D));
                    collision = Shapes.or(collision, steps(d, 24.0D));
                }
            }
            this.diagonalOutline[mask] = outline.optimize();
            this.diagonalCollision[mask] = collision.optimize();
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            this.slopeOutline[0][d.get2DDataValue()] = slopeSteps(d, true, false);
            this.slopeOutline[1][d.get2DDataValue()] = slopeSteps(d, false, false);
            this.slopeCollision[0][d.get2DDataValue()] = slopeSteps(d, true, true);
            this.slopeCollision[1][d.get2DDataValue()] = slopeSteps(d, false, true);
        }
    }

    /** Four 3x3 boxes stepping from the post to the block corner along the diagonal (cheap stand-in for a rotated box). */
    private static VoxelShape steps(Diagonal d, double height) {
        double sx = d.a.getStepX() + d.b.getStepX();
        double sz = d.a.getStepZ() + d.b.getStepZ();
        VoxelShape shape = Shapes.empty();
        for (double k : new double[] {1.5D, 3.5D, 5.5D, 7.5D}) {
            double cx = 8.0D + sx * k;
            double cz = 8.0D + sz * k;
            shape = Shapes.or(shape, Block.box(Math.max(0.0D, cx - 1.5D), 0.0D, Math.max(0.0D, cz - 1.5D),
                    Math.min(16.0D, cx + 1.5D), height, Math.min(16.0D, cz + 1.5D)));
        }
        return shape;
    }

    /**
     * Four 4x2 boxes from the post to the edge on side {@code d}; their tops follow the 45-degree rail (rail top 13.4 at
     * the post), clamped to the block for the outline and to the fence's 24 for collision.
     */
    private static VoxelShape slopeSteps(Direction d, boolean rising, boolean collision) {
        VoxelShape shape = Shapes.empty();
        for (double k = 1.0D; k < 8.0D; k += 2.0D) {
            double rise = rising ? k + 1.0D : -(k - 1.0D);
            double top = collision ? Math.min(24.0D, 24.0D + rise) : Math.max(4.0D, Math.min(16.0D, 13.4D + rise));
            double cx = 8.0D + d.getStepX() * k;
            double cz = 8.0D + d.getStepZ() * k;
            double hx = d.getAxis() == Direction.Axis.X ? 1.0D : 2.0D;
            double hz = d.getAxis() == Direction.Axis.Z ? 1.0D : 2.0D;
            shape = Shapes.or(shape, Block.box(cx - hx, 0.0D, cz - hz, cx + hx, top, cz + hz));
        }
        return shape.optimize();
    }

    @Override
    public MapCodec<FenceBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(NORTH_EAST, SOUTH_EAST, SOUTH_WEST, NORTH_WEST, SLOPE_UP, SLOPE_DOWN);
    }

    private static int mask(BlockState state) {
        int mask = 0;
        for (Diagonal d : Diagonal.values()) {
            if (state.getValue(d.property)) {
                mask |= 1 << d.ordinal();
            }
        }
        return mask;
    }

    private VoxelShape extras(BlockState state, VoxelShape base, VoxelShape[] diagonals, VoxelShape[][] slopes) {
        int mask = mask(state);
        VoxelShape shape = mask == 0 ? base : Shapes.or(base, diagonals[mask]);
        Slope up = state.getValue(SLOPE_UP);
        Slope down = state.getValue(SLOPE_DOWN);
        if (up != Slope.NONE) {
            shape = Shapes.or(shape, slopes[0][up.direction.get2DDataValue()]);
        }
        if (down != Slope.NONE) {
            shape = Shapes.or(shape, slopes[1][down.direction.get2DDataValue()]);
        }
        return shape;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return extras(state, super.getShape(state, level, pos, context), this.diagonalOutline, this.slopeOutline);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return extras(state, super.getCollisionShape(state, level, pos, context), this.diagonalCollision, this.slopeCollision);
    }

    private static boolean sloped(BlockState state, Direction d) {
        return state.getValue(SLOPE_UP).direction == d || state.getValue(SLOPE_DOWN).direction == d;
    }

    /** True when the arm on {@code d} is taken, flat or sloped. */
    private static boolean armOn(BlockState state, Direction d) {
        return state.getValue(PROPERTY_BY_DIRECTION.get(d)) || sloped(state, d);
    }

    /** True when a diagonal arm of {@code state} at {@code pos} should connect, given its current cardinal arms. */
    public static boolean connectsDiagonally(BlockState state, BlockGetter level, BlockPos pos, Diagonal d) {
        if (armOn(state, d.a) || armOn(state, d.b)) {
            return false;
        }
        BlockState other = level.getBlockState(d.offset(pos));
        if (!(other.getBlock() instanceof HuaBalustradeBlock)) {
            return false;
        }
        // The neighbour must also be free on its two cardinal arms towards us, so both sides agree.
        return !armOn(other, d.a.getOpposite()) && !armOn(other, d.b.getOpposite());
    }

    /** A flat fence connection exists on side {@code d} of {@code pos}: a fence or gate, not merely a solid face. */
    private boolean flatFence(BlockGetter level, BlockPos pos, Direction d) {
        return this.connectsTo(level.getBlockState(pos.relative(d)), false, d.getOpposite());
    }

    /** A sloped arm on side {@code d}, rising ({@code dy} 1) or falling ({@code dy} -1), has a partner to meet. */
    private boolean slopePartner(BlockGetter level, BlockPos pos, Direction d, int dy) {
        BlockPos partner = pos.relative(d).above(dy);
        return level.getBlockState(partner).getBlock() instanceof HuaBalustradeBlock
                && !flatFence(level, pos, d) && !flatFence(level, partner, d.getOpposite());
    }

    private Slope pickSlope(BlockGetter level, BlockPos pos, int dy) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (slopePartner(level, pos, d, dy)) {
                return Slope.of(d);
            }
        }
        return Slope.NONE;
    }

    /** {@code state} with its slopes and diagonal arms recomputed from the world; a sloped side drops its flat arm. */
    public BlockState withExtras(BlockState state, BlockGetter level, BlockPos pos) {
        Slope up = pickSlope(level, pos, 1);
        Slope down = pickSlope(level, pos, -1);
        state = state.setValue(SLOPE_UP, up).setValue(SLOPE_DOWN, down);
        for (Slope s : new Slope[] {up, down}) {
            if (s != Slope.NONE) {
                state = state.setValue(PROPERTY_BY_DIRECTION.get(s.direction), false);
            }
        }
        for (Diagonal d : Diagonal.values()) {
            state = state.setValue(d.property, connectsDiagonally(state, level, pos, d));
        }
        return state;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return withExtras(super.getStateForPlacement(context), context.getLevel(), context.getClickedPos());
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction facing, BlockState facingState, LevelAccessor level,
                                     BlockPos currentPos, BlockPos facingPos) {
        return withExtras(super.updateShape(state, facing, facingState, level, currentPos, facingPos), level, currentPos);
    }

    /**
     * Re-evaluates the balustrades vanilla does not notify: the four diagonal cells and the eight stepped cells (one to a
     * side and one up or down). Called for the old and the new state on every change at {@code pos}.
     */
    @Override
    protected void updateIndirectNeighbourShapes(BlockState state, LevelAccessor level, BlockPos pos, int flags, int recursionLeft) {
        for (Diagonal d : Diagonal.values()) {
            refresh(level, d.offset(pos), flags, recursionLeft);
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            refresh(level, pos.relative(d).above(), flags, recursionLeft);
            refresh(level, pos.relative(d).below(), flags, recursionLeft);
        }
    }

    private static void refresh(LevelAccessor level, BlockPos at, int flags, int recursionLeft) {
        BlockState other = level.getBlockState(at);
        if (other.getBlock() instanceof HuaBalustradeBlock b) {
            Block.updateOrDestroy(other, b.withExtras(other, level, at), level, at, flags, recursionLeft);
        }
    }

    private static Slope turn(Slope s, java.util.function.UnaryOperator<Direction> f) {
        return s == Slope.NONE ? s : Slope.of(f.apply(s.direction));
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rot) {
        BlockState out = super.rotate(state, rot);
        for (Diagonal d : Diagonal.values()) {
            out = out.setValue(Diagonal.of(rot.rotate(d.a), rot.rotate(d.b)).property, state.getValue(d.property));
        }
        return out.setValue(SLOPE_UP, turn(state.getValue(SLOPE_UP), rot::rotate))
                .setValue(SLOPE_DOWN, turn(state.getValue(SLOPE_DOWN), rot::rotate));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        BlockState out = super.mirror(state, mirror);
        for (Diagonal d : Diagonal.values()) {
            out = out.setValue(Diagonal.of(mirror.mirror(d.a), mirror.mirror(d.b)).property, state.getValue(d.property));
        }
        return out.setValue(SLOPE_UP, turn(state.getValue(SLOPE_UP), mirror::mirror))
                .setValue(SLOPE_DOWN, turn(state.getValue(SLOPE_DOWN), mirror::mirror));
    }
}
