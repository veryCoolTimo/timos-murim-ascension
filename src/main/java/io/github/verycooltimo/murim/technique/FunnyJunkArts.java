package io.github.verycooltimo.murim.technique;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** Server effects on the actual flap/stomp beats; no persistent timer survives a cancelled cast. */
public final class FunnyJunkArts {
    public static final double FLAP_RADIUS = 2.0D;
    public static final double FLAP_STRENGTH = 0.18D;

    public static void tick(ServerPlayer player, TechniqueDefinition definition, int clipTick) {
        if (JunkArts.THOUSAND_CRANES.equals(definition.id()) && FunnyJunkTiming.flap(clipTick)) {
            flap(player);
        } else if (JunkArts.BEGGARS_TAP.equals(definition.id()) && FunnyJunkTiming.stomp(clipTick)
                && player.onGround()) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.STONE_STEP, SoundSource.PLAYERS, 0.25F, FunnyJunkTiming.rightFoot(clipTick) ? 1.3F : 1.1F);
        }
    }

    /** Harmless radial knockback of nearby mobs only, respecting walls and knockback resistance. */
    public static void flap(ServerPlayer player) {
        int processed = 0;
        for (Mob mob : player.level().getEntitiesOfClass(Mob.class,
                player.getBoundingBox().inflate(FLAP_RADIUS),
                mob -> mob.isAlive() && !mob.isSpectator() && !mob.isAlliedTo(player))) {
            if (++processed > 24) {
                break;
            }
            if (mob.distanceToSqr(player) > FLAP_RADIUS * FLAP_RADIUS || !player.hasLineOfSight(mob)) {
                continue;
            }
            Vec3 away = mob.position().subtract(player.position());
            if (away.horizontalDistanceSqr() < 1.0E-6D) {
                away = new Vec3(1.0D, 0.0D, 0.0D);
            }
            mob.knockback(FLAP_STRENGTH, -away.x, -away.z);
        }
    }

    private FunnyJunkArts() {
    }
}
