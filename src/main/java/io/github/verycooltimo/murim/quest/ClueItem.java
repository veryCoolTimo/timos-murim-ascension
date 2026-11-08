package io.github.verycooltimo.murim.quest;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** A note, scrap, rubbing or fragment: right click reads it (the shared book reader uses the player's language). */
public final class ClueItem extends Item {

    public ClueItem(Properties properties) {
        super(properties);
    }

    public static String clueOf(ItemStack stack) {
        String id = stack.get(ModQuests.CLUE.get());
        return id == null ? "" : id;
    }

    @Override
    public Component getName(ItemStack stack) {
        String id = clueOf(stack);
        return id.isEmpty() ? super.getName(stack) : Component.translatable(Clues.titleKey(id));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp && Clues.defined(clueOf(stack))) {
            Clues.read(sp, clueOf(stack));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
