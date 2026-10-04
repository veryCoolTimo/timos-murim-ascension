package io.github.verycooltimo.murim.item;

import io.github.verycooltimo.murim.mastery.PageRules;
import io.github.verycooltimo.murim.mastery.PageService;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Обрывок манускрипта (docs/design/24-bandit-camp.md §3): страница книги стиля или техники —
 * добыча лагеря бандитов. Какая книга — компонент {@link ModDataComponents#TECHNIQUE}, поэтому
 * страницы разных книг не складываются в одну стопку. ПКМ — прочитать: сшить три в рваный
 * манускрипт, продолжить рваную книгу, получить озарение или мудрость ({@link PageRules}).
 */
public class ManualPageItem extends Item {

    public ManualPageItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.consume(stack);
        }
        if (serverPlayer.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.fail(stack);
        }
        PageService.readPage(serverPlayer, stack);
        serverPlayer.getCooldowns().addCooldown(this, 10);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public Component getName(ItemStack stack) {
        ResourceLocation id = stack.get(ModDataComponents.TECHNIQUE.get());
        return id == null ? super.getName(stack)
                : Component.translatable("item.murim.manual_page.named", PageService.bookName(id));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        if (stack.get(ModDataComponents.TECHNIQUE.get()) == null) {
            return;
        }
        lines.add(Component.translatable("item.murim.manual_page.hint_bind", PageRules.PAGES_TO_BIND)
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.murim.manual_page.hint_known").withStyle(ChatFormatting.DARK_GRAY));
    }
}
