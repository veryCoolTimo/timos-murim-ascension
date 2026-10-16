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
 * Shrine decor two or three blocks tall (shrine cabinet, portrait stand, floor paper lamp, hanging scrolls). PART 0 is
 * the bottom. Stacked like the tall plant, for any height: placement needs room above, parts are set in
 * {@link #setPlacedBy}, a part turns to air when a neighbour part is gone, only part 0 has loot, and breaking any
 * part in survival breaks part 0 with its drop (creative: no drop).
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/DoublePlantBlock.java (#updateShape,
 * #getStateForPlacement, #setPlacedBy, #playerWillDestroy), BedBlock.java#playerWillDestroy.
 */
public class ShrineTallBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<ShrineTallBlock> CODEC = simpleCodec(p -> new ShrineTallBlock(p, ShrineSpec.DUMMY));
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 2);

    protected final ShrineSpec spec;
    private final VoxelShape[][][] shapes;

    public ShrineTallBlock(Properties properties, ShrineSpec spec) {
        super(properties);
        this.spec = spec;
        this.shapes = ShrineBlock.buildShapes(spec);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, 0));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        for (int k = 1; k < spec.height(); k++) {
            if (pos.getY() + k >= level.getMaxBuildHeight() || !level.getBlockState(pos.above(k)).canBeReplaced(context)) {
                return null;
            }
        }
        return defaultBlockState().setValue(FACING, ShrineBlock.placeFacing(spec, context)).setValue(PART, 0);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        for (int k = spec.height() - 1; k >= 1; k--) {
            level.setBlock(pos.above(k), state.setValue(PART, k), Block.UPDATE_ALL);
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        int part = state.getValue(PART);
        int want = dir == Direction.DOWN && part > 0 ? part - 1 : dir == Direction.UP && part < spec.height() - 1 ? part + 1 : -1;
        if (want >= 0) {
            boolean ok = neighbour.is(this) && neighbour.getValue(PART) == want && neighbour.getValue(FACING) == state.getValue(FACING);
            return ok ? state : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        int part = state.getValue(PART);
        if (!level.isClientSide && part > 0) {
            BlockPos base = pos.below(part);
            BlockState b = level.getBlockState(base);
            if (b.is(this) && b.getValue(PART) == 0) {
                level.destroyBlock(base, !player.isCreative(), player);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes[0][Math.min(state.getValue(PART), shapes[0].length - 1)][state.getValue(FACING).get2DDataValue()];
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, net.minecraft.util.RandomSource random) {
        ShrineBlock.ambient(spec, state.getValue(PART), level, pos, random);
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
        builder.add(FACING, PART);
    }
}
