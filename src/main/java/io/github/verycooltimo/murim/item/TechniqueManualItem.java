package io.github.verycooltimo.murim.item;

import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.TechniqueRequirement;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
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
 * Манускрипт техники: прочитал — техника выучена на слое 0 («знаю форму, но коряво»),
 * дальше её обживают бой, тренировка и медитация (docs/design/19 §3г).
 *
 * <p>Жёсткий порог (автор 30.09): без основ манускрипт не даётся. Требования видны в
 * подсказке заранее, чтобы игрок понимал, что искать.
 */
public class TechniqueManualItem extends Item {

    public TechniqueManualItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.consume(stack);
        }
        ResourceLocation id = stack.get(ModDataComponents.TECHNIQUE.get());
        if (id == null) {
            return InteractionResultHolder.consume(stack);
        }
        // 03.10 автор: книга открывается и читается, учит кнопка «Изучить» (ManualPayloads.Learn).
        Integer depth = stack.get(ModDataComponents.MANUAL_DEPTH.get());
        TechniqueDefinition definition = TechniqueLoader.get(id);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer, new io.github.verycooltimo.murim.network.ManualPayloads.Open(
                id, depth == null ? 0 : depth, definition == null ? "basic" : definition.tier().toString().toLowerCase(java.util.Locale.ROOT),
                hand == InteractionHand.MAIN_HAND));
        return InteractionResultHolder.consume(stack);
    }

    /** «Изучить» из книги: книга с этой техникой должна быть в руке. */
    public static void learnFromHand(ServerPlayer serverPlayer, InteractionHand hand, ResourceLocation wanted) {
        ItemStack stack = serverPlayer.getItemInHand(hand);
        if (!(stack.getItem() instanceof TechniqueManualItem item) || serverPlayer.getCooldowns().isOnCooldown(item)) {
            return;
        }
        ResourceLocation book = stack.get(ModDataComponents.TECHNIQUE.get());
        if (book == null) {
            return;
        }
        // Книга стиля учит любую свою форму (по очереди — через requires в данных формы).
        boolean styleBasic = io.github.verycooltimo.murim.technique.Styles.ofBasic(wanted)
                .equals(io.github.verycooltimo.murim.technique.Styles.of(book)) && io.github.verycooltimo.murim.technique.Styles.of(book).isPresent();
        ResourceLocation id = wanted.equals(book) || styleBasic || io.github.verycooltimo.murim.technique.Styles.sameStyle(book, wanted) ? wanted : book;
        Integer depth = stack.get(ModDataComponents.MANUAL_DEPTH.get());
        MasteryService.Learn result = MasteryService.learn(serverPlayer, id, depth == null ? 0 : depth);
        if (result == MasteryService.Learn.ALREADY) {
            serverPlayer.displayClientMessage(Component.translatable("murim.mastery.already",
                    MasteryService.name(id)).withStyle(ChatFormatting.GRAY), true);
        } else if (result == MasteryService.Learn.UNKNOWN) {
            serverPlayer.displayClientMessage(Component.translatable("murim.method.unreadable")
                    .withStyle(ChatFormatting.GRAY), true);
        }
        serverPlayer.getCooldowns().addCooldown(item, 20);
    }

    @Override
    public Component getName(ItemStack stack) {
        ResourceLocation id = stack.get(ModDataComponents.TECHNIQUE.get());
        if (id == null) {
            return super.getName(stack);
        }
        // Книга стиля называется стилем, а не первой формой.
        java.util.Optional<io.github.verycooltimo.murim.technique.Styles.Style> style = io.github.verycooltimo.murim.technique.Styles.of(id);
        if (style.isPresent() && io.github.verycooltimo.murim.technique.Styles.sequential(style.get())) {
            return Component.translatable("item.murim.technique_manual.named", Component.translatable(style.get().nameKey()));
        }
        return Component.translatable("item.murim.technique_manual.named", MasteryService.name(id));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        ResourceLocation id = stack.get(ModDataComponents.TECHNIQUE.get());
        TechniqueDefinition definition = id == null ? null : TechniqueLoader.get(id);
        if (definition == null) {
            return;
        }
        Integer depth = stack.get(ModDataComponents.MANUAL_DEPTH.get());
        lines.add(depth == null || depth >= definition.layers()
                ? Component.translatable("item.murim.technique_manual.full").withStyle(ChatFormatting.GRAY)
                : Component.translatable("item.murim.technique_manual.torn", depth).withStyle(ChatFormatting.GRAY));
        for (TechniqueRequirement requirement : definition.requires()) {
            lines.add(Component.translatable("item.murim.technique_manual.requires",
                    MasteryService.name(requirement.technique()), requirement.layer())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
