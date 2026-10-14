package io.github.verycooltimo.murim.world.build;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Three rectangular steps, 3 px deep. FACING points away from the supporting pillar.
 * Only 240 states; the generator uses exactly the same surface coordinates as the outline. */
public final class SimpleBracketBlock extends Block {
    public static final MapCodec<SimpleBracketBlock> CODEC = simpleCodec(SimpleBracketBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<RedBeamBlock.Side> SIDE = RedBeamBlock.SIDE;
    public static final EnumProperty<Rest> REST = EnumProperty.create("rest", Rest.class);
    public static final EnumProperty<Reach> REACH = EnumProperty.create("reach", Reach.class);

    public enum Rest implements StringRepresentable {
        FLOOR(0), PILLAR(4), UNDER_LOW(8), UNDER_MID(14), UNDER_TOP(20);
        public final double y;
        Rest(double y) { this.y = y; }
        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public enum Reach implements StringRepresentable {
        NONE(0), NEAR(.5), MID(5.5), FAR(10.5);
        public final double px;
        Reach(double px) { this.px = px; }
        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public SimpleBracketBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.EAST)
                .setValue(SIDE, RedBeamBlock.Side.CENTER).setValue(REST, Rest.FLOOR).setValue(REACH, Reach.NONE));
    }

    @Override protected MapCodec<? extends Block> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SIDE, REST, REACH);
    }

    /** An overhead beam wins; otherwise put the back of the corbel on the actual pillar face. */
    public static BlockState connect(BlockState state, BlockGetter level, BlockPos pos) {
        BlockState above = level.getBlockState(pos.above());
        if (above.getBlock() instanceof RedBeamBlock) {
            Direction facing = state.getValue(FACING);
            if (facing.getAxis() != above.getValue(RedBeamBlock.AXIS)) {
                facing = RedBeamBlock.posDir(above.getValue(RedBeamBlock.AXIS));
            }
            Rest rest = switch (above.getValue(RedBeamBlock.LEVEL)) {
                case BOTTOM -> Rest.UNDER_LOW;
                case MIDDLE -> Rest.UNDER_MID;
                case TOP -> Rest.UNDER_TOP;
            };
            return state.setValue(FACING, facing).setValue(SIDE, above.getValue(RedBeamBlock.SIDE))
                    .setValue(REST, rest).setValue(REACH, Reach.NONE);
        }
        Direction preferred = state.getValue(FACING).getOpposite();
        for (int i = 0; i < 4; i++) {
            Direction support = i == 0 ? preferred : i == 1 ? preferred.getClockWise()
                    : i == 2 ? preferred.getCounterClockWise() : preferred.getOpposite();
            BlockState pillar = level.getBlockState(pos.relative(support));
            if (!(pillar.getBlock() instanceof RedPillarBlock)) continue;
            Integer along = RedPillarBlock.slotAlong(pillar, support.getAxis());
            Integer across = RedPillarBlock.slotAlong(pillar, TimberPlacement.cross(support.getAxis()));
            // Only an upright pillar has a full-height face to support this corbel.
            if (along == null || across == null || pillar.getValue(RedPillarBlock.AXIS) != Direction.Axis.Y) continue;
            int near = support.getAxisDirection() == Direction.AxisDirection.POSITIVE ? -1 : 1;
            return state.setValue(FACING, support.getOpposite()).setValue(SIDE, RedBeamBlock.Side.of(across))
                    .setValue(REST, Rest.PILLAR).setValue(REACH, along == near ? Reach.NEAR : along == 0 ? Reach.MID : Reach.FAR);
        }
        return state.setValue(REST, Rest.FLOOR).setValue(REACH, Reach.NONE);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        Direction facing = face.getAxis().isHorizontal() ? face : context.getHorizontalDirection().getClockWise();
        return connect(defaultBlockState().setValue(FACING, facing), context.getLevel(), context.getClickedPos());
    }

    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour,
            LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return connect(state, level, pos);
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        double back = state.getValue(REACH) == Reach.NONE ? 8 - 8.0 / 3 : -state.getValue(REACH).px;
        double c = TimberOffset.centrePx(state.getValue(SIDE).slot);
        double y = state.getValue(REST).y;
        VoxelShape shape = Shapes.empty();
        for (int i = 0; i < 3; i++) {
            double a = back + i * (16.0 / 9), b = back + (i + 1) * (16.0 / 9);
            Direction f = state.getValue(FACING);
            if (f == Direction.WEST || f == Direction.NORTH) { double t = a; a = 16 - b; b = 16 - t; }
            shape = Shapes.or(shape, f.getAxis() == Direction.Axis.X
                    ? Block.box(a, y + i * 3, c - 1.5, b, y + 8, c + 1.5)
                    : Block.box(c - 1.5, y + i * 3, a, c + 1.5, y + 8, b));
        }
        return shape;
    }

    @Override protected BlockState rotate(BlockState state, Rotation rotation) {
        Direction old = state.getValue(FACING), turned = rotation.rotate(old);
        Direction cross = RedBeamBlock.posDir(TimberPlacement.cross(old.getAxis()));
        int side = state.getValue(SIDE).slot * rotation.rotate(cross).getAxisDirection().getStep();
        return state.setValue(FACING, turned).setValue(SIDE, RedBeamBlock.Side.of(side));
    }

    @Override protected BlockState mirror(BlockState state, Mirror mirror) {
        Direction f = state.getValue(FACING);
        Direction cross = RedBeamBlock.posDir(TimberPlacement.cross(f.getAxis()));
        return state.setValue(FACING, mirror.mirror(f)).setValue(SIDE,
                RedBeamBlock.Side.of(state.getValue(SIDE).slot * mirror.mirror(cross).getAxisDirection().getStep()));
    }
}
