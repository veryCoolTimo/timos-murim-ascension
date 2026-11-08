package io.github.verycooltimo.murim.decor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * The lacquer pill box as an item: a pouch of six compartments for pills (author 24.10). A right click in the air with the box in hand
 * opens the menu; a right click on it in an inventory screen opens it too (client/decor/PillBoxClient sends {@code PillBoxOpenPayload}, the
 * server checks and opens). The contents sit in {@code minecraft:container}; the item does not stack, so a stack never shares them.
 */
public class PillBoxItem extends BlockItem {

    public PillBoxItem(Block block, Properties properties) {
        super(block, properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer server) {
            open(server, hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : OFFHAND_SLOT);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** The inventory index of the off hand. */
    public static final int OFFHAND_SLOT = 40;

    /** Opens the pouch of the box item at this inventory index of the player; ignores a slot that holds no box. */
    public static void open(ServerPlayer player, int inventorySlot) {
        if (inventorySlot < 0 || inventorySlot > OFFHAND_SLOT || !(player.getInventory().getItem(inventorySlot).getItem() instanceof PillBoxItem)) {
            return;
        }
        PillPouch.OfItem pouch = new PillPouch.OfItem(player.getInventory(), inventorySlot);
        PillBoxMenu.open(player, new SimpleMenuProvider((id, inventory, p) -> new PillBoxMenu(id, inventory, pouch, inventorySlot),
                Component.translatable("block.murim.int_pill_box")), inventorySlot);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.murim.int_pill_box.desc").withStyle(ChatFormatting.GRAY));
        ItemContainerContents contents = stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        int count = contents.nonEmptyStream().mapToInt(ItemStack::getCount).sum();
        if (count > 0) {
            tooltip.add(Component.translatable("item.murim.int_pill_box.count", count).withStyle(ChatFormatting.DARK_AQUA));
        }
    }
}
