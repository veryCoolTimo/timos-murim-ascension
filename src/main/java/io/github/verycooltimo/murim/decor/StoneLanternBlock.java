package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Granite yard lantern two blocks tall (author 07.10, ref docs/design/reference/build2/ref_06.png: «напольные огоньки —
 * как статуи»): plinth and shaft in the LOWER block, platform, fire box, roof and jewel in the UPPER one. Only the
 * upper half gives light (block properties in {@link HallLights}). Models: tools/art/hall_lights.py.
 *
 * <p>Two halves the way the vanilla tall plant does it: placement refused when the spot above is not free, the
 * upper half is set in {@link #setPlacedBy}, a half turns to air when its partner is gone (so breaking either takes
 * the whole lantern), only the lower half has loot, creative breaking of the upper half removes the lower one
 * without drops.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/DoublePlantBlock.java (#updateShape,
 * #getStateForPlacement, #setPlacedBy, #canSurvive, #preventDropFromBottomPart).
 */
public class StoneLanternBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<StoneLanternBlock> CODEC = simpleCodec(StoneLanternBlock::new);
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    private static final VoxelShape LOWER = Shapes.or(Block.box(1.6, 0, 1.6, 14.4, 3.6, 14.4), Block.box(5.5, 3.6, 5.5, 10.5, 16, 10.5));
    private static final VoxelShape UPPER = Shapes.or(Block.box(5.8, 0, 5.8, 10.2, 2.6, 10.2),
            Block.box(2.6, 2.6, 2.6, 13.4, 11.4, 13.4), Block.box(1, 11.4, 1, 15, 16, 15));

    public StoneLanternBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (pos.getY() >= level.getMaxBuildHeight() - 1 || !level.getBlockState(pos.above()).canBeReplaced(context)) {
            return null;
        }
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()).setValue(HALF, DoubleBlockHalf.LOWER);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER) {
            return true;
        }
        BlockState below = level.getBlockState(pos.below());
        // Called before the block is set during placement (see DoublePlantBlock#canSurvive): assume the pre-check.
        if (state.getBlock() != this) {
            return true;
        }
        return below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        DoubleBlockHalf half = state.getValue(HALF);
        Direction towardsPartner = half == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN;
        if (dir == towardsPartner) {
            boolean ok = neighbour.is(this) && neighbour.getValue(HALF) != half && neighbour.getValue(FACING) == state.getValue(FACING);
            return ok ? state : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockPos below = pos.below();
            BlockState b = level.getBlockState(below);
            if (b.is(this) && b.getValue(HALF) == DoubleBlockHalf.LOWER) {
                level.setBlock(below, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, below, Block.getId(b));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? LOWER : UPPER;
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
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF);
    }
}
