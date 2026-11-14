package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.boss.FortressMaster;
import io.github.verycooltimo.murim.network.ChannelSafe;
import io.github.verycooltimo.murim.network.HitImpactPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/**
 * The one own hit impact of combat outside techniques (art review 11.11, item 6): bandit hits, boss hits and a
 * successful block. The server only says where and how hard; the client draws it ({@code HitImpactVfx}).
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class HitImpacts {

    private HitImpacts() {
    }

    @SubscribeEvent
    static void onDamaged(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || event.getNewDamage() <= 0.0F) {
            return;
        }
        Entity attacker = event.getSource().getDirectEntity();
        if (SwordGuard.blocking(victim)) {
            return; // the guard already drew its block impact for this contact
        }
        if (attacker instanceof FortressMaster) {
            send(victim, attacker, HitImpactPayload.HIT, 1.7F);
        } else if (attacker instanceof Bandit) {
            send(victim, attacker, HitImpactPayload.HIT, 1.0F);
        }
    }

    /** A blocked or parried contact: the sparks fly at the defender's guard. */
    public static void block(LivingEntity defender, Entity incoming, boolean exact) {
        send(defender, incoming, HitImpactPayload.BLOCK, exact ? 1.3F : 1.0F);
    }

    private static void send(LivingEntity victim, Entity attacker, int kind, float power) {
        if (!(victim.level() instanceof ServerLevel)) {
            return;
        }
        double dx = victim.getX() - attacker.getX(), dz = victim.getZ() - attacker.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3D) {
            dx = 0.0D;
            dz = 1.0D;
            len = 1.0D;
        }
        dx /= len;
        dz /= len;
        // Contact: chest of the victim, shifted toward the attacker by the body radius; a block — at the raised guard.
        double toward = victim.getBbWidth() * 0.5D;
        double h = kind == HitImpactPayload.BLOCK ? 0.65D : 0.62D;
        HitImpactPayload payload = new HitImpactPayload((float) (victim.getX() - dx * toward), (float) (victim.getY() + victim.getBbHeight() * h),
                (float) (victim.getZ() - dz * toward), (float) dx, (float) dz, kind, power);
        if (Boolean.getBoolean("murim.capture")) {
            MurimMod.LOGGER.info("HITIMPACT kind={} power={} at {} {} {}", kind, power, payload.x(), payload.y(), payload.z());
        }
        ChannelSafe.nearEntity(victim, payload, true);
    }
}
