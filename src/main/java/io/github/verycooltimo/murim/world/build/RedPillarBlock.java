package io.github.verycooltimo.murim.world.build;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Lacquered column of the gate frame (author's refs decor/photo_2026-10-06_10_55_19/20). One pillar per block,
 * 5 px thick; {@link #OFFSET} puts it in the middle, against a face or into a corner — picked by where you
 * click (docs/design/33-building-kit.md). Sneak + right click with an empty hand moves it to the next slot.
 *
 * <p>It can also lie down and serve as a round beam (author 06.10: «колонну использовать ещё как балку»):
 * {@link #AXIS} x or z, chosen like a log from the clicked side face when placed while sneaking, or taken
 * from a lying pillar whose end you click. Pillars with the same axis and offset join along the axis: the
 * dark rings show only at the ends of a run. {@link #DOWN} is the negative end, {@link #UP} the positive end
 * (for a standing pillar: below and above), each a {@link Joint}.
 *
 * <p>Joints between standing and lying pillars (author 07.10: «колонны должны коннектиться друг с другом»): an end
 * that meets the side of a pillar of another axis — a lying run against a standing column, a column under or on
 * a lying run, two lying runs at right angles — runs into that pillar's block up to its far face (like a beam),
 * with no ring. NEAR / MID / FAR say where the other pillar stands in its block (how far to go).
 *
 * <p>Offset of a lying pillar: the slot across it ({@code along(cross axis)}) is horizontal as usual, the slot
 * along its own axis is reused as the height (−1 low, 0 middle, +1 high).
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/RotatedPillarBlock.java (axis from the clicked
 * face), BlockBehaviour#updateShape, #useWithoutItem (called for an empty hand even while sneaking —
 * ServerPlayerGameMode#useItemOn).
 */
public class RedPillarBlock extends Block implements net.minecraft.world.level.block.EntityBlock {

    public static final MapCodec<RedPillarBlock> CODEC = simpleCodec(RedPillarBlock::new);
    public static final EnumProperty<TimberOffset> OFFSET = EnumProperty.create("offset", TimberOffset.class);
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;
    public static final EnumProperty<Joint> UP = EnumProperty.create("up", Joint.class);
    public static final EnumProperty<Joint> DOWN = EnumProperty.create("down", Joint.class);

    /** What an end of the pillar meets: nothing (ring), the same run, or another pillar it runs into. */
    public enum Joint implements net.minecraft.util.StringRepresentable {
        NONE("none"), RUN("run"), NEAR("near"), MID("mid"), FAR("far");

        private final String name;

        Joint(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final double HALF = 2.5;

    /**
     * Beam bridging the gap from a standing pillar to the standing pillar right next to it (author 07.10: «если две
     * колонны стоят рядом и между ними ткнуть колонной или балкой — появится соединение»): none, or its height
     * (low/mid/high, the beam levels) and where the other pillar stands along the axis (near/mid/far), so the
     * beam runs from this pillar's centre into the other one's centre. Kept on the pillar with the lower x or z.
     */
    public enum Bridge implements net.minecraft.util.StringRepresentable {
        NONE("none", null, 0),
        LOW_NEAR("low_near", RedBeamBlock.Level3.BOTTOM, -1), LOW_MID("low_mid", RedBeamBlock.Level3.BOTTOM, 0), LOW_FAR("low_far", RedBeamBlock.Level3.BOTTOM, 1),
        MID_NEAR("mid_near", RedBeamBlock.Level3.MIDDLE, -1), MID_MID("mid_mid", RedBeamBlock.Level3.MIDDLE, 0), MID_FAR("mid_far", RedBeamBlock.Level3.MIDDLE, 1),
        HIGH_NEAR("high_near", RedBeamBlock.Level3.TOP, -1), HIGH_MID("high_mid", RedBeamBlock.Level3.TOP, 0), HIGH_FAR("high_far", RedBeamBlock.Level3.TOP, 1);

        private final String name;
        public final RedBeamBlock.Level3 level;
        /** The other pillar's slot along the axis in its own block (−1 near us, 0 middle, +1 far). */
        public final int otherSlot;

        Bridge(String name, RedBeamBlock.Level3 level, int otherSlot) {
            this.name = name;
            this.level = level;
            this.otherSlot = otherSlot;
        }

        public static Bridge of(RedBeamBlock.Level3 level, int otherSlot) {
            for (Bridge b : values()) {
                if (b.level == level && b.otherSlot == otherSlot) {
                    return b;
                }
            }
            return NONE;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /**
     * The column carries bridge beams, kept in a {@link PillarBridgeBlockEntity} (07.10: two bridge properties made
     * 43,200 states per column and the client ran out of memory baking multipart models). One flag keeps the state
     * count at 1,350.
     */
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty BRIDGED =
            net.minecraft.world.level.block.state.properties.BooleanProperty.create("bridged");

    static Bridge bridge(BlockGetter level, BlockPos pos, BlockState state, Direction.Axis axis) {
        if (!state.getValue(BRIDGED)) {
            return Bridge.NONE;
        }
        return level.getBlockEntity(pos) instanceof PillarBridgeBlockEntity be ? be.get(axis) : Bridge.NONE;
    }

    private static final VoxelShape[][] SHAPES = new VoxelShape[3][TimberOffset.values().length];

    static {
        for (Direction.Axis axis : Direction.Axis.values()) {
            for (TimberOffset o : TimberOffset.values()) {
                double[] c = centre(axis, o);
                SHAPES[axis.ordinal()][o.ordinal()] = switch (axis) {
                    case Y -> Block.box(c[0] - HALF, 0, c[2] - HALF, c[0] + HALF, 16, c[2] + HALF);
                    case X -> Block.box(0, c[1] - HALF, c[2] - HALF, 16, c[1] + HALF, c[2] + HALF);
                    case Z -> Block.box(c[0] - HALF, c[1] - HALF, 0, c[0] + HALF, c[1] + HALF, 16);
                };
            }
        }
    }

    public RedPillarBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.Y).setValue(OFFSET, TimberOffset.CENTER)
                .setValue(UP, Joint.NONE).setValue(DOWN, Joint.NONE).setValue(BRIDGED, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, OFFSET, UP, DOWN, BRIDGED);
    }

    /** Only a bridged column has a block entity; API: EntityBlock#newBlockEntity (nullable). */
    @Override
    public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(BRIDGED) ? new PillarBridgeBlockEntity(pos, state) : null;
    }

    /** Centre of the column's cross-section in px as {x, y, z}; the coordinate along the axis is unused. */
    static double[] centre(Direction.Axis axis, TimberOffset o) {
        return switch (axis) {
            case Y -> new double[] {TimberOffset.centrePx(o.dx()), 8, TimberOffset.centrePx(o.dz())};
            case X -> new double[] {8, TimberOffset.centrePx(o.dx()), TimberOffset.centrePx(o.dz())};
            case Z -> new double[] {TimberOffset.centrePx(o.dx()), TimberOffset.centrePx(o.dz()), 8};
        };
    }

    /**
     * Slot of this pillar's cross-section along a horizontal {@code axis} (−1, 0, +1), or {@code null} when the
     * pillar lies along that axis (it has no single position there).
     */
    public static Integer slotAlong(BlockState s, Direction.Axis axis) {
        Direction.Axis own = s.getValue(AXIS);
        if (own == axis) {
            return null;
        }
        TimberOffset o = s.getValue(OFFSET);
        if (own == Direction.Axis.Y) {
            return o.along(axis);
        }
        // lying pillar: x position is dx when it lies along z; z position is dz when it lies along x
        return axis == Direction.Axis.X ? o.dx() : o.dz();
    }

    static boolean joins(BlockState self, BlockState other) {
        // a run is one material: red and white columns meet with a joint, not as one run
        return other.getBlock() == self.getBlock() && other.getValue(AXIS) == self.getValue(AXIS)
                && other.getValue(OFFSET) == self.getValue(OFFSET);
    }

    /** Joint of the end facing {@code dir} (along the pillar's own axis) with the neighbour there. */
    static Joint jointFor(BlockState self, Direction dir, BlockState other) {
        if (!(other.getBlock() instanceof RedPillarBlock)) {
            return Joint.NONE;
        }
        if (joins(self, other)) {
            return Joint.RUN;
        }
        Direction.Axis axis = dir.getAxis();
        if (other.getValue(AXIS) == axis) {
            return Joint.NONE; // same direction, other slot: two separate runs
        }
        // where the other pillar's cross-section sits along our axis: 3, 8 or 13 px
        double c = centre(other.getValue(AXIS), other.getValue(OFFSET))[axis.ordinal()];
        int slot = c < 8 ? -1 : c > 8 ? 1 : 0;
        int near = dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? -1 : 1;
        return slot == near ? Joint.NEAR : slot == 0 ? Joint.MID : Joint.FAR;
    }

    public static BlockState connect(BlockState s, BlockGetter level, BlockPos pos) {
        Direction.Axis axis = s.getValue(AXIS);
        Direction up = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
        Direction down = up.getOpposite();
        return s.setValue(UP, jointFor(s, up, level.getBlockState(pos.relative(up))))
                .setValue(DOWN, jointFor(s, down, level.getBlockState(pos.relative(down))));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState clicked = TimberPlacement.clickedState(ctx);
        Direction face = ctx.getClickedFace();
        Vec3 l = TimberPlacement.localHit(ctx);
        boolean onLying = clicked.getBlock() instanceof RedPillarBlock && clicked.getValue(AXIS) != Direction.Axis.Y
                && clicked.getValue(AXIS) == face.getAxis();
        Player player = ctx.getPlayer();
        // Clicked the side of a standing column with another standing column one block further on: lie down
        // between them at the clicked height, in line with the clicked column (author 07.10).
        boolean spans = face.getAxis().isHorizontal() && standing(clicked)
                && standing(ctx.getLevel().getBlockState(ctx.getClickedPos().relative(face)));
        if (spans && !onLying) {
            Direction.Axis axis = face.getAxis();
            Integer across = slotAlong(clicked, TimberPlacement.cross(axis));
            int height = TimberOffset.zone(l.y);
            int a = across == null ? 0 : across;
            TimberOffset offset = axis == Direction.Axis.X ? TimberOffset.of(height, a) : TimberOffset.of(a, height);
            return connect(defaultBlockState().setValue(AXIS, axis).setValue(OFFSET, offset), ctx.getLevel(), ctx.getClickedPos());
        }
        boolean lie = onLying || (player != null && player.isSecondaryUseActive());
        if (lie) {
            Direction.Axis axis = onLying ? clicked.getValue(AXIS)
                    : face.getAxis().isHorizontal() ? face.getAxis() : TimberPlacement.acrossView(ctx);
            TimberOffset offset;
            if (onLying) {
                offset = clicked.getValue(OFFSET);
            } else {
                // across: the horizontal third; along the axis slot: the height third
                int across = TimberOffset.zone(axis == Direction.Axis.X ? l.z : l.x);
                int height = face == Direction.UP ? -1 : face == Direction.DOWN ? 1 : TimberOffset.zone(l.y);
                offset = axis == Direction.Axis.X ? TimberOffset.of(height, across) : TimberOffset.of(across, height);
            }
            return connect(defaultBlockState().setValue(AXIS, axis).setValue(OFFSET, offset), ctx.getLevel(), ctx.getClickedPos());
        }
        TimberOffset inherited = TimberPlacement.offsetOf(clicked);
        TimberOffset offset = inherited != null && face.getAxis() == Direction.Axis.Y ? inherited : TimberOffset.fromLocal(l.x, l.z);
        return connect(defaultBlockState().setValue(OFFSET, offset), ctx.getLevel(), ctx.getClickedPos());
    }

    static boolean standing(BlockState s) {
        return s.getBlock() instanceof RedPillarBlock && s.getValue(AXIS) == Direction.Axis.Y;
    }

    /**
     * A beam or column item used on the side of a standing column whose neighbour on that side is a standing
     * column too: no room for a block between them, so the pillar with the lower coordinate gets a bridge beam
     * into the other at the clicked height. API: BlockBehaviour#useItemOn (1.21.1).
     */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state, Level level,
                                                                  BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        Direction face = hit.getDirection();
        if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem bi)
                || !(bi.getBlock() instanceof RedBeamBlock || bi.getBlock() instanceof RedPillarBlock)
                || !face.getAxis().isHorizontal() || !standing(state)) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        BlockPos otherPos = pos.relative(face);
        BlockState other = level.getBlockState(otherPos);
        if (!standing(other)) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide) {
            Direction.Axis axis = face.getAxis();
            boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
            BlockPos lowPos = positive ? pos : otherPos;
            BlockState low = positive ? state : other;
            BlockState high = positive ? other : state;
            double y = hit.getLocation().y - pos.getY();
            RedBeamBlock.Level3 lvl = RedBeamBlock.Level3.of(TimberOffset.zone(y));
            Bridge b = Bridge.of(lvl, high.getValue(OFFSET).along(axis));
            level.setBlock(lowPos, low.setValue(BRIDGED, true), Block.UPDATE_ALL);
            if (level.getBlockEntity(lowPos) instanceof PillarBridgeBlockEntity be) {
                be.set(axis, b);
            }
            stack.consume(1, player);
        }
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (dir.getAxis().isHorizontal() && dir.getAxisDirection() == Direction.AxisDirection.POSITIVE && state.getValue(BRIDGED)
                && level.getBlockEntity(pos) instanceof PillarBridgeBlockEntity be) {
            Bridge b = be.get(dir.getAxis());
            if (b != Bridge.NONE) {
                // the other column moved or went: follow it, or drop the bridge
                be.set(dir.getAxis(), standing(neighbour) ? Bridge.of(b.level, neighbour.getValue(OFFSET).along(dir.getAxis())) : Bridge.NONE);
                if (!be.any()) {
                    return state.setValue(BRIDGED, false);
                }
            }
        }
        if (dir.getAxis() != state.getValue(AXIS)) {
            return state;
        }
        return state.setValue(dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? UP : DOWN, jointFor(state, dir, neighbour));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            level.setBlock(pos, connect(state.setValue(OFFSET, state.getValue(OFFSET).next()), level, pos), Block.UPDATE_ALL);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        VoxelShape shape = SHAPES[state.getValue(AXIS).ordinal()][state.getValue(OFFSET).ordinal()];
        for (Direction.Axis axis : new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z}) {
            Bridge b = bridge(level, pos, state, axis);
            if (b == Bridge.NONE) {
                continue;
            }
            double from = TimberOffset.centrePx(state.getValue(OFFSET).along(axis));
            double to = 16 + TimberOffset.centrePx(b.otherSlot);
            double c = TimberOffset.centrePx(state.getValue(OFFSET).along(TimberPlacement.cross(axis)));
            double y0 = b.level.y0;
            VoxelShape beam = axis == Direction.Axis.X
                    ? Block.box(from, y0, c - RedBeamBlock.HALF_DEPTH, to, y0 + RedBeamBlock.HEIGHT, c + RedBeamBlock.HALF_DEPTH)
                    : Block.box(c - RedBeamBlock.HALF_DEPTH, y0, from, c + RedBeamBlock.HALF_DEPTH, y0 + RedBeamBlock.HEIGHT, to);
            shape = net.minecraft.world.phys.shapes.Shapes.or(shape, beam);
        }
        return shape;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        Direction.Axis axis = state.getValue(AXIS);
        TimberOffset o = state.getValue(OFFSET);
        if (axis == Direction.Axis.Y) {
            return state.setValue(OFFSET, o.rotate(rotation));
        }
        if (rotation == Rotation.NONE) {
            return state;
        }
        // lying: turn the horizontal position of the cross-section, keep its height
        int height = axis == Direction.Axis.X ? o.dx() : o.dz();
        TimberOffset pos = axis == Direction.Axis.X ? TimberOffset.of(0, o.dz()) : TimberOffset.of(o.dx(), 0);
        TimberOffset turned = pos.rotate(rotation);
        Direction.Axis newAxis = rotation == Rotation.CLOCKWISE_180 ? axis : TimberPlacement.cross(axis);
        TimberOffset no = newAxis == Direction.Axis.X ? TimberOffset.of(height, turned.dz()) : TimberOffset.of(turned.dx(), height);
        return state.setValue(AXIS, newAxis).setValue(OFFSET, no);
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        Direction.Axis axis = state.getValue(AXIS);
        TimberOffset o = state.getValue(OFFSET);
        return switch (mirror) {
            // FRONT_BACK flips x: for a pillar lying along x its dx is the height, which stays
            case FRONT_BACK -> axis == Direction.Axis.X ? state : state.setValue(OFFSET, o.mirrorX());
            case LEFT_RIGHT -> axis == Direction.Axis.Z ? state : state.setValue(OFFSET, o.mirrorZ());
            default -> state;
        };
    }
}
