package io.github.verycooltimo.murim.item;

import io.github.verycooltimo.murim.cultivation.CultivationMethod;
import io.github.verycooltimo.murim.cultivation.CultivationState;
import io.github.verycooltimo.murim.cultivation.MethodLoader;
import io.github.verycooltimo.murim.cultivation.SeedLogic;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.sect.PlayerJournal;
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
 * изучил метод печатью на свитке — дальше практика медитацией. Цена метода написана на
 * самом предмете: подсказка берётся из локализации по идентификатору метода.
 *
 * <p>Author 21.10: methods can be changed outside combat without losing qi.
 */
public class MethodScrollItem extends Item {

    /** Order of the scroll textures: client property {@code murim:method_icon} is the 1-based index, 0 = generic scroll. */
    public static final List<String> ICONS = List.of("six_harmonies", "street_strength", "mountain_root",
            "reverse_breathing", "holy_fire_breath", "tang_poison_breath", "purple_sky_qi", "purple_sky_divine");

    public static int iconIndex(ResourceLocation method) {
        return method == null || !method.getNamespace().equals("murim") ? 0 : ICONS.indexOf(method.getPath()) + 1;
    }

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
            PlayerJournal.refuse(serverPlayer, Component.translatable("murim.method.unreadable")
                    .withStyle(ChatFormatting.GRAY));
            return InteractionResultHolder.consume(stack);
        }

        io.github.verycooltimo.murim.library.LibraryNetwork.openMethodScroll(serverPlayer, id, methodKey(id), methodKey(id) + ".cost");
        serverPlayer.getCooldowns().addCooldown(this, 20);
        return InteractionResultHolder.consume(stack);
    }

    /** Server snapshot: SeedLogic SAME is learned; every other SeedLogic change is allowed by the loadout gate. */
    public static io.github.verycooltimo.murim.library.LibraryNetwork.MethodState readerState(ServerPlayer player, ResourceLocation id) {
        var state = player.getData(ModAttachments.CULTIVATION);
        var method = MethodLoader.get(id);
        String reason = method == null ? "murim.method.unreadable"
                : io.github.verycooltimo.murim.mastery.LoadoutService.breathingChangeReason(player);
        boolean same = method != null && SeedLogic.learn(state, player.getData(ModAttachments.PROFILE), method,
                state.method().map(MethodLoader::get).orElse(null)).change() == SeedLogic.Change.SAME;
        boolean unread = java.util.Arrays.stream(InteractionHand.values()).map(player::getItemInHand)
                .anyMatch(stack -> stack.getItem() instanceof MethodScrollItem
                        && id.equals(stack.get(ModDataComponents.METHOD.get()))
                        && io.github.verycooltimo.murim.mastery.Originals.isOriginal(stack))
                && !player.getData(ModAttachments.READ_ORIGINALS).contains(io.github.verycooltimo.murim.mastery.Arts.methodArt(id));
        if (same && unread) return new io.github.verycooltimo.murim.library.LibraryNetwork.MethodState(id, "can", "", "");
        return new io.github.verycooltimo.murim.library.LibraryNetwork.MethodState(id,
                same ? "learned" : reason.isEmpty() ? "can" : "blocked", same ? "" : reason,
                same ? "" : state.method().map(MethodScrollItem::methodKey).orElse(""));
    }

    /** Untrusted request: the matching scroll must still be in either hand. */
    public static io.github.verycooltimo.murim.library.LibraryNetwork.MethodState requestLearn(ServerPlayer player, ResourceLocation id) {
        boolean held = false;
        for (InteractionHand hand : InteractionHand.values()) {
            var stack = player.getItemInHand(hand);
            held |= !stack.isEmpty() && stack.getItem() instanceof MethodScrollItem
                    && id.equals(stack.get(ModDataComponents.METHOD.get()));
        }
        if (!held) return new io.github.verycooltimo.murim.library.LibraryNetwork.MethodState(
                id, "blocked", "murim.method.scroll_required", "");
        var before = readerState(player, id);
        if (before.status().equals("can")) learn(player, MethodLoader.get(id));
        if (before.status().equals("can") || before.status().equals("learned")) {
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = player.getItemInHand(hand);
                if (stack.getItem() instanceof MethodScrollItem && id.equals(stack.get(ModDataComponents.METHOD.get()))) {
                    io.github.verycooltimo.murim.mastery.Originals.read(player, stack,
                            io.github.verycooltimo.murim.mastery.Arts.methodArt(id));
                }
            }
        }
        return readerState(player, id);
    }

    /** Also used when the sect mentor demonstrates a lost introductory scroll. No seed or rank is granted. */
    public static void learn(ServerPlayer serverPlayer, CultivationMethod method) {
        if (!io.github.verycooltimo.murim.mastery.LoadoutService.canChangeBreathing(serverPlayer)) return;
        CultivationState state = serverPlayer.getData(ModAttachments.CULTIVATION);
        DantianProfile profile = serverPlayer.getData(ModAttachments.PROFILE);
        CultivationMethod current = state.method().map(MethodLoader::get).orElse(null);
        SeedLogic.LearnResult result = SeedLogic.learn(state, profile, method, current);
        serverPlayer.setData(ModAttachments.CULTIVATION, result.state());
        io.github.verycooltimo.murim.mastery.MasteryService.learnMethod(serverPlayer, method);
        io.github.verycooltimo.murim.mastery.MasteryService.sync(serverPlayer);
        if (result.profile() != profile) {
            serverPlayer.setData(ModAttachments.PROFILE, result.profile());
            ProfileNetwork.sync(serverPlayer);
        }
        io.github.verycooltimo.murim.mastery.LoadoutService.sync(serverPlayer);
        io.github.verycooltimo.murim.cultivation.MeditationService.sync(serverPlayer,
                io.github.verycooltimo.murim.network.SyncMeditationPayload.Event.NONE);
        PlayerJournal.record(serverPlayer, Component.translatable(
                "murim.method.learn." + result.change().name().toLowerCase(java.util.Locale.ROOT),
                methodName(method.id())).withStyle(ChatFormatting.GRAY));
    }

    @Override
    public Component getName(ItemStack stack) {
        ResourceLocation id = stack.get(ModDataComponents.METHOD.get());
        if (id == null) {
            return super.getName(stack);
        }
        return io.github.verycooltimo.murim.mastery.Originals.name(stack, "item.murim.method_scroll.named", "item.murim.method_scroll.original", methodName(id));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines,
                                TooltipFlag flag) {
        ResourceLocation id = stack.get(ModDataComponents.METHOD.get());
        if (id == null) {
            return;
        }
        io.github.verycooltimo.murim.mastery.Originals.tooltip(stack, lines);
        // Цена метода — на самом предмете, до выбора (как было у оснований).
        lines.add(Component.translatable(methodKey(id) + ".cost").withStyle(ChatFormatting.GRAY));
        lines.addAll(TechniqueManualItem.clientFootworkInfo.apply(io.github.verycooltimo.murim.mastery.Arts.methodArt(id)));
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
