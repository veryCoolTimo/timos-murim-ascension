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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Small dougong-like bracket: bearing block, two stepped arms with little blocks, a cap — 5.3 px long and
 * 8 px tall (round 7, author 07.10: «кронштейн меньше сделать… независимо от позиции балки или столба к ним
 * прилегать»). Arms run along {@link #AXIS}; {@link #OFFSET} uses the pillar slots.
 *
 * <p>It snaps to the real surface of what it is placed against ({@link #REST}, {@link #REACH}): on a beam it sits
 * on the beam's top at the beam's side slot whatever the beam's level; under a beam it hangs right under the
 * beam's bottom; on a pillar's side it lies against the pillar's face, wherever the pillar stands in its block.
 *
 * <p>Between two beams of a lintel (same axis and side, level top) it {@link #CARRY carries} the lintel through
 * its block and stands right under it, so the lintel stays whole above a row of brackets.
 */
public class BeamBracketBlock extends Block {

    public static final MapCodec<BeamBracketBlock> CODEC = simpleCodec(BeamBracketBlock::new);
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    public static final EnumProperty<TimberOffset> OFFSET = RedPillarBlock.OFFSET;
    public static final EnumProperty<Rest> REST = EnumProperty.create("rest", Rest.class);
    public static final EnumProperty<Reach> REACH = EnumProperty.create("reach", Reach.class);
    public static final BooleanProperty CARRY = BooleanProperty.create("carry");

    /** Height of the bracket body in px. */
    public static final double HEIGHT = 8.0;
    /** Half length of the arms, px. */
    public static final double HALF_LEN = 8.0 / 3;

    /** Y of the bracket's bottom: on a beam below (by its level), on the floor, under the lintel, under a beam above. */
    public enum Rest implements StringRepresentable {
        ON_LOW("on_low", -12), ON_MID("on_mid", -6), FLOOR("floor", 0), LINTEL("lintel", 4),
        UNDER_LOW("under_low", 8), UNDER_MID("under_mid", 14), UNDER_TOP("under_top", 20);

        private final String name;
        public final double y0;

        Rest(String name, double y0) {
            this.name = name;
            this.y0 = y0;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /** How far along the arms the bracket moves into a pillar's block to touch its face (sign = side). */
    public enum Reach implements StringRepresentable {
        NONE("none", 0), NEG_NEAR("neg_near", -0.5), NEG_MID("neg_mid", -5.5), NEG_FAR("neg_far", -10.5),
        POS_NEAR("pos_near", 0.5), POS_MID("pos_mid", 5.5), POS_FAR("pos_far", 10.5);

        private final String name;
        public final double px;

        Reach(String name, double px) {
            this.name = name;
            this.px = px;
        }

        /** Shift of the bracket along its arms from its edge slot (same formula as tools/art/building_kit.py). */
        public double shift() {
            if (px == 0) {
                return 0;
            }
            double gap = TimberOffset.centrePx(-1) - HALF_LEN;
            return px < 0 ? px - gap : px + gap;
        }

        static Reach of(int sign, int slot) {
            if (sign < 0) {
                return slot == 1 ? NEG_NEAR : slot == 0 ? NEG_MID : NEG_FAR;
            }
            return slot == -1 ? POS_NEAR : slot == 0 ? POS_MID : POS_FAR;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public BeamBracketBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X).setValue(OFFSET, TimberOffset.CENTER)
                .setValue(REST, Rest.FLOOR).setValue(REACH, Reach.NONE).setValue(CARRY, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, OFFSET, REST, REACH, CARRY);
    }

    /** Side slot of the lintel this bracket would carry. */
    static int side(BlockState s) {
        return s.getValue(OFFSET).along(TimberPlacement.cross(s.getValue(AXIS)));
    }

    /** Does this bracket carry a top-level lintel along {@code axis} at {@code side}? */
    static boolean carries(BlockState s, Direction.Axis axis, RedBeamBlock.Side side) {
        return s.getValue(CARRY) && s.getValue(AXIS) == axis && side(s) == side.slot;
    }

    /** Is {@code n} a top-level beam, or a bracket/strut carrying one, along {@code axis} at {@code side}? */
    static boolean lintel(BlockState n, Direction.Axis axis, RedBeamBlock.Side side) {
        if (n.getBlock() instanceof RedBeamBlock) {
            return n.getValue(RedBeamBlock.AXIS) == axis && n.getValue(RedBeamBlock.SIDE) == side
                    && n.getValue(RedBeamBlock.LEVEL) == RedBeamBlock.Level3.TOP;
        }
        return carriesLintel(n, axis, side);
    }

    /** A bottom bracket or a strut that draws the lintel through its block. */
    static boolean carriesLintel(BlockState n, Direction.Axis axis, RedBeamBlock.Side side) {
        if (n.getBlock() instanceof BeamBracketBlock) {
            return carries(n, axis, side);
        }
        if (n.getBlock() instanceof HangingPostBlock) {
            return n.getValue(HangingPostBlock.CARRY) && n.getValue(HangingPostBlock.AXIS) == axis
                    && n.getValue(HangingPostBlock.OFFSET).along(TimberPlacement.cross(axis)) == side.slot;
        }
        return false;
    }

    private static boolean lintel(BlockState self, BlockState n) {
        return lintel(n, self.getValue(AXIS), RedBeamBlock.Side.of(side(self)));
    }

    public static BlockState connect(BlockState s, BlockGetter level, BlockPos pos) {
        Rest rest = s.getValue(REST);
        if (s.getValue(REACH) != Reach.NONE || (rest != Rest.FLOOR && rest != Rest.LINTEL)) {
            return s.setValue(CARRY, false);
        }
        Direction.Axis axis = s.getValue(AXIS);
        boolean carry = lintel(s, level.getBlockState(pos.relative(RedBeamBlock.negDir(axis))))
                || lintel(s, level.getBlockState(pos.relative(RedBeamBlock.posDir(axis))));
        // carrying: stand right under the lintel (12 px) instead of on the floor
        return s.setValue(CARRY, carry).setValue(REST, carry ? Rest.LINTEL : Rest.FLOOR);
    }

    static Rest onBeam(RedBeamBlock.Level3 level) {
        return switch (level) {
            case BOTTOM -> Rest.ON_LOW;
            case MIDDLE -> Rest.ON_MID;
            default -> Rest.FLOOR;
        };
    }

    static Rest underBeam(RedBeamBlock.Level3 level) {
        return switch (level) {
            case BOTTOM -> Rest.UNDER_LOW;
            case MIDDLE -> Rest.UNDER_MID;
            default -> Rest.UNDER_TOP;
        };
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState clicked = TimberPlacement.clickedState(ctx);
        Direction face = ctx.getClickedFace();
        boolean vertical = face.getAxis() == Direction.Axis.Y;
        Vec3 l = TimberPlacement.localHit(ctx);
        Direction.Axis axis = TimberPlacement.acrossView(ctx);
        TimberOffset offset = TimberOffset.fromLocal(l.x, l.z);
        Rest rest = face == Direction.DOWN || (!vertical && l.y > 0.5) ? Rest.UNDER_LOW : Rest.FLOOR;
        Reach reach = Reach.NONE;
        if (clicked.getBlock() instanceof RedBeamBlock) {
            // on or under a beam, wherever it sits: its axis and side, our bottom on its top or our top at its bottom
            axis = clicked.getValue(RedBeamBlock.AXIS);
            int across = clicked.getValue(RedBeamBlock.SIDE).slot;
            int along = TimberOffset.zone(axis == Direction.Axis.X ? l.x : l.z);
            offset = TimberOffset.CENTER.withAlong(axis, along).withAlong(TimberPlacement.cross(axis), across);
            RedBeamBlock.Level3 lvl = clicked.getValue(RedBeamBlock.LEVEL);
            if (face == Direction.UP) {
                rest = onBeam(lvl);
            } else if (face == Direction.DOWN) {
                rest = underBeam(lvl); // top of the bracket at the beam's bottom, whatever its level
            }
        } else if (clicked.getBlock() instanceof RedPillarBlock && !vertical) {
            // against a pillar's side: arms point away from it, back on its face, across at its slot
            axis = face.getAxis();
            Integer across = RedPillarBlock.slotAlong(clicked, TimberPlacement.cross(axis));
            Integer along = RedPillarBlock.slotAlong(clicked, axis);
            int sign = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? -1 : 1; // pillar is behind us
            int edge = sign; // our edge slot toward the pillar
            offset = TimberOffset.CENTER.withAlong(axis, edge).withAlong(TimberPlacement.cross(axis), across == null ? 0 : across);
            reach = Reach.of(sign, along == null ? 0 : along);
            rest = l.y > 0.5 ? Rest.UNDER_LOW : Rest.FLOOR;
        } else {
            TimberOffset inherited = TimberPlacement.offsetOf(clicked);
            if (inherited != null && vertical) {
                offset = inherited;
            }
            if (clicked.getBlock() instanceof BeamBracketBlock && vertical) {
                axis = clicked.getValue(AXIS);
            }
        }
        BlockState above = ctx.getLevel().getBlockState(ctx.getClickedPos().above());
        if (rest == Rest.UNDER_LOW && reach == Reach.NONE && above.getBlock() instanceof RedBeamBlock) {
            rest = underBeam(above.getValue(RedBeamBlock.LEVEL)); // hang right under the beam's bottom
        }
        BlockState s = defaultBlockState().setValue(AXIS, axis).setValue(OFFSET, offset).setValue(REST, rest).setValue(REACH, reach);
        return connect(s, ctx.getLevel(), ctx.getClickedPos());
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return dir.getAxis() == state.getValue(AXIS) ? connect(state, level, pos) : state;
    }

    /** Sneak + empty hand: next slot; after the ninth it turns 90°. The snap (rest, reach) is cleared. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            TimberOffset next = state.getValue(OFFSET).next();
            BlockState s = state.setValue(OFFSET, next);
            if (next == TimberOffset.CENTER) {
                s = s.setValue(AXIS, TimberPlacement.cross(state.getValue(AXIS)));
            }
            s = s.setValue(REACH, Reach.NONE).setValue(REST, state.getValue(REACH) == Reach.NONE ? state.getValue(REST) : Rest.FLOOR);
            level.setBlock(pos, connect(s, level, pos), Block.UPDATE_ALL);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        TimberOffset o = state.getValue(OFFSET);
        double cx = TimberOffset.centrePx(o.dx());
        double cz = TimberOffset.centrePx(o.dz());
        double y0 = state.getValue(REST).y0;
        boolean alongX = state.getValue(AXIS) == Direction.Axis.X;
        double sh = state.getValue(REACH).shift();
        if (alongX) {
            cx += sh;
        } else {
            cz += sh;
        }
        // The body may leave the block (snapped onto a neighbour); shapes may, as fence collision does.
        double w = 1.5;
        VoxelShape body = alongX ? Block.box(cx - HALF_LEN, y0, cz - w, cx + HALF_LEN, y0 + HEIGHT, cz + w)
                : Block.box(cx - w, y0, cz - HALF_LEN, cx + w, y0 + HEIGHT, cz + HALF_LEN);
        if (!state.getValue(CARRY)) {
            return body;
        }
        double c = TimberOffset.centrePx(side(state));
        VoxelShape beam = alongX ? clamped(0, 12, c - 1.5, 16, 16, c + 1.5) : clamped(c - 1.5, 12, 0, c + 1.5, 16, 16);
        return Shapes.or(body, beam);
    }

    private static VoxelShape clamped(double x0, double y0, double z0, double x1, double y1, double z1) {
        return Block.box(Math.max(0, x0), y0, Math.max(0, z0), Math.min(16, x1), y1, Math.min(16, z1));
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        BlockState s = state.setValue(OFFSET, state.getValue(OFFSET).rotate(rotation));
        if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) {
            s = s.setValue(AXIS, TimberPlacement.cross(state.getValue(AXIS)));
        }
        return s;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return switch (mirror) {
            case FRONT_BACK -> state.setValue(OFFSET, state.getValue(OFFSET).mirrorX());
            case LEFT_RIGHT -> state.setValue(OFFSET, state.getValue(OFFSET).mirrorZ());
            default -> state;
        };
    }
}
