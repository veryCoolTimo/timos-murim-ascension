package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Захват цели (автор 03.10: «кнопку, чтобы как в Devil May Cry зафиксироваться на противнике»,
 * все приёмы целятся в захваченного) и заморозка цели в воздухе, чтобы приём прошёл по врагу в небе.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class TargetLock {

    /** Дальше этого захват не держится (сервер проверяет и сам снимает). */
    public static final double RANGE = 32.0D;

    /** Захваченная живая цель в пределах {@code range} или null. */
    public static LivingEntity locked(ServerPlayer player, double range) {
        int id = player.getData(ModAttachments.LOCK)[0];
        if (id < 0 || !(player.level().getEntity(id) instanceof LivingEntity t) || !t.isAlive() || t == player
                || t.distanceTo(player) > Math.min(range, RANGE)) {
            return null;
        }
        return t;
    }

    public static void set(ServerPlayer player, int entityId) {
        player.setData(ModAttachments.LOCK, new int[] {entityId});
    }

    /** Центр корпуса цели — туда целятся приёмы в 3D. */
    public static Vec3 centre(LivingEntity t) {
        return t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D);
    }

    /**
     * Заморозить цель на {@code ticks}: в воздухе она висит (без гравитации и скорости), чтобы
     * приём по ней прошёл; на земле просто не сдвигается. Продлевается, не сокращается.
     */
    public static void freeze(LivingEntity t, int ticks) {
        long now = t.level().getGameTime();
        long[] f = t.getData(ModAttachments.FROZEN);
        long until = Math.max(f[0], now + ticks);
        long ours = f[1];
        if (!t.isNoGravity() && !t.onGround()) {
            t.setNoGravity(true);
            ours = 1L;
        }
        t.setData(ModAttachments.FROZEN, new long[] {until, ours});
    }

    /**
     * Оглушение техникой (автор 03.10: «противники не станятся»): замедление уровня ≥ 4, которым
     * техники и оглушают, у мобов выключает ИИ и гасит движение — не ходят и не бьют. Снимается
     * вместе с эффектом.
     */
    private static void stunTick(LivingEntity t) {
        if (!(t instanceof net.minecraft.world.entity.Mob mob)) {
            return;
        }
        net.minecraft.world.effect.MobEffectInstance slow = t.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN);
        boolean stunned = slow != null && slow.getAmplifier() >= 3;
        int[] ours = t.hasData(ModAttachments.STUN_AI) ? t.getData(ModAttachments.STUN_AI) : null;
        if (stunned) {
            if (!mob.isNoAi()) {
                mob.setNoAi(true);
                t.setData(ModAttachments.STUN_AI, new int[] {1});
            }
            Vec3 v = t.getDeltaMovement();
            t.setDeltaMovement(0.0D, Math.min(0.0D, v.y), 0.0D);
        } else if (ours != null && ours[0] == 1) {
            mob.setNoAi(false);
            t.setData(ModAttachments.STUN_AI, new int[] {0});
        }
    }

    @SubscribeEvent
    static void onTick(EntityTickEvent.Pre event) {
        if (event.getEntity() instanceof LivingEntity s && !s.level().isClientSide()) {
            stunTick(s);
        }
        if (!(event.getEntity() instanceof LivingEntity t) || t.level().isClientSide() || !t.hasData(ModAttachments.FROZEN)) {
            return;
        }
        long[] f = t.getData(ModAttachments.FROZEN);
        if (f[0] == 0L) {
            return;
        }
        if (t.level().getGameTime() < f[0] && t.isAlive()) {
            t.setDeltaMovement(Vec3.ZERO);
            t.hurtMarked = true;
            return;
        }
        if (f[1] == 1L) {
            t.setNoGravity(false);
        }
        t.setData(ModAttachments.FROZEN, new long[] {0L, 0L});
    }

    private TargetLock() {
    }
}
