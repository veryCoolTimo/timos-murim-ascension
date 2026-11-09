package io.github.verycooltimo.murim.library;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** The first-join book: fixed text, the direction of Mount Hua is worked out from the reader's position. */
public final class SpawnBookItem extends Item {
    public SpawnBookItem(Properties properties) { super(properties); }

    @Override
    public Component getName(ItemStack stack) { return Component.translatable(SpawnBook.TITLE); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer sp) SpawnBook.open(sp);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }
}
