package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/**
 * Спарринг без смерти: урон между учеником и его партнёром не опускает здоровье ниже порога
 * (потеря половины), а дошедший до порога проигрывает — ученик кланяется.
 * API: reference/neoforge-src/net/neoforged/neoforge/event/entity/living/LivingDamageEvent.java (Pre#setNewDamage, Post)
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SparEvents {

    /** Share of a player's blow that counts against the partner in a spar. */
    public static final float PLAYER_BLOW_SHARE = 0.5F;
    private static final java.util.List<String> HALF = java.util.List.of("murim.bubble.spar.half_a", "murim.bubble.spar.half_b", "murim.bubble.spar.half_c");
    private static final java.util.List<String> LAST = java.util.List.of("murim.bubble.spar.last_a", "murim.bubble.spar.last_b", "murim.bubble.spar.last_c");

    /** How far the bout has gone for the partner of {@code d}: 0 at the start, 1 at the line. */
    static float progress(SectDisciple d) {
        float floor = d.floorFor(d);
        float span = d.getMaxHealth() - floor;
        return span <= 0.0F ? 1.0F : Math.min(1.0F, (d.getMaxHealth() - d.getHealth()) / span);
    }

    /** Score through sound and the partner's voice, no bars: a bell on each landed blow, a word at half and near the end. */
    static void score(net.minecraft.server.level.ServerPlayer p, SectDisciple d, float before, boolean clean) {
        float now = progress(d);
        p.level().playSound(null, d.getX(), d.getY() + 1.0D, d.getZ(), net.minecraft.sounds.SoundEvents.NOTE_BLOCK_CHIME.value(),
                net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, clean ? 1.6F : 0.9F + 0.6F * now);
        java.util.List<String> pool = before < 0.5F && now >= 0.5F ? HALF : before < 0.8F && now >= 0.8F ? LAST : null;
        if (pool != null) {
            String key = io.github.verycooltimo.murim.sect.SectLines.pick(p, pool.get(0).replace("_a", ""), pool, p.getRandom().nextInt(pool.size()));
            io.github.verycooltimo.murim.sect.SectChatter.send(d, key, net.minecraft.network.chat.Component.translatable(key));
        }
    }

    /** Урон, который можно нанести стороне {@code health} с порогом {@code floor}. */
    public static float clamp(float damage, float health, float floor) {
        return Math.max(0.0F, Math.min(damage, health - floor));
    }

    @SubscribeEvent
    static void onDamage(LivingDamageEvent.Pre event) {
        SectDisciple d = duel(event.getEntity(), event.getSource().getEntity());
        if (d != null) {
            float dmg = event.getNewDamage();
            // The player's blows on his sparring partner count less (author 22.10: «after 3-4 hits you win»): a bout is longer.
            if (event.getEntity() == d && event.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer) {
                dmg *= PLAYER_BLOW_SHARE;
            }
            event.setNewDamage(clamp(dmg, event.getEntity().getHealth(), d.floorFor(event.getEntity())));
        }
    }

    @SubscribeEvent
    static void onDamaged(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        SectDisciple d = duel(victim, event.getSource().getEntity());
        if (d != null && victim == d && event.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer sp
                && event.getNewDamage() > 0.0F) {
            float after = progress(d);
            // The blow already landed; the step it made is what crossed the thresholds.
            float step = event.getNewDamage() / Math.max(1.0F, d.getMaxHealth() - d.floorFor(d));
            boolean clean = d.onPartnerHit();
            score(sp, d, Math.max(0.0F, after - step), clean);
            if (clean) {
                io.github.verycooltimo.murim.sect.SectService.onCleanHit(sp, d);
            }
        }
        if (d != null && victim.getHealth() <= d.floorFor(victim) + 0.01F) {
            d.endSpar(victim == d);
        }
    }

    /** Ученик, если удар — внутри его спарринга (ученик ↔ партнёр). */
    private static SectDisciple duel(LivingEntity victim, net.minecraft.world.entity.Entity attacker) {
        if (victim.level().isClientSide()) {
            return null;
        }
        if (victim instanceof SectDisciple d && d.spar() == SectDisciple.Spar.FIGHT && attacker != null
                && attacker.getUUID().equals(d.partner())) {
            return d;
        }
        if (attacker instanceof SectDisciple d && d.spar() == SectDisciple.Spar.FIGHT && victim.getUUID().equals(d.partner())) {
            return d;
        }
        return null;
    }

    private SparEvents() {
    }
}
