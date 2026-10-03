package io.github.verycooltimo.murim.item;

import io.github.verycooltimo.murim.cultivation.CultivationMethod;
import io.github.verycooltimo.murim.cultivation.CultivationState;
import io.github.verycooltimo.murim.cultivation.MethodLoader;
import io.github.verycooltimo.murim.cultivation.SeedLogic;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.registry.ModAttachments;
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
 * Свиток метода культивации — находимый предмет, с которого начинается путь.
 *
 * <p>Заменяет мануал со старым ритуалом (docs/design/19-dantian-qi-meditation.md §1):
 * прочитал свиток — метод твой, дальше практика медитацией. Цена метода написана на
 * самом предмете: подсказка берётся из локализации по идентификатору метода.
 *
 * <p>Смена метода после семени стоит дорого, поэтому она требует подтверждения: обычное
 * использование только предупреждает, использование на корточках — меняет.
 */
public class MethodScrollItem extends Item {

    public MethodScrollItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.consume(stack);
        }
        ResourceLocation id = stack.get(ModDataComponents.METHOD.get());
        CultivationMethod method = id == null ? null : MethodLoader.get(id);
        if (method == null) {
            serverPlayer.displayClientMessage(Component.translatable("murim.method.unreadable")
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.consume(stack);
        }

        CultivationState state = serverPlayer.getData(ModAttachments.CULTIVATION);
        DantianProfile profile = serverPlayer.getData(ModAttachments.PROFILE);
        CultivationMethod current = state.method().map(MethodLoader::get).orElse(null);

        boolean costly = state.seeded() && current != null && !current.id().equals(method.id());
        if (costly && !serverPlayer.isShiftKeyDown()) {
            String warning = current.nature().equals(method.nature())
                    ? "murim.method.warn_same_nature" : "murim.method.warn_other_nature";
            serverPlayer.displayClientMessage(Component.translatable(warning)
                    .withStyle(ChatFormatting.GOLD), false);
            return InteractionResultHolder.consume(stack);
        }

        SeedLogic.LearnResult result = SeedLogic.learn(state, profile, method, current);
        serverPlayer.setData(ModAttachments.CULTIVATION, result.state());
        if (result.profile() != profile) {
            serverPlayer.setData(ModAttachments.PROFILE, result.profile());
            ProfileNetwork.sync(serverPlayer);
        }
        serverPlayer.displayClientMessage(Component.translatable(
                "murim.method.learn." + result.change().name().toLowerCase(java.util.Locale.ROOT),
                methodName(id)).withStyle(ChatFormatting.GRAY), false);
        serverPlayer.getCooldowns().addCooldown(this, 20);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public Component getName(ItemStack stack) {
        ResourceLocation id = stack.get(ModDataComponents.METHOD.get());
        if (id == null) {
            return super.getName(stack);
        }
        return Component.translatable("item.murim.method_scroll.named", methodName(id));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines,
                                TooltipFlag flag) {
        ResourceLocation id = stack.get(ModDataComponents.METHOD.get());
        if (id == null) {
            return;
        }
        // Цена метода — на самом предмете, до выбора (как было у оснований).
        lines.add(Component.translatable(methodKey(id) + ".cost").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.murim.method_scroll.hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static Component methodName(ResourceLocation id) {
        return Component.translatable(methodKey(id));
    }

    private static String methodKey(ResourceLocation id) {
        return "method." + id.getNamespace() + "." + id.getPath();
    }
}
