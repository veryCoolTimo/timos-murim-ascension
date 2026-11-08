package io.github.verycooltimo.murim.decor;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The menu of the pill box: six compartments (one row, on red silk) and the player's inventory. The container is the block entity of a
 * placed box ({@link PillBoxBlockEntity}) or the pouch of a box item ({@link PillPouch.OfItem}); the server is authoritative, the
 * client shows a plain {@link SimpleContainer} that the slot packets fill. Only pills may be put in.
 */
public final class PillBoxMenu extends AbstractContainerMenu {

    // Layout shared with the screen texture (tools/art/pill_art/box_menu_texture.py)
    public static final int WIDTH = 176;
    public static final int HEIGHT = 154;
    public static final int POUCH_X = 15;
    public static final int POUCH_Y = 27;
    public static final int POUCH_STEP = 26;
    public static final int INV_X = 8;
    public static final int INV_Y = 70;
    public static final int HOTBAR_Y = 128;

    private final Container box;
    private final int boxSlots;
    private final boolean crate;
    /** The inventory index of the box item this menu was opened on (its slot is locked), -1 for a placed block. */
    private final int lockedSlot;

    public PillBoxMenu(int id, Inventory inventory, Container box, int lockedSlot) {
        this(id, inventory, box, lockedSlot, box.getContainerSize() == 18);
    }

    private PillBoxMenu(int id, Inventory inventory, Container box, int lockedSlot, boolean crate) {
        super(IntWorkshop.PILL_BOX_MENU.get(), id);
        this.crate = crate;
        boxSlots = crate ? 18 : PillPouch.SLOTS;
        checkContainerSize(box, boxSlots);
        this.box = box;
        this.lockedSlot = lockedSlot;
        box.startOpen(inventory.player);
        for (int i = 0; i < boxSlots; i++) {
            addSlot(new PillSlot(box, i, crate ? 8 + i % 9 * 18 : POUCH_X + i * POUCH_STEP, crate ? 20 + i / 9 * 18 : POUCH_Y));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Held(inventory, col + row * 9 + 9, INV_X + col * 18, INV_Y + row * 18, lockedSlot));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Held(inventory, col, INV_X + col * 18, HOTBAR_Y, lockedSlot));
        }
    }

    /**
     * Opens a menu for a player: the usual NeoForge open-screen packet, with the locked inventory index as the extra data. A player
     * whose connection has no mod channels (a GameTest actor) gets the same server-side menu without the packet.
     */
    public static void open(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.MenuProvider provider, int lockedSlot) {
        open(player, provider, lockedSlot, false);
    }

    public static void open(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.MenuProvider provider, int lockedSlot, boolean crate) {
        if (player.connection != null && player.connection.hasChannel(
                net.neoforged.neoforge.network.payload.AdvancedOpenScreenPayload.TYPE)) {
            player.openMenu(provider, buf -> { buf.writeVarInt(lockedSlot); buf.writeBoolean(crate); });
            return;
        }
        if (player.containerMenu != player.inventoryMenu) {
            player.doCloseContainer();
        }
        AbstractContainerMenu menu = provider.createMenu(0, player.getInventory(), player);
        if (menu != null) {
            player.containerMenu = menu;
        }
    }

    /** Client side: the real contents arrive in the slot packets. */
    public static PillBoxMenu fromNetwork(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        int locked = buf.readVarInt();
        boolean crate = buf.readBoolean();
        Container client = new SimpleContainer(crate ? 18 : PillPouch.SLOTS) {
            @Override public void setItem(int slot, ItemStack stack) {
                super.setItem(slot, crate ? stack : PillPouch.stored(stack));
            }
            @Override public ItemStack removeItem(int slot, int amount) {
                return crate ? super.removeItem(slot, amount) : PillPouch.withdrawn(
                        super.removeItem(slot, Math.min(amount, PillPouch.withdrawalLimit(getItem(slot)))));
            }
            @Override public boolean canPlaceItem(int slot, ItemStack stack) {
                return crate ? PillPouch.accepts(stack) : PillPouch.canInsert(this, slot, stack);
            }
        };
        return new PillBoxMenu(id, inventory, client, locked, crate);
    }

    public boolean isCrate() { return crate; }

    /** An ordinary click on a displayed kind withdraws one, shift-click uses vanilla stack transfer. */
    @Override
    public void clicked(int index, int button, net.minecraft.world.inventory.ClickType type, Player player) {
        // A hotbar swap could move a compressed storage stack without the withdrawal limit.
        if (!crate && index >= 0 && index < boxSlots && (type == net.minecraft.world.inventory.ClickType.SWAP
                || type == net.minecraft.world.inventory.ClickType.CLONE)) return;
        if (index >= 0 && index < boxSlots && type == net.minecraft.world.inventory.ClickType.PICKUP
                && slots.get(index).hasItem()) {
            ItemStack selected = slots.get(index).getItem();
            ItemStack carried = getCarried();
            if (carried.isEmpty() || PillPouch.sameKind(carried, selected)
                    && carried.getCount() < carried.getMaxStackSize()) {
                ItemStack one = slots.get(index).remove(1);
                if (carried.isEmpty()) setCarried(one); else carried.grow(one.getCount());
                slots.get(index).setChanged();
                return;
            }
            if (!crate && PillPouch.sameKind(carried, selected)) return;
        }
        super.clicked(index, button, type, player);
    }

    public int lockedSlot() {
        return lockedSlot;
    }

    public Container box() {
        return box;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem() || !slot.mayPickup(player)) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        boolean fromBox = index < boxSlots;
        if (!crate) {
            if (fromBox) {
                ItemStack transfer = PillPouch.withdrawn(stack.copy());
                transfer.setCount(Math.min(transfer.getCount(), transfer.getMaxStackSize()));
                int before = transfer.getCount();
                if (!moveItemStackTo(transfer, boxSlots, slots.size(), true)) return ItemStack.EMPTY;
                box.removeItem(index, before - transfer.getCount());
                slot.setChanged();
                return copy;
            }
            if (!PillPouch.canInsert(box, 0, stack)) return ItemStack.EMPTY;
            int space = 64;
            for (int i = 0; i < boxSlots; i++) space -= box.getItem(i).getCount();
            int amount = Math.min(space, stack.getCount());
            if (amount <= 0) return ItemStack.EMPTY;
            int destination = -1;
            for (int i = 0; i < boxSlots; i++) {
                if (!box.getItem(i).isEmpty()) { destination = i; break; }
            }
            if (destination < 0) destination = 0;
            ItemStack combined = stack.copy();
            combined.setCount(box.getItem(destination).getCount() + amount);
            box.setItem(destination, combined);
            stack.shrink(amount);
            slot.setChanged();
            return copy;
        }
        boolean moved = fromBox
                ? moveItemStackTo(stack, boxSlots, slots.size(), true)
                : PillPouch.accepts(stack) && moveItemStackTo(stack, 0, boxSlots, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return box.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        box.stopOpen(player);
    }

    /** A compartment: pills only. */
    private static final class PillSlot extends Slot {
        PillSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return container.getContainerSize() == 18 ? super.getMaxStackSize(stack)
                    : PillPouch.capacity(container, getContainerSlot(), stack);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return container.canPlaceItem(getContainerSlot(), stack);
        }
    }

    /** A slot of the player's inventory; the box item the menu was opened on cannot be moved while it is open. */
    private static final class Held extends Slot {
        private final boolean locked;

        Held(Inventory inventory, int index, int x, int y, int lockedSlot) {
            super(inventory, index, x, y);
            this.locked = index == lockedSlot;
        }

        @Override
        public boolean mayPickup(Player player) {
            return !locked;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !locked;
        }
    }
}
