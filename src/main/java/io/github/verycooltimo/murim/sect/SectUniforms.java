package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModUniforms;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The sect issues the attained rank set (third-generation clothes for rank-zero newcomers), without changing social generation.
 * Three fixed flags in the existing serialized, copy-on-death sect journal; server only, no new schema or client sync.
 * Inventory/equipment synchronization remains vanilla. Catch-up issues only the current set, never all lower sets.
 */
public final class SectUniforms {
    public static boolean issue(ServerPlayer player) {
        SectState state = SectService.state(player);
        if (!state.member()) return false;
        int rank = player.getData(ModAttachments.PROFILE).rank();
        var uniform = ModUniforms.ALL.get(Math.max(1, Math.min(rank, 3)) - 1);
        String flag = "uniform.issued." + uniform.generation();
        if (state.has(flag)) return false;
        player.setData(ModAttachments.SECT, state.with(flag));
        for (var piece : uniform.pieces()) {
            ItemStack stack = new ItemStack(piece.get());
            if (!player.getInventory().add(stack)) player.drop(stack, false);
        }
        Component notice = Component.translatable("murim.sect.uniform.issued",
                Component.translatable("murim.uniform." + uniform.generation()));
        SectNotice.chat(player, notice);
        return true;
    }

    private SectUniforms() { }
}
