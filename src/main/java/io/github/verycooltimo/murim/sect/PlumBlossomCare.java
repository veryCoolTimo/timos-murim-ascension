package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Server-only first-tear medicine. Existing sect flags persist across death/reload; no new save fields or client state.
 * API verified in /tmp/mr-ref-1121: neoforge-src/net/neoforged/neoforge/attachment/IAttachmentHolder.java#getData/setData;
 * minecraft-src/net/minecraft/world/entity/player/Inventory.java#add, Player.java#drop,
 * world/item/ItemStack.java#ItemStack, network/chat/Component.java#translatable.
 */
public final class PlumBlossomCare {
    public static final String TORN = "pill.first_tear";
    public static final String GIVEN = "pill.plum_blossom_given";
    public static final String LINE = "dialogue.murim.gyeong_cho.plum_blossom";

    private PlumBlossomCare() {}

    public static void torn(ServerPlayer player) {
        player.setData(ModAttachments.SECT, SectService.state(player).with(TORN));
    }

    /** Next conversation with the healer: one gift even after repeated tears, death or reconnecting. */
    public static boolean meet(ServerPlayer player, SectDisciple npc) {
        SectState state = SectService.state(player);
        if (!"gyeong_cho".equals(npc.memberKey()) || !state.has(TORN) || state.has(GIVEN)) return false;
        player.setData(ModAttachments.SECT, state.with(GIVEN));
        ItemStack gift = new ItemStack(ModItems.PILL_PLUM_BLOSSOM.get());
        if (!player.getInventory().add(gift)) player.drop(gift, false);
        SectChatter.send(npc, LINE, Component.translatable(LINE));
        return true;
    }
}
