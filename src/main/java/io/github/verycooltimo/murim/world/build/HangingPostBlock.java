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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Short strut between two beams, like a fence post between rails (author 06.10, round 4: «не висеть должен,
 * а между балками быть… шапочка такого же диаметра, как сам столбик… короче в три раза»). A 2.5 px post
 * with a cap of the same width set off by a thin groove.
 *
 * <ul>
 *   <li>Put in the row of the upper lintel, between top-level beams along {@link #AXIS}: it {@link #CARRY carries}
 *       the lintel through its block (like the bracket) and stands up to it at 12 px.</li>
 *   <li>{@link #DOWN} true when a top-level beam lies in the block below: the strut runs down THROUGH it and
 *       sticks out {@link #BELOW} px under it (round 5, refs thin_frame_1 and thin_frame_3: dividers between two beams).
 *       Otherwise it is a short piece (5 px) hanging from the beam above. Both end in the same-width cap.</li>
 * </ul>
 * Same nine slots and sneak-click cycle as the pillar.
 */
public class HangingPostBlock extends Block {

    public static final MapCodec<HangingPostBlock> CODEC = simpleCodec(HangingPostBlock::new);
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    public static final EnumProperty<TimberOffset> OFFSET = RedPillarBlock.OFFSET;
    public static final BooleanProperty DOWN = BlockStateProperties.DOWN;
    public static final BooleanProperty CARRY = BooleanProperty.create("carry");

    /** Length of a strut that hangs free, px (a third of the old hanging post). */
    public static final double SHORT = 5.0;
    /** How far a strut between two beams sticks out under the lower beam, cap included, px (round 5). */
    public static final double BELOW = 4.0;
    public static final double HALF_WIDTH = 1.25;

    public HangingPostBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X).setValue(OFFSET, TimberOffset.CENTER)
                .setValue(DOWN, false).setValue(CARRY, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, OFFSET, DOWN, CARRY);
    }

    static boolean beamBelow(BlockState below) {
        return below.getBlock() instanceof RedBeamBlock && below.getValue(RedBeamBlock.LEVEL) == RedBeamBlock.Level3.TOP;
    }

    public static BlockState connect(BlockState s, BlockGetter level, BlockPos pos) {
        Direction.Axis axis = s.getValue(AXIS);
        RedBeamBlock.Side side = RedBeamBlock.Side.of(s.getValue(OFFSET).along(TimberPlacement.cross(axis)));
        boolean carry = BeamBracketBlock.lintel(level.getBlockState(pos.relative(RedBeamBlock.negDir(axis))), axis, side)
                || BeamBracketBlock.lintel(level.getBlockState(pos.relative(RedBeamBlock.posDir(axis))), axis, side);
        return s.setValue(CARRY, carry).setValue(DOWN, beamBelow(level.getBlockState(pos.below())));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState clicked = TimberPlacement.clickedState(ctx);
        boolean vertical = ctx.getClickedFace().getAxis() == Direction.Axis.Y;
        Vec3 l = TimberPlacement.localHit(ctx);
        Direction.Axis axis = TimberPlacement.acrossView(ctx);
        TimberOffset offset;
        if (clicked.getBlock() instanceof RedBeamBlock) {
            // On or under a beam: across the beam it follows the beam, along it the click.
            axis = clicked.getValue(RedBeamBlock.AXIS);
            int along = TimberOffset.zone(axis == Direction.Axis.X ? l.x : l.z);
            offset = TimberOffset.CENTER.withAlong(axis, along).withAlong(TimberPlacement.cross(axis), clicked.getValue(RedBeamBlock.SIDE).slot);
        } else {
            TimberOffset inherited = TimberPlacement.offsetOf(clicked);
            offset = inherited != null && vertical ? inherited : TimberOffset.fromLocal(l.x, l.z);
        }
        return connect(defaultBlockState().setValue(AXIS, axis).setValue(OFFSET, offset), ctx.getLevel(), ctx.getClickedPos());
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return dir == Direction.DOWN || dir.getAxis() == state.getValue(AXIS) ? connect(state, level, pos) : state;
    }

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
            level.setBlock(pos, connect(s, level, pos), Block.UPDATE_ALL);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Top of the strut: under the carried lintel (12 px) or under a beam in the block above (16 px). */
    static double top(BlockState s) {
        return s.getValue(CARRY) ? RedBeamBlock.Level3.TOP.y0 : 16;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        TimberOffset o = state.getValue(OFFSET);
        double cx = TimberOffset.centrePx(o.dx());
        double cz = TimberOffset.centrePx(o.dz());
        double top = top(state);
        double bottom = state.getValue(DOWN) ? -RedBeamBlock.HEIGHT - BELOW : top - SHORT;
        VoxelShape post = Block.box(cx - HALF_WIDTH, bottom, cz - HALF_WIDTH, cx + HALF_WIDTH, top, cz + HALF_WIDTH);
        if (!state.getValue(CARRY)) {
            return post;
        }
        double c = TimberOffset.centrePx(o.along(TimberPlacement.cross(state.getValue(AXIS))));
        VoxelShape beam = state.getValue(AXIS) == Direction.Axis.X ? Block.box(0, 12, c - 1.5, 16, 16, c + 1.5) : Block.box(c - 1.5, 12, 0, c + 1.5, 16, 16);
        return Shapes.or(post, beam);
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
