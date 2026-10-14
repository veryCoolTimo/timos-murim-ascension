package io.github.verycooltimo.murim.library;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;

/** Four bookshelf layouts with shared library contents; only the subtly marked one or two volumes are interactive.
 * The custom entity keeps the mod's book components and works with hoppers; no renderer or ticking entity needed.
 * Slot order is vanilla: three across the upper row, then three across the lower row, viewed from the front.
 */
public class ArchiveShelfBlock extends ChiseledBookShelfBlock {
    public static final IntegerProperty VARIANT = IntegerProperty.create("variant", 0, 3);
    public static final MapCodec<ChiseledBookShelfBlock> CODEC = simpleCodec(ArchiveShelfBlock::new);

    public ArchiveShelfBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(VARIANT, 0));
    }

    @Override
    public MapCodec<ChiseledBookShelfBlock> codec() {
        return CODEC;
    }

    static int hitSlot(BlockState state, BlockPos pos, BlockHitResult hit) {
        Direction front = state.getValue(HorizontalDirectionalBlock.FACING);
        if (hit.getDirection() != front) return -1;
        double x = hit.getLocation().x - pos.getX();
        double z = hit.getLocation().z - pos.getZ();
        double u = switch (front) {
            case NORTH -> 1.0D - x;
            case SOUTH -> x;
            case WEST -> z;
            case EAST -> 1.0D - z;
            default -> 0.0D;
        };
        double v = 1.0D - (hit.getLocation().y - pos.getY());
        for (int slot = 0; slot < 6; slot++) {
            double[] r = LibraryShelfLayout.bookBounds(state.getValue(VARIANT), slot);
            if (r != null && u * 16 >= r[0] && u * 16 < r[2]
                    && v * 16 >= r[1] && v * 16 < r[3]) return slot;
        }
        return -1;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        BlockPos pos = context.getClickedPos();
        return state == null ? null : state.setValue(VARIANT,
                LibraryShelfLayout.variantAt(pos.getX(), pos.getY(), pos.getZ()));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(VARIANT);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        int slot = hitSlot(state, pos, hit);
        if (slot < 0 || !(level.getBlockEntity(pos) instanceof ArchiveShelfBlockEntity shelf)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!stack.is(ItemTags.BOOKSHELF_BOOKS)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // Occupied cells do not redirect to another slot or consume the held book.
        if (!shelf.getItem(slot).isEmpty()) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide) {
            player.awardStat(Stats.ITEM_USED.get(stack.getItem()));
            boolean enchanted = stack.is(Items.ENCHANTED_BOOK);
            shelf.setItem(slot, player.isCreative() ? stack.copyWithCount(1) : stack.split(1));
            level.playSound(null, pos, enchanted ? SoundEvents.CHISELED_BOOKSHELF_INSERT_ENCHANTED
                    : SoundEvents.CHISELED_BOOKSHELF_INSERT, SoundSource.BLOCKS, 1.0F, 1.0F);
            level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        int slot = hitSlot(state, pos, hit);
        if (slot < 0 || !(level.getBlockEntity(pos) instanceof ArchiveShelfBlockEntity shelf)
                || shelf.getItem(slot).isEmpty()) return InteractionResult.PASS;
        if (!level.isClientSide) {
            ItemStack taken = shelf.removeItem(slot, 1);
            level.playSound(null, pos, taken.is(Items.ENCHANTED_BOOK) ? SoundEvents.CHISELED_BOOKSHELF_PICKUP_ENCHANTED
                    : SoundEvents.CHISELED_BOOKSHELF_PICKUP, SoundSource.BLOCKS, 1.0F, 1.0F);
            if (!player.getInventory().add(taken)) player.drop(taken, false);
            level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ArchiveShelfBlockEntity(pos, state);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (!state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof ArchiveShelfBlockEntity shelf) {
            Containers.dropContents(level, pos, shelf);
            level.updateNeighbourForOutputSignal(pos, this);
        }
        super.onRemove(state, level, pos, replacement, moved);
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ArchiveShelfBlockEntity shelf ? shelf.lastSlot() + 1 : 0;
    }
}
