package io.github.verycooltimo.murim.world.build;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
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
 * Red lacquered beam (lintel) of the gate frame. Runs along {@link #AXIS}; sits in one of three slots
 * across the axis ({@link #SIDE}, the same slots as pillar offsets) and at one of three heights
 * ({@link #LEVEL}). Beams with the same axis, side and level join end to end into one long lintel. Each
 * end shows what it meets: nothing (cut end with a dark ring), another beam (or a bracket carrying
 * the lintel), or a pillar — fence-like (author 06.10): the beam runs into the pillar's block and through the
 * pillar to its far face, wherever the pillar stands ({@code pillar}, {@code pillar_mid}, {@code pillar_far}).
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/RotatedPillarBlock.java (axis from the
 * clicked face), FenceBlock#updateShape (neighbour-driven connections).
 */
public class RedBeamBlock extends Block {

    public static final MapCodec<RedBeamBlock> CODEC = simpleCodec(RedBeamBlock::new);
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    public static final EnumProperty<Side> SIDE = EnumProperty.create("side", Side.class);
    public static final EnumProperty<Level3> LEVEL = EnumProperty.create("level", Level3.class);
    public static final EnumProperty<End> NEG_END = EnumProperty.create("neg_end", End.class);
    public static final EnumProperty<End> POS_END = EnumProperty.create("pos_end", End.class);

    /** Beam cross-section: 4 px tall, 3 px deep (author round 3: slim like the refs). */
    public static final double HEIGHT = 4.0;
    public static final double HALF_DEPTH = 1.5;

    /** Slot across the axis: minus = north for an x beam, west for a z beam. */
    public enum Side implements StringRepresentable {
        CENTER("center", 0), MINUS("minus", -1), PLUS("plus", 1);

        private final String name;
        public final int slot;

        Side(String name, int slot) {
            this.name = name;
            this.slot = slot;
        }

        public static Side of(int slot) {
            return slot < 0 ? MINUS : slot > 0 ? PLUS : CENTER;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /** Height inside the block: bottom 0..4 px, middle 6..10, top 12..16. */
    public enum Level3 implements StringRepresentable {
        BOTTOM("bottom", 0.0), MIDDLE("middle", 6.0), TOP("top", 12.0);

        private final String name;
        public final double y0;

        Level3(String name, double y0) {
            this.name = name;
            this.y0 = y0;
        }

        public static Level3 of(int zone) {
            return zone < 0 ? BOTTOM : zone > 0 ? TOP : MIDDLE;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public enum End implements StringRepresentable {
        NONE("none"), BEAM("beam"), PILLAR("pillar"), PILLAR_MID("pillar_mid"), PILLAR_FAR("pillar_far");

        private final String name;

        End(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    private static final VoxelShape[] SHAPES = new VoxelShape[2 * 3 * 3];

    static {
        for (Direction.Axis axis : new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z}) {
            for (Side side : Side.values()) {
                for (Level3 lvl : Level3.values()) {
                    double c = TimberOffset.centrePx(side.slot);
                    SHAPES[shapeIndex(axis, side, lvl)] = axis == Direction.Axis.X
                            ? Block.box(0, lvl.y0, c - HALF_DEPTH, 16, lvl.y0 + HEIGHT, c + HALF_DEPTH)
                            : Block.box(c - HALF_DEPTH, lvl.y0, 0, c + HALF_DEPTH, lvl.y0 + HEIGHT, 16);
                }
            }
        }
    }

    private static int shapeIndex(Direction.Axis axis, Side side, Level3 lvl) {
        return ((axis == Direction.Axis.X ? 0 : 1) * 3 + side.ordinal()) * 3 + lvl.ordinal();
    }

    public RedBeamBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X).setValue(SIDE, Side.CENTER)
                .setValue(LEVEL, Level3.TOP).setValue(NEG_END, End.NONE).setValue(POS_END, End.NONE));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, SIDE, LEVEL, NEG_END, POS_END);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction face = ctx.getClickedFace();
        BlockState clicked = TimberPlacement.clickedState(ctx);
        boolean onBeam = clicked.getBlock() instanceof RedBeamBlock;
        Direction.Axis axis;
        if (face.getAxis().isHorizontal()) {
            axis = face.getAxis();
        } else if (onBeam) {
            axis = clicked.getValue(AXIS);
        } else {
            axis = TimberPlacement.acrossView(ctx);
        }
        Direction.Axis cross = TimberPlacement.cross(axis);
        Vec3 l = TimberPlacement.localHit(ctx);
        Side side;
        Level3 lvl;
        if (onBeam && clicked.getValue(AXIS) == axis && face.getAxis() == axis) {
            // Extending a lintel from its end: same slot and height.
            side = clicked.getValue(SIDE);
            lvl = clicked.getValue(LEVEL);
        } else {
            TimberOffset off = TimberPlacement.offsetOf(clicked);
            if (off != null) {
                side = Side.of(off.along(cross));
            } else if (onBeam && clicked.getValue(AXIS) == axis) {
                side = clicked.getValue(SIDE);
            } else {
                side = Side.of(TimberOffset.zone(cross == Direction.Axis.X ? l.x : l.z));
            }
            lvl = face == Direction.UP ? Level3.BOTTOM : face == Direction.DOWN ? Level3.TOP : Level3.of(TimberOffset.zone(l.y));
        }
        BlockState s = defaultBlockState().setValue(AXIS, axis).setValue(SIDE, side).setValue(LEVEL, lvl);
        return connect(s, ctx.getLevel(), ctx.getClickedPos());
    }

    /** What the end facing {@code dir} (along the beam axis) meets. */
    static End endFor(BlockState self, Direction dir, BlockState neighbour, BlockGetter level, BlockPos neighbourPos) {
        Direction.Axis axis = self.getValue(AXIS);
        if (neighbour.getBlock() instanceof RedBeamBlock) {
            return neighbour.getValue(AXIS) == axis && neighbour.getValue(SIDE) == self.getValue(SIDE)
                    && neighbour.getValue(LEVEL) == self.getValue(LEVEL) ? End.BEAM : End.NONE;
        }
        if (neighbour.getBlock() instanceof BeamStubBlock) {
            return neighbour.getValue(BeamStubBlock.FACING) == dir
                    && neighbour.getValue(SIDE) == self.getValue(SIDE)
                    && neighbour.getValue(LEVEL) == self.getValue(LEVEL) ? End.BEAM : End.NONE;
        }
        if (BeamBracketBlock.carriesLintel(neighbour, axis, self.getValue(SIDE)) && self.getValue(LEVEL) == Level3.TOP) {
            return End.BEAM;
        }
        if (neighbour.getBlock() instanceof RedPillarBlock) {
            // Fence-like joint (author 06.10): the beam runs into the pillar and through it, wherever it stands.
            double cross = TimberOffset.centrePx(self.getValue(SIDE).slot) / 16;
            double y = (self.getValue(LEVEL).y0 + HEIGHT / 2) / 16;
            net.minecraft.world.phys.AABB bounds = neighbour.getShape(level, neighbourPos).bounds();
            boolean intersects = axis == Direction.Axis.X ? cross > bounds.minZ && cross < bounds.maxZ
                    : cross > bounds.minX && cross < bounds.maxX;
            if (!intersects || y <= bounds.minY || y >= bounds.maxY) return End.NONE;
            Integer slotOrNull = RedPillarBlock.slotAlong(neighbour, axis);
            if (slotOrNull == null) {
                return End.BEAM; // a lying pillar continuing along this beam's axis
            }
            int slot = slotOrNull;
            int near = dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? -1 : 1;
            return slot == near ? End.PILLAR : slot == 0 ? End.PILLAR_MID : End.PILLAR_FAR;
        }
        // A wall only closes this end when its actual shape intersects the beam's cross-section.
        // Reuse the existing tenon distances; no new states or hand-picked wall end are needed.
        double cross = TimberOffset.centrePx(self.getValue(SIDE).slot) / 16;
        double y = (self.getValue(LEVEL).y0 + HEIGHT / 2) / 16;
        double distance = Double.POSITIVE_INFINITY;
        for (net.minecraft.world.phys.AABB box : neighbour.getShape(level, neighbourPos).toAabbs()) {
            boolean intersects = axis == Direction.Axis.X ? cross > box.minZ && cross < box.maxZ
                    : cross > box.minX && cross < box.maxX;
            if (!intersects || y <= box.minY || y >= box.maxY) continue;
            double near = dir.getAxisDirection() == Direction.AxisDirection.POSITIVE
                    ? (axis == Direction.Axis.X ? box.minX : box.minZ)
                    : 1 - (axis == Direction.Axis.X ? box.maxX : box.maxZ);
            distance = Math.min(distance, near * 16);
        }
        if (distance <= 0) return End.BEAM;
        if (distance <= 3) return End.PILLAR;
        if (distance <= 8) return End.PILLAR_MID;
        if (distance <= 13) return End.PILLAR_FAR;
        return End.NONE;
    }

    static Direction negDir(Direction.Axis axis) {
        return Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE);
    }

    static Direction posDir(Direction.Axis axis) {
        return Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
    }

    public static BlockState connect(BlockState s, BlockGetter level, BlockPos pos) {
        Direction.Axis axis = s.getValue(AXIS);
        Direction neg = negDir(axis);
        Direction pos2 = posDir(axis);
        return s.setValue(NEG_END, endFor(s, neg, level.getBlockState(pos.relative(neg)), level, pos.relative(neg)))
                .setValue(POS_END, endFor(s, pos2, level.getBlockState(pos.relative(pos2)), level, pos.relative(pos2)));
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        Direction.Axis axis = state.getValue(AXIS);
        if (dir.getAxis() != axis) {
            return state;
        }
        return state.setValue(dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? POS_END : NEG_END, endFor(state, dir, neighbour, level, neighbourPos));
    }

    /** Sneak + empty hand: next of the nine side/level slots; after the ninth the beam turns 90°. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            level.setBlock(pos, connect(cycle(state), level, pos), Block.UPDATE_ALL);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    static BlockState cycle(BlockState s) {
        int i = s.getValue(SIDE).ordinal() * 3 + s.getValue(LEVEL).ordinal() + 1;
        BlockState out = s;
        if (i == 9) {
            i = 0;
            out = out.setValue(AXIS, TimberPlacement.cross(s.getValue(AXIS)));
        }
        return out.setValue(SIDE, Side.values()[i / 3]).setValue(LEVEL, Level3.values()[i % 3]);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPES[shapeIndex(state.getValue(AXIS), state.getValue(SIDE), state.getValue(LEVEL))];
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    /**
     * Rotation as a turn of the side vector: the side slot is a 2D offset across the axis, and the ends
     * swap when the positive direction turns into a negative one.
     */
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        Direction.Axis axis = state.getValue(AXIS);
        int slot = state.getValue(SIDE).slot;
        TimberOffset sideVec = axis == Direction.Axis.X ? TimberOffset.of(0, slot) : TimberOffset.of(slot, 0);
        TimberOffset turned = sideVec.rotate(rotation);
        Direction pos = rotation.rotate(posDir(axis));
        Direction.Axis newAxis = pos.getAxis();
        int newSlot = newAxis == Direction.Axis.X ? turned.dz() : turned.dx();
        BlockState out = state.setValue(AXIS, newAxis).setValue(SIDE, Side.of(newSlot));
        if (pos.getAxisDirection() == Direction.AxisDirection.NEGATIVE) {
            out = out.setValue(NEG_END, state.getValue(POS_END)).setValue(POS_END, state.getValue(NEG_END));
        }
        return out;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        Direction.Axis axis = state.getValue(AXIS);
        Direction.Axis flipped = mirror == Mirror.FRONT_BACK ? Direction.Axis.X : mirror == Mirror.LEFT_RIGHT ? Direction.Axis.Z : null;
        if (flipped == null) {
            return state;
        }
        if (flipped == axis) {
            return state.setValue(NEG_END, state.getValue(POS_END)).setValue(POS_END, state.getValue(NEG_END));
        }
        return state.setValue(SIDE, Side.of(-state.getValue(SIDE).slot));
    }
}
