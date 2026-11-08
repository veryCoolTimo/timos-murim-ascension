package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.item.PillItem;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * The pill pouch of the lacquer pill box (author 24.10: «как мешочек с пилюлями»): six compartments, only pills go in, the box item
 * carries them in the vanilla container component like a shulker box ({@code minecraft:container}), the placed block keeps them in its
 * block entity and gives them back through the same component when it is broken and placed again.
 */
public final class PillPouch {

    public static final int SLOTS = 6;

    private PillPouch() {
    }

    /** Actual pills only; old saved herbs/vials remain readable and withdrawable. */
    public static boolean accepts(ItemStack stack) {
        return stack.getItem() instanceof PillItem pill && pill.kind().itemId().startsWith("pill_");
    }

    /** The capacity component belongs to storage, never to a withdrawn pill. */
    public static ItemStack stored(ItemStack stack) {
        ItemStack copy = stack.copy();
        if (!copy.isEmpty()) copy.set(DataComponents.MAX_STACK_SIZE, 64);
        return copy;
    }

    public static ItemStack withdrawn(ItemStack stack) {
        if (!stack.isEmpty()) stack.set(DataComponents.MAX_STACK_SIZE, stack.getItem().getDefaultInstance().getMaxStackSize());
        return stack;
    }

    public static int withdrawalLimit(ItemStack stack) {
        return withdrawn(stack.copy()).getMaxStackSize();
    }

    public static boolean sameKind(ItemStack a, ItemStack b) {
        return ItemStack.isSameItemSameComponents(withdrawn(a.copy()), withdrawn(b.copy()));
    }

    /** The compartments of a box item. */
    public static NonNullList<ItemStack> read(ItemStack box) {
        NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items);
        return items;
    }

    /** Six saved compartments stay compatible, but their combined capacity is one kind / 64 pills. */
    public static boolean canInsert(Container box, int slot, ItemStack stack) {
        if (!accepts(stack)) return false;
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack other = box.getItem(i);
            if (!other.isEmpty() && !sameKind(other, stack)) return false;
        }
        return capacity(box, slot, stack) > 0;
    }

    public static int capacity(Container box, int slot, ItemStack stack) {
        int others = 0;
        for (int i = 0; i < box.getContainerSize(); i++) if (i != slot) others += box.getItem(i).getCount();
        return Math.max(0, 64 - others);
    }

    /** How many pills (not stacks) a box item holds. */
    public static int count(ItemStack box) {
        return (int) box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyStream()
                .mapToInt(ItemStack::getCount).sum();
    }

    /** The compartments of a box that is an item in a player's inventory: every change is written to the stack at once. */
    public static final class OfItem implements Container {
        private final Inventory inventory;
        private final int inventorySlot;
        private final ItemStack box;
        private final NonNullList<ItemStack> items;

        public OfItem(Inventory inventory, int inventorySlot) {
            this.inventory = inventory;
            this.inventorySlot = inventorySlot;
            this.box = inventory.getItem(inventorySlot);
            this.items = read(box);
        }

        public int inventorySlot() {
            return inventorySlot;
        }

        @Override
        public int getContainerSize() {
            return SLOTS;
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
            ItemStack out = net.minecraft.world.ContainerHelper.removeItem(items, slot, Math.min(amount, withdrawalLimit(items.get(slot))));
            if (!out.isEmpty()) {
                setChanged();
            }
            return withdrawn(out);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return net.minecraft.world.ContainerHelper.takeItem(items, slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (!stack.isEmpty() && !canPlaceItem(slot, stack)) return;
            ItemStack copy = stored(stack);
            copy.limitSize(capacity(this, slot, stack));
            items.set(slot, copy);
            setChanged();
        }

        @Override
        public void setChanged() {
            if (isEmpty()) {
                box.remove(DataComponents.CONTAINER);
            } else {
                box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
            }
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return canInsert(this, slot, stack);
        }

        /** The box must still be the very stack the menu was opened on. */
        @Override
        public boolean stillValid(Player player) {
            return player.getInventory() == inventory && !box.isEmpty() && inventory.getItem(inventorySlot) == box;
        }

        @Override
        public void clearContent() {
            items.clear();
            setChanged();
        }
    }
}
