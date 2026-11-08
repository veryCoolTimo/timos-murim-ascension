package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.GateDoorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * The big sect gate and the people who look after it (author 22.10: «я открыл ворота секты, никто их не закрывает»; a broken gate
 * made no sound at all). A gate a player opens is entered in {@link SectDoors}, so it is shut again once he has walked through
 * and nobody stands in the passage (the sweep never closes it on a person); at night the sweep keeps the gate shut. A gate that
 * is broken raises the alarm: the guards run to it and the elder speaks to the culprit (a survival player; the third strike of one
 * sect day is the warrant).
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectGate {
    private SectGate() { }

    /** Is the gate whose part sits at {@code pos} THE gate of the sect (the one beside the layout's gate zone)? */
    static boolean isSectGate(SectLayout layout, BlockPos pos) {
        if (layout == null) return false;
        Vec3 gate = layout.at("sect_gate", 0, 0);
        return gate != null && Math.hypot(pos.getX() + 0.5D - gate.x, pos.getZ() + 0.5D - gate.z) < 11.0D && Math.abs(pos.getY() - gate.y) < 10.0D;
    }

    /** A player has set the gate at {@code origin} swinging open: the sect shuts it behind him. True if it was the sect gate. */
    public static boolean opened(ServerLevel level, BlockPos origin) {
        return opened(level, origin, SectLife.layout(level));
    }

    static boolean opened(ServerLevel level, BlockPos origin, SectLayout layout) {
        if (!isSectGate(layout, origin)) return false;
        SectDoors.mark(level, origin, level.getGameTime());
        return true;
    }

    /** The gate is broken: everybody within earshot is called to it. Returns how many took the alarm up. */
    static int broken(ServerLevel level, BlockPos pos, SectLayout layout, ServerPlayer culprit) {
        if (!isSectGate(layout, pos)) return 0;
        if (culprit != null) SectNotice.chat(culprit, Component.translatable("murim.sect.gate.broken"));
        return SectAlarm.raise(level, Vec3.atCenterOf(pos), SectAlarm.Kind.GATE, culprit);
    }

    @SubscribeEvent
    static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getState().getBlock() instanceof GateDoorBlock)) return;
        broken(level, event.getPos(), SectLife.layout(level), event.getPlayer() instanceof ServerPlayer p ? p : null);
    }
}
