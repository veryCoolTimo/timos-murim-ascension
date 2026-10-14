package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
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
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** Three-block rack, 1008 states. Only LOWER has loot, like the existing two-block rack. */
public class TallLibraryBookcaseBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public enum Part implements StringRepresentable {
        LOWER, MIDDLE, UPPER;

        @Override
        public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public static final IntegerProperty BOOK_SLOT = IntegerProperty.create("book_slot", 0, 5);
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final MapCodec<TallLibraryBookcaseBlock> CODEC = simpleCodec(TallLibraryBookcaseBlock::new);

    public TallLibraryBookcaseBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)
                .setValue(PART, Part.LOWER).setValue(LibraryBookcaseBlock.VARIANT, 0).setValue(LibraryBookcaseBlock.HAS_BOOK, false).setValue(BOOK_SLOT, 0));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (pos.getY() >= level.getMaxBuildHeight() - 2
                || !level.getBlockState(pos.above()).canBeReplaced(context)
                || !level.getBlockState(pos.above(2)).canBeReplaced(context)) return null;
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(LibraryBookcaseBlock.VARIANT, LibraryBookcaseBlock.variantAt(pos))
                .setValue(BOOK_SLOT, LibraryBookcaseBlock.bookSlotAt(pos, true));
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        state = state.setValue(LibraryBookcaseBlock.HAS_BOOK, false);
        // Defer neighbour checks until the entire rack exists.
        level.setBlock(pos.above(), state.setValue(PART, Part.MIDDLE), Block.UPDATE_CLIENTS);
        level.setBlock(pos.above(2), state.setValue(PART, Part.UPPER), Block.UPDATE_ALL);
        level.blockUpdated(pos.above(), this);
    }

    private boolean matches(BlockState state, BlockState other, Part part) {
        return other.is(this) && other.getValue(PART) == part
                && other.getValue(FACING) == state.getValue(FACING)
                && other.getValue(LibraryBookcaseBlock.VARIANT) == state.getValue(LibraryBookcaseBlock.VARIANT);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getBlock() != this || state.getValue(PART) == Part.LOWER) return true;
        Part below = state.getValue(PART) == Part.UPPER ? Part.MIDDLE : Part.LOWER;
        return matches(state, level.getBlockState(pos.below()), below);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        Part part = state.getValue(PART);
        if (dir == Direction.UP && part != Part.UPPER
                && !matches(state, neighbour, part == Part.LOWER ? Part.MIDDLE : Part.UPPER)) {
            return Blocks.AIR.defaultBlockState();
        }
        if (dir == Direction.DOWN && part != Part.LOWER
                && !matches(state, neighbour, part == Part.UPPER ? Part.MIDDLE : Part.LOWER)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative()) BookcaseManual.clearCreative(level, pos, state);
        if (!level.isClientSide && player.isCreative() && state.getValue(PART) != Part.LOWER) {
            BlockPos bottom = pos.below(state.getValue(PART).ordinal());
            BlockState lower = level.getBlockState(bottom);
            if (matches(state, lower, Part.LOWER)) {
                level.setBlock(bottom, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, bottom, Block.getId(lower));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return HallFurniture.turn(0, 0, 1, 16, 16, 15, state.getValue(FACING));
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) { return false; }
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }
    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BookcaseManualBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state,
            Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        return BookcaseManual.use(level, pos, state);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos,
                            RandomSource random) {
        BookcaseManual.particle(state, level, pos, random);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
                            BlockState next, boolean moving) {
        if (!state.is(next.getBlock())) BookcaseManual.removed(level, pos);
        super.onRemove(state, level, pos, next, moving);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, LibraryBookcaseBlock.VARIANT, LibraryBookcaseBlock.HAS_BOOK, BOOK_SLOT);
    }
}
