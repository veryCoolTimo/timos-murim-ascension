package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** All rewards are server-side. The read ledger is per ART, including method/ IDs. */
public final class Originals {
    private Originals() {}

    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/item/ItemStack.java#getOrDefault
    public static boolean isOriginal(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.ORIGINAL.get(), false);
    }

    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/network/chat/Component.java#translatable
    public static Component name(ItemStack stack, String copyKey, String originalKey, Component base) {
        return Component.translatable(isOriginal(stack) ? originalKey : copyKey, base);
    }

    public static void tooltip(ItemStack stack, java.util.List<Component> lines) {
        if (isOriginal(stack)) {
            lines.add(Component.translatable("murim.original.hint").withStyle(ChatFormatting.GOLD));
            lines.add(Component.translatable("murim.original.cap").withStyle(ChatFormatting.GRAY));
        }
    }

    // API: /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/attachment/IAttachmentHolder.java#getData,setData
    public static boolean read(ServerPlayer player, ItemStack stack, ResourceLocation form) {
        if (player.level().isClientSide || !isOriginal(stack)) return false;
        ResourceLocation art = Arts.masteryId(form);
        ResourceLocation written = stack.get(ModDataComponents.TECHNIQUE.get());
        ResourceLocation method = stack.get(ModDataComponents.METHOD.get());
        if (stack.getItem() instanceof io.github.verycooltimo.murim.item.MethodScrollItem && method != null) {
            written = Arts.methodArt(method);
        } else if (!(stack.getItem() instanceof io.github.verycooltimo.murim.item.TechniqueManualItem)) {
            return false;
        }
        if (written == null || !Arts.masteryId(written).equals(art)) return false;
        MasteryState state = player.getData(ModAttachments.MASTERY);
        TechniqueProgress current = state.progress(art);
        if (current == null) return false; // normal learning must succeed first
        var read = player.getData(ModAttachments.READ_ORIGINALS);
        if (read.contains(art)) return false;
        var next = new java.util.ArrayList<>(read);
        next.add(art);
        player.setData(ModAttachments.READ_ORIGINALS, java.util.List.copyOf(next));
        TechniqueProgress reward = OriginalRules.read(current, false);
        player.setData(ModAttachments.MASTERY, state.with(art, reward));
        MasteryService.unlockStarForms(player);
        MasteryService.sync(player);
        return true;
    }
}
