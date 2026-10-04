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

    /** Урон, который можно нанести стороне {@code health} с порогом {@code floor}. */
    public static float clamp(float damage, float health, float floor) {
        return Math.max(0.0F, Math.min(damage, health - floor));
    }

    @SubscribeEvent
    static void onDamage(LivingDamageEvent.Pre event) {
        SectDisciple d = duel(event.getEntity(), event.getSource().getEntity());
        if (d != null) {
            event.setNewDamage(clamp(event.getNewDamage(), event.getEntity().getHealth(), d.floorFor(event.getEntity())));
        }
    }

    @SubscribeEvent
    static void onDamaged(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        SectDisciple d = duel(victim, event.getSource().getEntity());
        // Урок наставника: удар партнёра в окно после выпуска удара ученика — «чистый».
        if (d != null && victim == d && event.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer p
                && event.getNewDamage() > 0.0F && d.onPartnerHit()) {
            io.github.verycooltimo.murim.sect.SectService.onCleanHit(p, d);
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
