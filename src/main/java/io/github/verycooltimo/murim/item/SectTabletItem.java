package io.github.verycooltimo.murim.item;

import io.github.verycooltimo.murim.sect.SectTablet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** The jade sect tablet: a journal. Right-click asks the server for a snapshot, which opens the screen. */
public class SectTabletItem extends Item {
    public SectTabletItem(Properties properties) {
        super(properties);
    }

    // API: reference/minecraft-src/net/minecraft/world/item/Item.java#use(Level, Player, InteractionHand)
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            SectTablet.open(sp);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
