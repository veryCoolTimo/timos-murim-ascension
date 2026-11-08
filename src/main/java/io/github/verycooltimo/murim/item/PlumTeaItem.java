package io.github.verycooltimo.murim.item;

import io.github.verycooltimo.murim.cultivation.TeaFocus;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * A cup of plum-blossom tea, the one Mount Hua dish with an effect (author 18.10): a short focus for meditation
 * ({@link TeaFocus}). A cup may be carried to the cushion, and it does not count as a meal in the journal.
 */
public class PlumTeaItem extends MealBowlItem {

    public PlumTeaItem(Properties properties) {
        super(properties, true);
    }

    @Override
    protected void finished(ServerPlayer player) {
        TeaFocus.grant(player);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, net.minecraft.world.item.TooltipFlag flag) {
        tooltip.add(Component.translatable("item.murim.plum_tea.desc").withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
