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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Grand name plaque of the main hall, eight blocks wide (coordinator 15.10). Same behaviour as
 * {@link SectPlaqueWideBlock} with {@link #PARTS} = 8 and no inset (it hangs on the porch beam line): the clicked spot
 * becomes part 3, a part with a missing neighbour drops to air, only part 3 has loot.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/BedBlock.java (#setPlacedBy, #updateShape,
 * #playerWillDestroy).
 */
public class HallPlaqueGrandBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<HallPlaqueGrandBlock> CODEC = simpleCodec(HallPlaqueGrandBlock::new);
    public static final int PARTS = 8;
    /** The part placed at the clicked spot; the only one with loot. */
    public static final int ANCHOR = 3;
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, PARTS - 1);

    // Board y -1..17, 2 px deep (board 1 + frame 1) against the back of the block.
    private static final VoxelShape NORTH = Block.box(0, -1, 14, 16, 17, 16);
    private static final VoxelShape SOUTH = Block.box(0, -1, 0, 16, 17, 2);
    private static final VoxelShape WEST = Block.box(14, -1, 0, 16, 17, 16);
    private static final VoxelShape EAST = Block.box(0, -1, 0, 2, 17, 16);

    public HallPlaqueGrandBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, ANCHOR));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    /** The viewer's right: the viewer faces {@code -facing}. */
    static Direction right(BlockState state) {
        return state.getValue(FACING).getCounterClockWise();
    }

    /** Position of part {@code k} of the row that {@code state} at {@code pos} belongs to. */
    public static BlockPos partPos(BlockState state, BlockPos pos, int k) {
        return pos.relative(right(state), k - state.getValue(PART));
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clicked = context.getClickedFace();
        Direction facing = clicked.getAxis().isHorizontal() ? clicked : context.getHorizontalDirection().getOpposite();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = defaultBlockState().setValue(FACING, facing).setValue(PART, ANCHOR);
        for (int k = 0; k < PARTS; k++) {
            if (k == ANCHOR) {
                continue;
            }
            BlockPos other = partPos(state, pos, k);
            if (!level.getBlockState(other).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(other)) {
                return null;
            }
        }
        return state;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide) {
            for (int k = 0; k < PARTS; k++) {
                if (k != state.getValue(PART)) {
                    level.setBlock(partPos(state, pos, k), state.setValue(PART, k), Block.UPDATE_ALL);
                }
            }
            level.blockUpdated(pos, Blocks.AIR);
            state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        int part = state.getValue(PART);
        Direction r = right(state);
        int expected = dir == r ? part + 1 : dir == r.getOpposite() ? part - 1 : -1;
        if (expected >= 0 && expected < PARTS) {
            return neighbour.is(this) && neighbour.getValue(PART) == expected && neighbour.getValue(FACING) == state.getValue(FACING)
                    ? state : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // Creative: take the loot part first, without drops, so the cascade cannot drop it.
        if (!level.isClientSide && player.isCreative() && state.getValue(PART) != ANCHOR) {
            BlockPos anchor = partPos(state, pos, ANCHOR);
            BlockState a = level.getBlockState(anchor);
            if (a.is(this) && a.getValue(PART) == ANCHOR) {
                level.setBlock(anchor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, anchor, Block.getId(a));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        VoxelShape base = switch (facing) {
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
            default -> NORTH;
        };
        return base;
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
        if (mirror == Mirror.NONE) {
            return state;
        }
        // A mirror swaps the viewer's left and right: parts swap ends so the text still reads 大華山派.
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING))).setValue(PART, PARTS - 1 - state.getValue(PART));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }
}
