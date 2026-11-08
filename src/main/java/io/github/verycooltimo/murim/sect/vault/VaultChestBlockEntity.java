package io.github.verycooltimo.murim.sect.vault;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 27-slot vault inventory. API and persistence follow decor/StoStorageBlockEntity. */
public class VaultChestBlockEntity extends RandomizableContainerBlockEntity {
    private NonNullList<ItemStack> items;
    /** Server-only ledger. Missing in old saves means no claims. Never sent to clients. */
    private CompoundTag originalClaims = new CompoundTag();
    private boolean crown;
    private boolean crownTaken;
    private long crownTakenTime;

    private static String originalKey(ItemStack book) {
        var method = book.get(io.github.verycooltimo.murim.registry.ModDataComponents.METHOD.get());
        var technique = book.get(io.github.verycooltimo.murim.registry.ModDataComponents.TECHNIQUE.get());
        return method != null ? "method/" + method : technique != null ? "technique/" + technique : "";
    }

    /** Claim a copy, never the shared stack. All callers run on the server thread. */
    public ItemStack claimOriginal(java.util.UUID player, int slot) {
        ItemStack book = getItem(slot);
        if (!io.github.verycooltimo.murim.mastery.Originals.isOriginal(book)) return ItemStack.EMPTY;
        String key = originalKey(book);
        CompoundTag claimed = originalClaims.getCompound(player.toString());
        if (key.isEmpty() || claimed.getBoolean(key)) return ItemStack.EMPTY;
        claimed.putBoolean(key, true);
        originalClaims.put(player.toString(), claimed);
        if (crown && key.equals("technique/murim:twenty_four_plum_rainfall") && !crownTaken) {
            crownTaken = true;
            crownTakenTime = level == null ? 0L : level.getGameTime();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        setChanged();
        return book.copyWithCount(1);
    }

    /** Restore authored assignments, without guessing a loot slot or replacing unrelated loot. */
    void restoreOriginals(BlockPos local) {
        ItemStack assigned = HuaVault.assignedOriginal(local);
        if (!assigned.isEmpty() && getItem(0).isEmpty()) setItem(0, assigned);
        if (crown || local.equals(VaultPlan.CHEST)) {
            restoreOriginal(HuaVault.original("method/purple_sky_divine"));
            restoreOriginal(HuaVault.original("twenty_four_plum_rainfall"));
        }
    }

    private void restoreOriginal(ItemStack book) {
        for (int i = 0; i < getContainerSize(); i++) {
            if (originalKey(getItem(i)).equals(originalKey(book))
                    && io.github.verycooltimo.murim.mastery.Originals.isOriginal(getItem(i))) return;
        }
        for (int i = 0; i < getContainerSize(); i++) {
            if (getItem(i).isEmpty()) { setItem(i, book); return; }
        }
    }

    public void giveOriginals(Player player) {
        if (level == null || level.isClientSide || player.isSpectator()) return;
        if (level instanceof net.minecraft.server.level.ServerLevel server) {
            var site = io.github.verycooltimo.murim.world.hua.MountHuaSites.get(server.getServer());
            if (HuaVault.applies(site)) restoreOriginals(HuaVault.local(site, worldPosition));
            else if (crown) restoreOriginals(VaultPlan.CHEST);
        }
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack copy = claimOriginal(player.getUUID(), i);
            if (!copy.isEmpty() && !player.getInventory().add(copy)) player.drop(copy, false);
        }
    }

    // API verified in 1.21.1 Container and BaseContainerBlockEntity: no automation may extract originals.
    @Override public boolean canTakeItem(net.minecraft.world.Container target, int slot, ItemStack stack) {
        return !io.github.verycooltimo.murim.mastery.Originals.isOriginal(getItem(slot)) && super.canTakeItem(target, slot, stack);
    }
    @Override public ItemStack removeItem(int slot, int count) {
        return io.github.verycooltimo.murim.mastery.Originals.isOriginal(getItem(slot)) ? ItemStack.EMPTY : super.removeItem(slot, count);
    }
    @Override public ItemStack removeItemNoUpdate(int slot) {
        return io.github.verycooltimo.murim.mastery.Originals.isOriginal(getItem(slot)) ? ItemStack.EMPTY : super.removeItemNoUpdate(slot);
    }

    public boolean crown() { return crown; }
    public boolean crownTaken() { return crownTaken; }
    public long crownTakenTime() { return crownTakenTime; }
    public void markCrown() {
        crown = true;
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** Inspect after loot generation and container edits finish, including hopper and in-place stack edits. */
    public void checkCrown() {
        if (level == null || level.isClientSide || !crown || crownTaken || getLootTable() != null) return;
        boolean present = items.stream().anyMatch(stack -> io.github.verycooltimo.murim.mastery.Originals.isOriginal(stack)
                && net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum_rainfall")
                .equals(stack.get(io.github.verycooltimo.murim.registry.ModDataComponents.TECHNIQUE.get())));
        if (!present) {
            crownTaken = true;
            crownTakenTime = level.getGameTime();
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/level/block/entity/BlockEntity.java#getUpdatePacket,getUpdateTag
    @Override public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("ArchiveCrown", crown);
        tag.putBoolean("ArchiveCrownTaken", crownTaken);
        tag.putLong("ArchiveCrownTakenTime", crownTakenTime);
        return tag;
    }
    private final ContainerOpenersCounter openers = new ContainerOpenersCounter() {
        @Override
        protected void onOpen(Level level, BlockPos pos, BlockState state) {
            setOpen(state, true);
        }

        @Override
        protected void onClose(Level level, BlockPos pos, BlockState state) {
            setOpen(state, false);
        }

        @Override
        protected void openerCountChanged(Level level, BlockPos pos, BlockState state, int a, int b) {
        }

        @Override
        protected boolean isOwnContainer(Player player) {
            return player.containerMenu instanceof ChestMenu menu && menu.getContainer() == VaultChestBlockEntity.this;
        }
    };

    public VaultChestBlockEntity(BlockPos pos, BlockState state) {
        super(VaultRegistry.VAULT_CHEST_ENTITY.get(), pos, state);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
    }

    private void setOpen(BlockState state, boolean open) {
        if (level != null && state.hasProperty(VaultChestBlock.OPEN)) {
            level.setBlock(worldPosition, state.setValue(VaultChestBlock.OPEN, open), 3);
            level.playSound(null, worldPosition, open ? net.minecraft.sounds.SoundEvents.CHEST_OPEN
                    : net.minecraft.sounds.SoundEvents.CHEST_CLOSE, net.minecraft.sounds.SoundSource.BLOCKS, 0.5F, 1.0F);
        }
    }

    @Override
    public int getContainerSize() {
        return 27;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inv) {
        ChestMenu menu = new ChestMenu(MenuType.GENERIC_9x3, id, inv, this, 3) {
            @Override public void clicked(int slot, int button, net.minecraft.world.inventory.ClickType type, Player player) {
                if (slot >= 0 && slot < 27 && io.github.verycooltimo.murim.mastery.Originals.isOriginal(VaultChestBlockEntity.this.getItem(slot))) return;
                super.clicked(slot, button, type, player);
            }
            private ItemStack menuItem(int slot) { return slots.get(slot).getItem(); }
            @Override public ItemStack quickMoveStack(Player player, int index) {
                if (index >= 0 && index < 27 && io.github.verycooltimo.murim.mastery.Originals.isOriginal(VaultChestBlockEntity.this.getItem(index))) return ItemStack.EMPTY;
                if (index >= 27 && io.github.verycooltimo.murim.mastery.Originals.isOriginal(menuItem(index))) return ItemStack.EMPTY;
                return super.quickMoveStack(player, index);
            }
        };
        for (int i = 0; i < 27; i++) {
            var previous = menu.slots.get(i);
            var slot = new net.minecraft.world.inventory.Slot(this, i, previous.x, previous.y) {
                @Override public boolean mayPickup(Player player) {
                    return !io.github.verycooltimo.murim.mastery.Originals.isOriginal(getItem());
                }
                @Override public boolean mayPlace(ItemStack stack) {
                    return !io.github.verycooltimo.murim.mastery.Originals.isOriginal(getItem()) && super.mayPlace(stack);
                }
            };
            slot.index = i;
            menu.slots.set(i, slot);
        }
        return menu;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!originalClaims.isEmpty()) tag.put("OriginalClaims", originalClaims.copy());
        tag.putBoolean("ArchiveCrown", crown);
        tag.putBoolean("ArchiveCrownTaken", crownTaken);
        tag.putLong("ArchiveCrownTakenTime", crownTakenTime);
        if (!trySaveLootTable(tag)) {
            ContainerHelper.saveAllItems(tag, items, registries);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        originalClaims = tag.getCompound("OriginalClaims").copy();
        crown = tag.getBoolean("ArchiveCrown");
        crownTaken = tag.getBoolean("ArchiveCrownTaken");
        crownTakenTime = tag.getLong("ArchiveCrownTakenTime");
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        if (!tryLoadLootTable(tag)) {
            ContainerHelper.loadAllItems(tag, items, registries);
        }
    }

    @Override
    public void startOpen(Player player) {
        if (!remove && !player.isSpectator()) {
            openers.incrementOpeners(player, level, worldPosition, getBlockState());
        }
    }

    @Override
    public void stopOpen(Player player) {
        if (!remove && !player.isSpectator()) {
            openers.decrementOpeners(player, level, worldPosition, getBlockState());
        }
    }

    public void recheckOpen() {
        if (!remove) {
            openers.recheckOpeners(level, worldPosition, getBlockState());
        }
    }
}
