package io.github.verycooltimo.murim.decor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A placed small box (six compatible saved compartments, one kind / 64 total) or crate (18 pill slots).
 * Sneak-use toggles the persistent {@code open} block state, independently of selection menus. Contents travel with the box
 * through {@code minecraft:container}, shulker-style: the loot table copies the component on break, placing reads it back.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/entity/ChestBlockEntity.java (wooden sounds),
 * BlockEntity.java#collectImplicitComponents / applyImplicitComponents / removeComponentsFromTag, ShulkerBoxBlockEntity.
 */
public class PillBoxBlockEntity extends BlockEntity implements Container {

    private NonNullList<ItemStack> items;

    public PillBoxBlockEntity(BlockPos pos, BlockState state) {
        super(state.is(IntWorkshop.PILL_CRATE.get()) ? IntWorkshop.PILL_CRATE_ENTITY.get() : IntWorkshop.PILL_BOX_ENTITY.get(), pos, state);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
    }

    public boolean isCrate() { return getBlockState().getBlock() instanceof PillCrateBlock; }

    /** OPEN lives in the block state: vanilla chunk saving and UPDATE_ALL persist and broadcast it. */
    void toggleLid() {
        if (level == null || level.isClientSide || remove) {
            return;
        }
        BlockState state = getBlockState();
        boolean open = !state.getValue(PillBoxBlock.OPEN);
        // API: same Level#setBlock / playSound calls used by the former opener counter here.
        level.setBlock(getBlockPos(), state.setValue(PillBoxBlock.OPEN, open), Block.UPDATE_ALL);
        level.playSound(null, getBlockPos().getX() + 0.5D, getBlockPos().getY() + 0.3D, getBlockPos().getZ() + 0.5D,
                open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE,
                SoundSource.BLOCKS, 0.25F, level.random.nextFloat() * 0.1F + 1.25F);
    }

    // ------------------------------------------------------------------ container

    @Override
    public int getContainerSize() {
        return isCrate() ? 18 : PillPouch.SLOTS;
    }

    @Override
    public boolean isEmpty() {
        return items.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack out = ContainerHelper.removeItem(items, slot, isCrate() ? amount : Math.min(amount, PillPouch.withdrawalLimit(items.get(slot))));
        if (!out.isEmpty()) {
            setChanged();
        }
        return isCrate() ? out : PillPouch.withdrawn(out);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (!stack.isEmpty() && !canPlaceItem(slot, stack)) return;
        ItemStack copy = isCrate() ? stack.copy() : PillPouch.stored(stack);
        copy.limitSize(isCrate() ? getMaxStackSize(copy) : PillPouch.capacity(this, slot, copy));
        items.set(slot, copy);
        setChanged();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return isCrate() ? PillPouch.accepts(stack) : PillPouch.canInsert(this, slot, stack);
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
    }

    // Contents drive the open-box sprites; menu slots still use vanilla container synchronization.
    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        ContainerHelper.saveAllItems(tag, items, true, registries);
        return tag;
    }

    // ------------------------------------------------------------------ saving and components

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
    }

    @Override
    protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        input.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items);
    }

    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        tag.remove("Items");
    }
}
