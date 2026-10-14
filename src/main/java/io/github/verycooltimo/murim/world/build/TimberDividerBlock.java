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
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Standalone divider post (author 07.10: «блоки-разделители, как раньше у нас с шапкой был, чтобы просто был
 * разделителем»): the thin 2.5 px post with a cap of the same width set off by a groove, independent of beams.
 * Goes anywhere — floors, between beams, under or over things — in the pillar's nine slots. Dividers with the same
 * slot and cap side stack into one continuous post; the cap shows only at the free end on its side.
 * {@link #CAP} down by default; placed while sneaking it is up. Sneak + empty hand cycles the slot.
 */
public class TimberDividerBlock extends Block {

    public static final MapCodec<TimberDividerBlock> CODEC = simpleCodec(TimberDividerBlock::new);
    public static final EnumProperty<TimberOffset> OFFSET = RedPillarBlock.OFFSET;
    public static final EnumProperty<Cap> CAP = EnumProperty.create("cap", Cap.class);
    public static final BooleanProperty UP = BlockStateProperties.UP;
    public static final BooleanProperty DOWN = BlockStateProperties.DOWN;

    public static final double HALF = 1.25;

    public enum Cap implements StringRepresentable {
        DOWN("down"), UP("up");

        private final String name;

        Cap(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public TimberDividerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(OFFSET, TimberOffset.CENTER).setValue(CAP, Cap.DOWN)
                .setValue(UP, false).setValue(DOWN, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OFFSET, CAP, UP, DOWN);
    }

    static boolean joins(BlockState self, BlockState other) {
        return other.getBlock() instanceof TimberDividerBlock && other.getValue(OFFSET) == self.getValue(OFFSET)
                && other.getValue(CAP) == self.getValue(CAP);
    }

    public static BlockState connect(BlockState s, BlockGetter level, BlockPos pos) {
        return s.setValue(UP, joins(s, level.getBlockState(pos.above()))).setValue(DOWN, joins(s, level.getBlockState(pos.below())));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState clicked = TimberPlacement.clickedState(ctx);
        boolean vertical = ctx.getClickedFace().getAxis() == Direction.Axis.Y;
        Vec3 l = TimberPlacement.localHit(ctx);
        Player player = ctx.getPlayer();
        Cap cap = player != null && player.isSecondaryUseActive() ? Cap.UP : Cap.DOWN;
        TimberOffset offset;
        if (clicked.getBlock() instanceof TimberDividerBlock && vertical) {
            offset = clicked.getValue(OFFSET);
            cap = clicked.getValue(CAP);
        } else if (clicked.getBlock() instanceof RedBeamBlock && vertical) {
            Direction.Axis axis = clicked.getValue(RedBeamBlock.AXIS);
            int along = TimberOffset.zone(axis == Direction.Axis.X ? l.x : l.z);
            offset = TimberOffset.CENTER.withAlong(axis, along).withAlong(TimberPlacement.cross(axis), clicked.getValue(RedBeamBlock.SIDE).slot);
        } else {
            TimberOffset inherited = TimberPlacement.offsetOf(clicked);
            offset = inherited != null && vertical ? inherited : TimberOffset.fromLocal(l.x, l.z);
        }
        return connect(defaultBlockState().setValue(OFFSET, offset).setValue(CAP, cap), ctx.getLevel(), ctx.getClickedPos());
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (dir == Direction.UP) {
            return state.setValue(UP, joins(state, neighbour));
        }
        if (dir == Direction.DOWN) {
            return state.setValue(DOWN, joins(state, neighbour));
        }
        return state;
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
        TimberOffset o = state.getValue(OFFSET);
        double cx = TimberOffset.centrePx(o.dx());
        double cz = TimberOffset.centrePx(o.dz());
        return Block.box(cx - HALF, 0, cz - HALF, cx + HALF, 16, cz + HALF);
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(OFFSET, state.getValue(OFFSET).rotate(rotation));
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
