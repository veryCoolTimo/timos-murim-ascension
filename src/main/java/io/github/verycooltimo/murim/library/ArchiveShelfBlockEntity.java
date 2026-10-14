package io.github.verycooltimo.murim.library;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Persistent marked-book slots; decorative books never enter the inventory.
 * Occupancy lives in the state, contents (including components) in NBT. */
public class ArchiveShelfBlockEntity extends BlockEntity implements Container {
    private final NonNullList<ItemStack> items = NonNullList.withSize(6, ItemStack.EMPTY);
    private int lastSlot = -1;

    public ArchiveShelfBlockEntity(BlockPos pos, BlockState state) {
        super(ModLibrary.ARCHIVE_SHELF_ENTITY.get(), pos, state);
    }

    public int lastSlot() { return lastSlot; }
    @Override public int getContainerSize() { return 6; }
    @Override public int getMaxStackSize() { return 1; }
    @Override public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }
    @Override public ItemStack getItem(int slot) { return items.get(slot); }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot >= 0 && slot < 6 && LibraryShelfLayout.available(getBlockState().getValue(ArchiveShelfBlock.VARIANT), slot)
                && items.get(slot).isEmpty() && stack.is(ItemTags.BOOKSHELF_BOOKS);
    }
    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= 6) return;
        if (!stack.isEmpty() && (!stack.is(ItemTags.BOOKSHELF_BOOKS)
                || !LibraryShelfLayout.available(getBlockState().getValue(ArchiveShelfBlock.VARIANT), slot))) return;
        items.set(slot, stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        changed(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack out = ContainerHelper.removeItem(items, slot, count);
        if (!out.isEmpty()) changed(slot);
        return out;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        // Like the vanilla shelf, even this removal must clear its visible occupied cell.
        ItemStack out = ContainerHelper.takeItem(items, slot);
        if (!out.isEmpty()) changed(slot);
        return out;
    }

    @Override
    public void clearContent() {
        items.clear();
        changed(lastSlot);
    }

    private void changed(int slot) {
        lastSlot = slot;
        setChanged();
        if (level != null && !level.isClientSide && level.getBlockState(worldPosition).is(ModLibrary.ARCHIVE_SHELF.get())) {
            BlockState state = level.getBlockState(worldPosition);
            for (int i = 0; i < 6; i++) {
                state = state.setValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(i), !items.get(i).isEmpty());
            }
            level.setBlock(worldPosition, state, Block.UPDATE_ALL);
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
            level.updateNeighbourForOutputSignal(worldPosition, state.getBlock());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.clear();
        ContainerHelper.loadAllItems(tag, items, registries);
        lastSlot = tag.contains("LastInteractedSlot") ? tag.getInt("LastInteractedSlot") : -1;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, true, registries);
        tag.putInt("LastInteractedSlot", lastSlot);
    }

    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }
}
