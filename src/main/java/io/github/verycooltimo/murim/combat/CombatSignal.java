package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.ChannelSafe;
import io.github.verycooltimo.murim.network.CombatPulsePayload;
import io.github.verycooltimo.murim.sect.SectBouts;
import io.github.verycooltimo.murim.sect.SectDuel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * The part of "in combat" only the server knows: a sect bout or duel, or a mob that has the player as its target
 * within {@link #AGGRO_RANGE} blocks (a mob's target is not synced to the client). While it holds, the player gets
 * an empty {@link CombatPulsePayload} every {@link #PERIOD} ticks. The client adds what it sees itself (hits dealt and
 * taken, lock-on, a technique used) and shows the technique slots only then (author 18.10).
 *
 * <p>Stateless on purpose: nothing is stored, so a reconnect or a missed packet cannot leave the HUD stuck.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class CombatSignal {
    public static final int PERIOD = 20;
    public static final double AGGRO_RANGE = 16.0D;

    /** A sect bout or duel is on, or something hunts this player. */
    public static boolean engaged(ServerPlayer player) {
        return SectBouts.inBout(player) || SectDuel.inDuel(player) || hunted(player);
    }

    /** A living mob within {@link #AGGRO_RANGE} blocks has this player as its attack target. */
    public static boolean hunted(ServerPlayer player) {
        double reach = AGGRO_RANGE * AGGRO_RANGE;
        return !player.serverLevel().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(AGGRO_RANGE),
                mob -> mob.isAlive() && mob.getTarget() == player && mob.distanceToSqr(player) <= reach).isEmpty();
    }

    /** Sends the pulse when {@link #engaged}; returns whether it did. */
    public static boolean pulse(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator() || !engaged(player)) {
            return false;
        }
        ChannelSafe.send(player, new CombatPulsePayload());
        return true;
    }

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % PERIOD == 0) {
            pulse(player);
        }
    }

    private CombatSignal() {
    }
}
