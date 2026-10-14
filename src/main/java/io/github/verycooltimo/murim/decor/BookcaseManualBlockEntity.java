package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.library.ModLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** One generated manual per rack part. No insertion API: decorative books never become loot. */
public class BookcaseManualBlockEntity extends BlockEntity {
    // Client-only, transient: animateTick drives the mote; no entity ticker or saved timer.
    private long nextParticleAt = -1L;

    private final NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);

    public BookcaseManualBlockEntity(BlockPos pos, BlockState state) {
        super(ModLibrary.BOOKCASE_MANUAL_ENTITY.get(), pos, state);
    }

    boolean particleDue(long now, RandomSource random) {
        if (nextParticleAt < 0L) {
            nextParticleAt = now + 60 + random.nextInt(41);
            return false;
        }
        if (now < nextParticleAt) return false;
        nextParticleAt = now + 60 + random.nextInt(41);
        return true;
    }

    public ItemStack manual() { return items.get(0); }

    public void setManual(ItemStack stack) {
        items.set(0, stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = level.getBlockState(worldPosition);
            if (state.hasProperty(LibraryBookcaseBlock.HAS_BOOK)) {
                level.setBlock(worldPosition, state.setValue(LibraryBookcaseBlock.HAS_BOOK, !stack.isEmpty()), Block.UPDATE_ALL);
            }
        }
    }

    public ItemStack takeManual() {
        ItemStack stack = manual();
        setManual(ItemStack.EMPTY);
        return stack;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, true, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);
    }
}
