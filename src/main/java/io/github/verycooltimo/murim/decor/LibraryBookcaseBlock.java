package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Library version of the open rack: narrow books, seven position-selected layouts.
 * The existing shelf supplies two-half placement, partner removal, creative break and rotation.
 * Placement copies the layout and manual slot to the upper half. 7 * 2 * 4 * 2 * 4 = 448 states.
 * Assets: tools/art/library_bookcase.py.
 */
public class LibraryBookcaseBlock extends ScrollShelfBlock implements EntityBlock {

    public static final MapCodec<LibraryBookcaseBlock> CODEC = simpleCodec(LibraryBookcaseBlock::new);
    public static final IntegerProperty VARIANT = IntegerProperty.create("variant", 0, 6);

    public static final IntegerProperty BOOK_SLOT = IntegerProperty.create("book_slot", 0, 3);

    public static final BooleanProperty HAS_BOOK =
            BooleanProperty.create("has_book");

    public LibraryBookcaseBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(VARIANT, 0).setValue(HAS_BOOK, false).setValue(BOOK_SLOT, 0));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    public static int variantAt(BlockPos pos) {
        return (int) Math.floorMod(Mth.getSeed(pos), 7L);
    }

    /** Independent salted position hash; consecutive slots select consecutive shelves in the rack. */
    public static int bookSlotAt(BlockPos pos, boolean tall) {
        return (int) Math.floorMod(Mth.getSeed(pos.offset(17, 31, -11)), tall ? 6L : 4L);
    }

    public static IntegerProperty bookSlotProperty(BlockState state) {
        return state.hasProperty(TallLibraryBookcaseBlock.PART) ? TallLibraryBookcaseBlock.BOOK_SLOT : BOOK_SLOT;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : state.setValue(VARIANT, variantAt(context.getClickedPos()))
                .setValue(BOOK_SLOT, bookSlotAt(context.getClickedPos(), false));
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
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state,
                                        Player player) {
        if (!level.isClientSide && player.isCreative()) BookcaseManual.clearCreative(level, pos, state);
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(VARIANT, HAS_BOOK, BOOK_SLOT);
    }
}
