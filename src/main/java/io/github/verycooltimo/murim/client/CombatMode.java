package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.LockOn;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Combat mode (author 01.10, tightened 18.10): the technique slots show in a FIGHT, not because a sword is in the hand.
 * A fight is, for {@link CombatClock#HOLD_TICKS} after the last sign:
 * <ul>
 *   <li>the player hit a living entity, or a living entity hit the player (seen from the damage events the client
 *       already gets: {@code hurtTime} plus {@code getLastDamageSource});</li>
 *   <li>there is a lock-on target;</li>
 *   <li>a technique was used ({@link #engage()} from the key handlers);</li>
 *   <li>the server says so with a {@code CombatPulsePayload}: a sect bout or duel, or a mob that targets the
 *       player within 16 blocks (the only parts the client cannot see).</li>
 * </ul>
 * A swing at air, the attack key, and opening the wheel are NOT signs of a fight.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class CombatMode {

    /** The server pulse comes every second; this keeps it alive across one lost packet. */
    private static final int PULSE_TICKS = 45;

    private static final CombatClock CLOCK = new CombatClock();
    private static int pulse;

    /** A technique was used, or a hit was confirmed: a sign of a fight. */
    public static void engage() {
        CLOCK.engage();
    }

    /** The server pulse: a bout, a duel, or a mob hunting the player. */
    public static void pulse() {
        pulse = PULSE_TICKS;
    }

    /** How much of the combat HUD shows, 0..1. */
    public static float presence(float partial) {
        return CLOCK.presence(partial);
    }

    public static boolean active() {
        return CLOCK.active();
    }

    /** True if {@code source} was dealt by a living entity other than {@code victim} itself. */
    static boolean byLiving(DamageSource source, Entity victim) {
        return source != null && source.getEntity() instanceof LivingEntity attacker && attacker != victim;
    }

    /** What the client sees right now: a hit taken or dealt, a lock-on, or a server pulse. */
    private static boolean fighting(Minecraft minecraft, LocalPlayer player) {
        if (pulse > 0) {
            pulse--;
            return true;
        }
        if (LockOn.target() >= 0 || (player.hurtTime > 0 && byLiving(player.getLastDamageSource(), player))) {
            return true;
        }
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity victim && victim != player && victim.hurtTime > 0
                    && victim.getLastDamageSource() != null && victim.getLastDamageSource().getEntity() == player) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            reset();
            return;
        }
        CLOCK.tick(fighting(minecraft, player));
    }

    public static void reset() {
        CLOCK.reset();
        pulse = 0;
    }

    private CombatMode() {
    }
}
