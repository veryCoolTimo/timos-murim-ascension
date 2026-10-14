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
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Beam end that sticks out past a pillar (author 06.10, ref decor/gate_beam_ends.png): a short beam end
 * (2 px past the pillar face wherever the pillar stands, author rounds 3-4) with the beam's cross-section and cut end. It points along {@link #FACING} away from the block it
 * was placed against; against a pillar it reaches back into the pillar ({@link #REACH}), so a beam that runs
 * into the pillar from the other side reads as one beam crossing it. Side and height follow the beam rules.
 */
public class BeamStubBlock extends Block {

    public static final MapCodec<BeamStubBlock> CODEC = simpleCodec(BeamStubBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<RedBeamBlock.Side> SIDE = RedBeamBlock.SIDE;
    public static final EnumProperty<RedBeamBlock.Level3> LEVEL = RedBeamBlock.LEVEL;
    public static final EnumProperty<Reach> REACH = EnumProperty.create("reach", Reach.class);

    /** Stub length out of its block face, px. */
    public static final double LENGTH = 2.0;

    /** How far back the stub goes into the supporting pillar's block: to the pillar's face toward the stub. */
    public enum Reach implements StringRepresentable {
        NONE("none", 0), NEAR("near", 0.5), MID("mid", 5.5), FAR("far", 10.5);

        private final String name;
        public final double px;

        Reach(String name, double px) {
            this.name = name;
            this.px = px;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public BeamStubBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.EAST).setValue(SIDE, RedBeamBlock.Side.CENTER)
                .setValue(LEVEL, RedBeamBlock.Level3.TOP).setValue(REACH, Reach.NONE));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SIDE, LEVEL, REACH);
    }

    static Reach reachFor(Direction facing, BlockState support) {
        if (!(support.getBlock() instanceof RedPillarBlock)) {
            return Reach.NONE;
        }
        Integer slotOrNull = RedPillarBlock.slotAlong(support, facing.getAxis());
        if (slotOrNull == null) {
            return Reach.NONE;
        }
        int slot = slotOrNull;
        int toward = facing.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
        return slot == toward ? Reach.NEAR : slot == 0 ? Reach.MID : Reach.FAR;
    }

    public static BlockState connect(BlockState s, BlockGetter level, BlockPos pos) {
        Direction f = s.getValue(FACING);
        return s.setValue(REACH, reachFor(f, level.getBlockState(pos.relative(f.getOpposite()))));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction face = ctx.getClickedFace();
        BlockState clicked = TimberPlacement.clickedState(ctx);
        Vec3 l = TimberPlacement.localHit(ctx);
        Direction facing = face.getAxis().isHorizontal() ? face
                : Direction.fromAxisAndDirection(TimberPlacement.acrossView(ctx), Direction.AxisDirection.POSITIVE);
        Direction.Axis cross = TimberPlacement.cross(facing.getAxis());
        RedBeamBlock.Side side;
        RedBeamBlock.Level3 lvl;
        if (clicked.getBlock() instanceof RedBeamBlock) {
            side = clicked.getValue(RedBeamBlock.SIDE);
            lvl = clicked.getValue(RedBeamBlock.LEVEL);
        } else {
            TimberOffset off = TimberPlacement.offsetOf(clicked);
            side = off != null ? RedBeamBlock.Side.of(off.along(cross)) : RedBeamBlock.Side.of(TimberOffset.zone(cross == Direction.Axis.X ? l.x : l.z));
            lvl = face == Direction.UP ? RedBeamBlock.Level3.BOTTOM : face == Direction.DOWN ? RedBeamBlock.Level3.TOP
                    : RedBeamBlock.Level3.of(TimberOffset.zone(l.y));
        }
        BlockState s = defaultBlockState().setValue(FACING, facing).setValue(SIDE, side).setValue(LEVEL, lvl);
        return connect(s, ctx.getLevel(), ctx.getClickedPos());
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return dir == state.getValue(FACING).getOpposite() ? state.setValue(REACH, reachFor(state.getValue(FACING), neighbour)) : state;
    }

    /** Sneak + empty hand: next side/height slot; after the ninth the stub turns 90°. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            int i = state.getValue(SIDE).ordinal() * 3 + state.getValue(LEVEL).ordinal() + 1;
            BlockState s = state;
            if (i == 9) {
                i = 0;
                s = s.setValue(FACING, state.getValue(FACING).getClockWise());
            }
            s = s.setValue(SIDE, RedBeamBlock.Side.values()[i / 3]).setValue(LEVEL, RedBeamBlock.Level3.values()[i % 3]);
            level.setBlock(pos, connect(s, level, pos), Block.UPDATE_ALL);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        Direction f = state.getValue(FACING);
        double c = TimberOffset.centrePx(state.getValue(SIDE).slot);
        double y0 = state.getValue(LEVEL).y0;
        double y1 = y0 + RedBeamBlock.HEIGHT;
        double d = RedBeamBlock.HALF_DEPTH;
        double back = state.getValue(REACH).px;
        return switch (f) {
            // The end starts at the supporting pillar's face, which may lie inside the neighbouring block;
            // the outline follows the model there (shapes may leave the block, as fence collision does).
            case EAST -> Block.box(-back, y0, c - d, LENGTH - back, y1, c + d);
            case WEST -> Block.box(16 - LENGTH + back, y0, c - d, 16 + back, y1, c + d);
            case SOUTH -> Block.box(c - d, y0, -back, c + d, y1, LENGTH - back);
            default -> Block.box(c - d, y0, 16 - LENGTH + back, c + d, y1, 16 + back);
        };
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        Direction f = state.getValue(FACING);
        Direction nf = rotation.rotate(f);
        // The side slot is a vector across the axis; turn it with the block.
        int slot = state.getValue(SIDE).slot;
        TimberOffset v = f.getAxis() == Direction.Axis.X ? TimberOffset.of(0, slot) : TimberOffset.of(slot, 0);
        TimberOffset t = v.rotate(rotation);
        int ns = nf.getAxis() == Direction.Axis.X ? t.dz() : t.dx();
        return state.setValue(FACING, nf).setValue(SIDE, RedBeamBlock.Side.of(ns));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        Direction f = state.getValue(FACING);
        Direction.Axis flipped = mirror == Mirror.FRONT_BACK ? Direction.Axis.X : mirror == Mirror.LEFT_RIGHT ? Direction.Axis.Z : null;
        if (flipped == null) {
            return state;
        }
        if (flipped == f.getAxis()) {
            return state.setValue(FACING, f.getOpposite());
        }
        return state.setValue(SIDE, RedBeamBlock.Side.of(-state.getValue(SIDE).slot));
    }
}
