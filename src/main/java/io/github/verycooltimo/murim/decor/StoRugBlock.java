package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Floor rug / mat, 1 px thick; the trim shows only on sides without a neighbouring rug of the same kind. */
public class StoRugBlock extends Block {
    public static final MapCodec<StoRugBlock> CODEC = simpleCodec(StoRugBlock::new);
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty EAST = BlockStateProperties.EAST;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty WEST = BlockStateProperties.WEST;
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 1.2, 16);

    public StoRugBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false).setValue(WEST, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    private static BooleanProperty prop(Direction d) {
        return switch (d) {
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            default -> NORTH;
        };
    }

    private BlockState connect(BlockState s, BlockGetter level, BlockPos pos) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            s = s.setValue(prop(d), level.getBlockState(pos.relative(d)).is(this));
        }
        return s;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext c) {
        return connect(defaultBlockState(), c.getLevel(), c.getClickedPos());
    }

    @Override
    protected boolean canSurvive(BlockState s, LevelReader level, BlockPos pos) {
        return !level.getBlockState(pos.below()).isAir();
    }

    @Override
    protected BlockState updateShape(BlockState s, Direction d, BlockState n, LevelAccessor level, BlockPos p, BlockPos np) {
        if (!s.canSurvive(level, p)) {
            return Blocks.AIR.defaultBlockState();
        }
        return d.getAxis().isHorizontal() ? s.setValue(prop(d), n.is(this)) : s;
    }

    @Override
    protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return SHAPE;
    }

    @Override
    protected BlockState rotate(BlockState s, Rotation rotation) {
        // the trim flags are per side: a stamped rug of the baked sect turned with its site must keep its open sides on the same walls
        return s.setValue(prop(rotation.rotate(Direction.NORTH)), s.getValue(NORTH))
                .setValue(prop(rotation.rotate(Direction.EAST)), s.getValue(EAST))
                .setValue(prop(rotation.rotate(Direction.SOUTH)), s.getValue(SOUTH))
                .setValue(prop(rotation.rotate(Direction.WEST)), s.getValue(WEST));
    }

    @Override
    protected BlockState mirror(BlockState s, Mirror mirror) {
        return switch (mirror) {
            case LEFT_RIGHT -> s.setValue(NORTH, s.getValue(SOUTH)).setValue(SOUTH, s.getValue(NORTH));
            case FRONT_BACK -> s.setValue(EAST, s.getValue(WEST)).setValue(WEST, s.getValue(EAST));
            default -> s;
        };
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(NORTH, EAST, SOUTH, WEST);
    }
}
