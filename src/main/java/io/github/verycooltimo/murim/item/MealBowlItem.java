package io.github.verycooltimo.murim.item;

import io.github.verycooltimo.murim.sect.SectMeals;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The cook's food (doc 39 item 6): eaten like any other food, anywhere and at any hunger (author 19.10: no «only at a table»,
 * and a full stomach must not block the bowl, see {@code ModItems}); eaten during a meal period it also marks the meal in the
 * sect journal. No empty bowl is left: the tableware goes back to the cook by itself. The dishes of the Mount Hua kitchen
 * ({@link io.github.verycooltimo.murim.sect.SectMenu}) and the older generic bowls are all this class; a dish sipped rather than
 * eaten uses the drink animation.
 */
public class MealBowlItem extends Item {
    /** Custom-data flag of a cooled portion (a latecomer's leftovers). */
    public static final String COLD = "cold";

    private final boolean drink;

    public MealBowlItem(Properties properties) {
        this(properties, false);
    }

    public MealBowlItem(Properties properties, boolean drink) {
        super(properties);
        this.drink = drink;
    }

    /** The food was finished on the server: the meal mark in the journal (tea gives its focus instead). */
    protected void finished(ServerPlayer player) {
        SectMeals.eaten(player);
    }

    // API: reference/minecraft-src/net/minecraft/world/item/Item.java#finishUsingItem(ItemStack, Level, LivingEntity)
    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        ItemStack rest = super.finishUsingItem(stack, level, entity);
        if (!level.isClientSide && entity instanceof ServerPlayer player) {
            finished(player);
        }
        return rest;
    }

    // API: reference/minecraft-src/net/minecraft/world/item/Item.java#getUseAnimation(ItemStack)
    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return drink ? UseAnim.DRINK : UseAnim.EAT;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, net.minecraft.world.item.TooltipFlag flag) {
        net.minecraft.world.item.component.CustomData data = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY);
        tooltip.add(Component.translatable(data.copyTag().getBoolean(COLD)
                ? "murim.sect.meal.cold_tip" : "murim.sect.meal.cooling_tip").withStyle(ChatFormatting.GRAY));
    }
}
