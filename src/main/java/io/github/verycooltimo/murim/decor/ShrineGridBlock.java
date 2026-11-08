package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
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
 * House-shrine, 2 x 3 cells, 2.5 blocks tall. COL counts right (right = FACING.getCounterClockWise()), PART
 * (= row) counts up; the clicked block becomes the lower LEFT cell and the rest grows right and up. Same stacking rules
 * as {@link ShrineTallBlock}: needs room, a cell turns to air when a neighbour cell is gone, only cell (0, 0) has
 * loot, breaking any cell breaks the cell (0, 0) with its drop (creative: none).
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/DoublePlantBlock.java, BedBlock.java.
 */
public class ShrineGridBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<ShrineGridBlock> CODEC = simpleCodec(p -> new ShrineGridBlock(p, ShrineSpec.DUMMY));
    public static final IntegerProperty COL = IntegerProperty.create("col", 0, 1);
    public static final IntegerProperty PART = ShrineTallBlock.PART;

    private final ShrineSpec spec;
    private final VoxelShape[][][] shapes;

    public ShrineGridBlock(Properties properties, ShrineSpec spec) {
        super(properties);
        this.spec = spec;
        this.shapes = ShrineBlock.buildShapes(spec);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(COL, 0).setValue(PART, 0));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    private static Direction right(Direction facing) {
        return facing.getCounterClockWise();
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos base = context.getClickedPos();
        Level level = context.getLevel();
        for (int c = 0; c < spec.width(); c++) {
            for (int r = 0; r < spec.height(); r++) {
                BlockPos at = base.relative(right(facing), c).above(r);
                if (at.getY() >= level.getMaxBuildHeight() || !level.getBlockState(at).canBeReplaced(context)) {
                    return null;
                }
            }
        }
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        Direction facing = state.getValue(FACING);
        for (int c = 0; c < spec.width(); c++) {
            for (int r = 0; r < spec.height(); r++) {
                if (c != 0 || r != 0) {
                    level.setBlock(pos.relative(right(facing), c).above(r), state.setValue(COL, c).setValue(PART, r), Block.UPDATE_ALL);
                }
            }
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        int c = state.getValue(COL), r = state.getValue(PART);
        Direction facing = state.getValue(FACING);
        int wc = c, wr = r;
        if (dir == Direction.UP) {
            wr = r + 1;
        } else if (dir == Direction.DOWN) {
            wr = r - 1;
        } else if (dir == right(facing)) {
            wc = c + 1;
        } else if (dir == right(facing).getOpposite()) {
            wc = c - 1;
        } else {
            return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
        }
        if (wc < 0 || wr < 0 || wc >= spec.width() || wr >= spec.height()) {
            return state;
        }
        boolean ok = neighbour.is(this) && neighbour.getValue(COL) == wc && neighbour.getValue(PART) == wr
                && neighbour.getValue(FACING) == facing;
        return ok ? state : Blocks.AIR.defaultBlockState();
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        int c = state.getValue(COL), r = state.getValue(PART);
        if (!level.isClientSide && (c > 0 || r > 0)) {
            BlockPos base = pos.below(r).relative(right(state.getValue(FACING)).getOpposite(), c);
            BlockState b = level.getBlockState(base);
            if (b.is(this) && b.getValue(COL) == 0 && b.getValue(PART) == 0) {
                level.destroyBlock(base, !player.isCreative(), player);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes[state.getValue(COL)][state.getValue(PART)][state.getValue(FACING).get2DDataValue()];
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        // Emit once from the base cell. The ledge is at 6.5 px; the candle extends into row 1.
        // Coordinates are in the south-facing whole-model frame from shrine_rework.py.
        if (spec.fx() != ShrineSpec.Fx.OFFERINGS || state.getValue(COL) != 0 || state.getValue(PART) != 0) {
            return;
        }
        offeringParticle(state.getValue(FACING), level, pos, random, 9.7, 17.2, 13.7, true);
        offeringParticle(state.getValue(FACING), level, pos, random, 16, 12.5, 13.7, false);
    }

    private static void offeringParticle(Direction facing, Level level, BlockPos pos, RandomSource random,
                                         double x, double y, double z, boolean candle) {
        double rx = x, rz = z;
        // Same south-frame rotation as HallFurniture.turn and the OBJ blockstates.
        switch (facing) {
            case NORTH -> { rx = 16 - x; rz = 16 - z; }
            case EAST -> { rx = z; rz = 16 - x; }
            case WEST -> { rx = 16 - z; rz = x; }
            default -> { }
        }
        ShrineBlock.smallOfferingParticle(level, random, pos.getX() + rx / 16,
                pos.getY() + y / 16, pos.getZ() + rz / 16, candle);
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
        builder.add(FACING, COL, PART);
    }
}
