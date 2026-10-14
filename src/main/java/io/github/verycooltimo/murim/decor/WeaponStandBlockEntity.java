package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Swords on a weapon stand: 3 slots on the training rack, 1 on the single stand. Saved in NBT ({@code Items}, the
 * ContainerHelper format) and synced to clients with the block-entity data packet, like the campfire's items.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/entity/CampfireBlockEntity.java (#loadAdditional,
 * #saveAdditional, #getUpdatePacket, #getUpdateTag, #markUpdated); ContainerHelper.java#saveAllItems/loadAllItems.
 */
public class WeaponStandBlockEntity extends BlockEntity {

    private final NonNullList<ItemStack> items;

    public WeaponStandBlockEntity(BlockPos pos, BlockState state) {
        super(WeaponRacks.WEAPON_STAND_ENTITY.get(), pos, state);
        int slots = state.getBlock() instanceof WeaponStandBlock b ? b.kind().slots() : 1;
        items = NonNullList.withSize(slots, ItemStack.EMPTY);
    }

    public NonNullList<ItemStack> items() {
        return items;
    }

    public ItemStack get(int slot) {
        return items.get(slot);
    }

    /** The aimed slot when it is free, else the free slot nearest to it; −1 when full. */
    public int freeSlotNear(int aimed) {
        return nearest(aimed, true);
    }

    /** The aimed slot when it holds a sword, else the stored one nearest to it; −1 when empty. */
    public int storedSlotNear(int aimed) {
        return nearest(aimed, false);
    }

    private int nearest(int aimed, boolean free) {
        for (int d = 0; d < items.size(); d++) {
            for (int s : new int[] {aimed - d, aimed + d}) {
                if (s >= 0 && s < items.size() && items.get(s).isEmpty() == free) {
                    return s;
                }
            }
        }
        return -1;
    }

    public void put(int slot, ItemStack stack) {
        items.set(slot, stack);
        markUpdated();
    }

    public ItemStack take(int slot) {
        ItemStack out = items.get(slot);
        items.set(slot, ItemStack.EMPTY);
        markUpdated();
        return out;
    }

    /** Default contents for stands placed by world generation: wooden swords on the rack, a Huashan sword on the stand. */
    public void fillDefault() {
        boolean rack = items.size() > 1;
        for (int i = 0; i < items.size(); i++) {
            items.set(i, new ItemStack(rack ? ModItems.WOODEN_SWORD.get() : ModItems.HUASHAN_SWORD.get()));
        }
        markUpdated();
    }

    /**
     * Places a stand already holding its default swords (for structures and sect layouts). Nothing in worldgen places
     * weapon stands yet; whoever does should call this instead of a bare setBlock.
     */
    public static void placeFilled(Level level, BlockPos pos, Block block, Direction facing) {
        level.setBlock(pos, block.defaultBlockState().setValue(WeaponStandBlock.FACING, facing), Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof WeaponStandBlockEntity stand) {
            stand.fillDefault();
        }
    }

    private void markUpdated() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        for (int i = 0; i < items.size(); i++) {
            items.set(i, ItemStack.EMPTY);
        }
        ContainerHelper.loadAllItems(tag, items, registries);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, true, registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        ContainerHelper.saveAllItems(tag, items, true, registries);
        return tag;
    }
}
