package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.network.RainPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Сервер Ливня Цветущей Сливы (см. {@link RainRules}). Состояние — вложение {@code RAIN}:
 * {старт x, y, z, направление x, z, слой, id цели или −1, метка 0/1, первое попадание 0/1,
 * точка остановки x, y, z}.
 *
 * <p>Замах урона не наносит: он только метит цель (оцепенение — мобы до конца техники, игрок
 * 0,6 с, босс 0,5 с). Урон приносят три волны ливня, каждая — если цель жива, рядом и над ней
 * открыто небо (под крышей лепестки не достают — это слабость техники).
 */
public final class RainExecutor {

    /** Начало IMPACT: цель по взгляду до 16 блоков, рывок сбоку неё за спину. */
    public static boolean start(ServerPlayer player, ResourceLocation id) {
        int layer = Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.layer(player, id));
        double base = TechniqueDamage.base(player, id);
        Vec3 o = player.position();
        Vec3 look = player.getLookAngle();
        Vec3 f = new Vec3(look.x, 0.0D, look.z);
        f = f.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : f.normalize();
        if (layer <= 0) {
            // Слой 0 — учебный укол в ближнем секторе, без эффектов.
            boolean hit = false;
            double cos = Math.cos(Math.toRadians(PlumRules.TRAINING_ARC / 2.0D));
            for (LivingEntity t : candidates(player, player.getBoundingBox().inflate(PlumRules.TRAINING_REACH + 1.0D))) {
                if (BehaviorExecutor.inArc(player.getEyePosition(), look, t.getBoundingBox(), PlumRules.TRAINING_REACH, cos)
                        && t.hurt(player.damageSources().playerAttack(player), (float) (base * RainRules.DMG_TRAINING))) {
                    hit = true;
                }
            }
            return hit;
        }
        LivingEntity target = io.github.verycooltimo.murim.combat.TargetLock.locked(player, RainRules.RANGE + 2.0D);
        double best = Double.MAX_VALUE;
        double cone = Math.cos(Math.toRadians(35.0D));
        for (LivingEntity t : target != null ? java.util.List.<LivingEntity>of() : candidates(player, player.getBoundingBox().inflate(RainRules.RANGE + 0.5D))) {
            Vec3 to = new Vec3(t.getX() - o.x, 0.0D, t.getZ() - o.z);
            double d = to.length();
            // Стойки для брони — не противники.
            if (t instanceof net.minecraft.world.entity.decoration.ArmorStand || d < 0.5D || d > RainRules.RANGE
                    || to.normalize().dot(f) < cone || !player.hasLineOfSight(t)) {
                continue;
            }
            if (d < best) {
                best = d;
                target = t;
            }
        }
        Vec3 dest;
        Vec3 dir;
        if (target != null) {
            dest = RainRules.behind(o, target.position(), target.getBbWidth());
            dir = new Vec3(target.getX() - o.x, 0.0D, target.getZ() - o.z).normalize();
        } else {
            // Без цели — замах в пустоту: короткий проход вперёд, иллюзии нет.
            dir = f;
            dest = o.add(f.scale(4.0D));
        }
        dest = clipWall(player, o, dest);
        Vec3 path = new Vec3(dest.x - o.x, 0.0D, dest.z - o.z);
        if (path.lengthSqr() > 1.0E-4D) {
            io.github.verycooltimo.murim.combat.FootworkService.sendDash(player, path.normalize(), path.length(), RainRules.DASH_TICKS);
        }
        player.setData(ModAttachments.RAIN, new double[] {o.x, o.y, o.z, dir.x, dir.z, layer,
                target == null ? -1 : target.getId(), 0, 0, dest.x, dest.y, dest.z});
        // Иммунитет на проходе: мастер — размытая полоса.
        player.invulnerableTime = Math.max(player.invulnerableTime, RainRules.DASH_TICKS + 4);
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new RainPayload(player.getId(), o, dest, yaw, layer, RainPayload.RELEASE, target == null ? -1 : target.getId()));
        return false;
    }

    /** Путь рывка обрезается первой стеной на высоте пояса. */
    private static Vec3 clipWall(ServerPlayer player, Vec3 o, Vec3 dest) {
        Vec3 from = o.add(0.0D, 0.9D, 0.0D);
        Vec3 to = dest.add(0.0D, 0.9D, 0.0D);
        net.minecraft.world.phys.BlockHitResult wall = player.level().clip(new net.minecraft.world.level.ClipContext(
                from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (wall.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            return dest;
        }
        Vec3 d = to.subtract(from);
        double reach = Math.max(0.0D, wall.getLocation().distanceTo(from) - 0.5D);
        return o.add(new Vec3(d.x, 0.0D, d.z).normalize().scale(reach));
    }

    /** Шкала после замаха: метка, оцепенение, три волны ливня. */
    public static void tick(ServerPlayer player, ResourceLocation id, int since) {
        double[] r = player.getData(ModAttachments.RAIN);
        int layer = (int) r[5];
        if (layer <= 0 || r.length < 12) {
            return;
        }
        int t = since + RainRules.RELEASE;
        LivingEntity target = r[6] >= 0 && player.level().getEntity((int) r[6]) instanceof LivingEntity le && le.isAlive() ? le : null;
        // Цель ушла, умерла или слишком далеко — иллюзия рассыпается, урона нет.
        if (r[7] > 0.5D && (target == null || target.distanceTo(player) > RainRules.RANGE + 4.0D) && t < RainRules.contact(2)) {
            r[7] = 0.0D;
            r[6] = -1;
            player.setData(ModAttachments.RAIN, r);
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new RainPayload(player.getId(), player.position(), player.position(), 0.0F, layer, RainPayload.LOST, -1));
            return;
        }
        // Метка: мастер прошёл у цели — замах «провёл» по ней, но урона нет.
        if (t == RainRules.MARK && target != null && r[7] < 0.5D) {
            // Позиция мастера на сервере отстаёт от плавного рывка: проверяем, что цель осталась у точки прохода.
            if (flat(target.position(), new Vec3(r[9], r[10], r[11])) < 4.5D) {
                r[7] = 1.0D;
                player.setData(ModAttachments.RAIN, r);
                entrance(target);
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        new RainPayload(player.getId(), new Vec3(r[0], r[1], r[2]), target.position(), 0.0F, layer,
                                RainPayload.MARKED, target.getId()));
            }
        }
        if (r[7] < 0.5D || target == null) {
            return;
        }
        // Оцепенелая цель стоит: гасим её ход каждый тик до последней волны.
        boolean boss = target.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES);
        if (!(target instanceof net.minecraft.world.entity.player.Player) && !boss && t <= RainRules.contact(2)) {
            target.setDeltaMovement(0.0D, target.getDeltaMovement().y, 0.0D);
            target.hurtMarked = true;
        }
        double base = TechniqueDamage.base(player, id)
                * RainRules.power(layer);
        for (int k = 0; k < RainRules.COHORTS.length; k++) {
            if (t != RainRules.contact(k)) {
                continue;
            }
            if (!openSky(player, target)) {
                continue;
            }
            target.invulnerableTime = 0;
            if (target.hurt(player.damageSources().playerAttack(player), (float) (base * RainRules.DMG[k]))) {
                io.github.verycooltimo.murim.mastery.MasteryService.onHit(player, id, target);
                if (k == 2) {
                    // Обрушение прижимает к земле, без отброса.
                    target.setDeltaMovement(target.getDeltaMovement().multiply(0.2D, 0.0D, 0.2D).add(0.0D, -0.2D, 0.0D));
                    target.hurtMarked = true;
                }
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        new RainPayload(player.getId(), new Vec3(r[0], r[1], r[2]),
                                target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D), 0.0F, layer,
                                RainPayload.HIT + k, target.getId()));
            }
        }
    }

    /** Оцепенение иллюзии: мобы до конца ливня, игрок 0,6 с, босс 0,5 с. */
    private static void entrance(LivingEntity t) {
        boolean boss = t.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES);
        int ticks = t instanceof net.minecraft.world.entity.player.Player ? 12 : boss ? 10
                : RainRules.contact(2) - RainRules.MARK + 6;
        t.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, ticks, 9, false, false, false));
        t.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, ticks, 9, false, false, false));
        if (t instanceof net.minecraft.world.entity.Mob mob && !boss) {
            mob.getNavigation().stop();
        }
    }

    /** Лепестки падают с неба: путь от высоты ядра до головы цели свободен от блоков. */
    private static boolean openSky(ServerPlayer player, LivingEntity t) {
        Vec3 head = t.position().add(0.0D, t.getBbHeight() + 0.1D, 0.0D);
        net.minecraft.world.phys.BlockHitResult roof = player.level().clip(new net.minecraft.world.level.ClipContext(
                head.add(0.0D, RainRules.CORE_HEIGHT, 0.0D), head, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return roof.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    private static double flat(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static java.util.List<LivingEntity> candidates(ServerPlayer player, AABB box) {
        return player.serverLevel().getEntitiesOfClass(LivingEntity.class, box,
                c -> c != player && c.isAlive() && !c.isSpectator());
    }

    private RainExecutor() {
    }
}
