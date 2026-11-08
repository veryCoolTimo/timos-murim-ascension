package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.Stun;
import io.github.verycooltimo.murim.combat.SwordGuard;
import io.github.verycooltimo.murim.cultivation.QiRules;
import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * What a senior's release does to a player (author 22.10: «просто кастовать приёмы долго ... бью его просто»).
 * Unblocked: a short stun and a shove. Blocked: the block costs qi, no stun. Parried at the exact moment
 * (the sword guard cancelled the blow): the senior reels. Only in hard bouts ({@link SectDisciple#hardBout}):
 * never the lessons, never the juniors. Runs after {@link SwordGuard} (which is HIGHEST) and sees its cancel.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SeniorStrikes {

    /** Qi a block of a senior's release costs. */
    public static final double BLOCK_QI = 6.0D;

    @SubscribeEvent(priority = EventPriority.LOW, receiveCanceled = true)
    static void onPlayerHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.level().isClientSide()
                || !(event.getSource().getEntity() instanceof SectDisciple d) || !d.releasing() || !d.hardBout()
                || !p.getUUID().equals(d.partner())) {
            return;
        }
        long now = p.level().getGameTime();
        if (event.isCanceled()) {
            if (SwordGuard.state(p).contact == now) {
                d.parried();
            }
            return;
        }
        if (event.getAmount() <= 0) {
            return;
        }
        event.setAmount(event.getAmount() * d.greedFactor());
        boolean blocked = SwordGuard.blocking(p) && SwordGuard.frontal(p, d.position());
        if (blocked) {
            var profile = p.getData(ModAttachments.PROFILE);
            if (QiRules.has(p, profile, BLOCK_QI)) {
                p.setData(ModAttachments.PROFILE, QiRules.charge(p, profile, BLOCK_QI));
                ProfileNetwork.sync(p);
                return;
            }
            // Out of qi: the guard breaks.
        }
        if (Stun.isStunned(p)) {
            return;
        }
        Stun.apply(p, SectDisciple.RELEASE_STUN, SectDisciple.RELEASE_STUN);
        p.knockback(0.7D, d.getX() - p.getX(), d.getZ() - p.getZ());
        p.hurtMarked = true;
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.HOSTILE, 1.0F, 0.8F);
    }

    private SeniorStrikes() {
    }
}
