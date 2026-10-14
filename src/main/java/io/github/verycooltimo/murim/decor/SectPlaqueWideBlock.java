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
 * Wide sect name plaque, four blocks (author 07.10: «табличку сделать в 4 блока шириной»; ref
 * docs/design/reference/decor/plaque_hanja_ref.png). {@link #PART} 0..3 from the viewer's left; the clicked spot
 * becomes part 1 so the board is centred on it. The board is 18 px tall (y −1..17), close to the ref's 3.6:1.
 *
 * <p>Like {@link SectPlaqueBlock}: placement needs all four spots free, a part with a missing neighbour drops to
 * air (so breaking any part takes the whole row), only part 1 has loot, and in creative breaking another part
 * removes part 1 first without drops. {@link #INSET} moves the board back flush onto the pillar, beam or block
 * behind the clicked spot ({@link SectPlaqueBlock#insetFor}).
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/BedBlock.java (#setPlacedBy, #updateShape,
 * #playerWillDestroy); Block#updateOrDestroy drops the loot part when the cascade removes it.
 */
public class SectPlaqueWideBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<SectPlaqueWideBlock> CODEC = simpleCodec(SectPlaqueWideBlock::new);
    public static final int PARTS = 4;
    /** The part placed at the clicked spot; the only one with loot. */
    public static final int ANCHOR = 1;
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, PARTS - 1);
    public static final IntegerProperty INSET = SectPlaqueBlock.INSET;

    // Board y -1..17, 2 px deep (board 1 + frame 1) against the back of the block.
    private static final VoxelShape NORTH = Block.box(0, -1, 14, 16, 17, 16);
    private static final VoxelShape SOUTH = Block.box(0, -1, 0, 16, 17, 2);
    private static final VoxelShape WEST = Block.box(14, -1, 0, 16, 17, 16);
    private static final VoxelShape EAST = Block.box(0, -1, 0, 2, 17, 16);

    public SectPlaqueWideBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, ANCHOR).setValue(INSET, 0));
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
        BlockState state = defaultBlockState().setValue(FACING, facing).setValue(PART, ANCHOR)
                .setValue(INSET, SectPlaqueBlock.insetFor(facing, level, pos.relative(facing.getOpposite())));
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
        double back = state.getValue(INSET) / 32.0;
        return base.move(-facing.getStepX() * back, 0, -facing.getStepZ() * back);
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
        builder.add(FACING, PART, INSET);
    }
}
