package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The focus of a cup of plum-blossom tea (Mount Hua kitchen, author 18.10): for a short while the mind settles and the
 * meditation reserve grows a little faster. Deliberately mild, no power creep: +10 % for 2.5 minutes, not stacking (a second cup
 * only renews the time), gone with the session (a transient attachment, like the stun immunity).
 */
public final class TeaFocus {
    /** How long the focus lasts, ticks (2.5 minutes). */
    public static final int TICKS = 3000;
    /** Multiplier of the meditation gain while the focus lasts. */
    public static final double FACTOR = 1.10D;

    private TeaFocus() {
    }

    /** The player drank a cup now. Server only. */
    public static void grant(ServerPlayer player) {
        player.setData(ModAttachments.TEA_FOCUS_END, player.level().getGameTime() + TICKS);
        PlayerJournal.record(player, Component.translatable("murim.tea.focus").withStyle(ChatFormatting.GRAY));
    }

    public static boolean active(ServerPlayer player) {
        return player.getData(ModAttachments.TEA_FOCUS_END) > player.level().getGameTime();
    }

    /** Factor of the meditation gain: 1.10 while the tea works, else 1. */
    public static double factor(ServerPlayer player) {
        return active(player) ? FACTOR : 1.0D;
    }
}
