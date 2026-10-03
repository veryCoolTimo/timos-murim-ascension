package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.network.ShowerPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Сервер Ливня Цветов (см. {@link ShowerRules}). Состояние — вложение {@code SHOWER}:
 * {старт x, y, z, зависание x, y, z, слой, id цели или −1, тик прокола или −1, оглушение 0/1,
 * выход x, y, z, направление x, y, z, прошлая точка тела x, y, z, приземлился 0/1, точка прокола x, y, z}.
 *
 * <p>Цель — захваченная (TargetLock), иначе ближайшая в конусе взгляда, в том числе в небе: она
 * замирает (TargetLock.freeze) до прохода. Урон — только по факту: тело мастера на пикировании
 * прошло сквозь цель (отрезок пути между тиками у её центра).
 */
public final class ShowerExecutor {

    private static final int SIZE = 23;

    /** Начало IMPACT: цель, точка зависания, толчок и взлёт. */
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
                        && t.hurt(player.damageSources().playerAttack(player), (float) base)) {
                    hit = true;
                }
            }
            return hit;
        }
        // Автор 03.10: в любом направлении — захваченная цель, иначе ближайшая в конусе 30° взгляда.
        LivingEntity target = io.github.verycooltimo.murim.combat.TargetLock.locked(player, ShowerRules.LOCK_RANGE);
        if (target == null) {
            double best = Double.MAX_VALUE;
            double cone = Math.cos(Math.toRadians(30.0D));
            for (LivingEntity t : candidates(player, player.getBoundingBox().inflate(ShowerRules.RANGE + 0.5D))) {
                Vec3 to = io.github.verycooltimo.murim.combat.TargetLock.centre(t).subtract(player.getEyePosition());
                double d = to.length();
                if (t instanceof net.minecraft.world.entity.decoration.ArmorStand || d < 1.0D || d > ShowerRules.RANGE
                        || to.normalize().dot(look) < cone || !player.hasLineOfSight(t)) {
                    continue;
                }
                if (d < best) {
                    best = d;
                    target = t;
                }
            }
        }
        Vec3 apex;
        if (target != null) {
            apex = ShowerRules.apex(o, target.position(), layer);
            // Цель замирает до конца прохода — в небе висит, а не падает из-под укола.
            io.github.verycooltimo.murim.combat.TargetLock.freeze(target, ShowerRules.DIVE + ShowerRules.HIT_WINDOW + 4);
        } else {
            // Без цели — прыжок вперёд и пикирование в землю в 9 блоках.
            apex = o.add(f.scale(3.5D)).add(0.0D, ShowerRules.height(layer), 0.0D);
        }
        apex = clip(player, o, apex);
        Vec3 path = apex.subtract(o);
        if (path.lengthSqr() > 1.0E-4D) {
            io.github.verycooltimo.murim.combat.FootworkService.sendDash(player, path.normalize(), path.length(), ShowerRules.RISE_TICKS);
        }
        double[] r = new double[SIZE];
        r[0] = o.x;
        r[1] = o.y;
        r[2] = o.z;
        r[3] = apex.x;
        r[4] = apex.y;
        r[5] = apex.z;
        r[6] = layer;
        r[7] = target == null ? -1 : target.getId();
        r[8] = -1;
        r[13] = f.x;
        r[15] = f.z;
        Vec3 body = o.add(0.0D, 0.9D, 0.0D);
        r[16] = body.x;
        r[17] = body.y;
        r[18] = body.z;
        player.setData(ModAttachments.SHOWER, r);
        // Иммунитет на толчке и проходе: мастер — снаряд.
        player.invulnerableTime = Math.max(player.invulnerableTime, ShowerRules.DIVE + ShowerRules.DIVE_TICKS + 4);
        player.fallDistance = 0.0F;
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new ShowerPayload(player.getId(), o, apex, layer, ShowerPayload.RELEASE, target == null ? -1 : target.getId()));
        return false;
    }

    /** Пикирование, проход сквозь цель, ливень разрезов, приземление. */
    public static void tick(ServerPlayer player, ResourceLocation id, int since) {
        double[] r = player.getData(ModAttachments.SHOWER);
        if (r.length < SIZE) {
            return;
        }
        int layer = (int) r[6];
        if (layer <= 0) {
            return;
        }
        // Падение с высоты пикирования — часть приёма, а не урон себе.
        if (since <= ShowerRules.END) {
            player.fallDistance = 0.0F;
        }
        LivingEntity target = r[7] >= 0 && player.level().getEntity((int) r[7]) instanceof LivingEntity le && le.isAlive() ? le : null;
        double base = TechniqueDamage.base(player, id) * ShowerRules.power(layer);
        Vec3 body = player.position().add(0.0D, 0.9D, 0.0D);
        Vec3 prev = new Vec3(r[16], r[17], r[18]);
        r[16] = body.x;
        r[17] = body.y;
        r[18] = body.z;

        if (since == ShowerRules.DIVE) {
            Vec3 from = player.position();
            Vec3 exit;
            if (target != null) {
                exit = ShowerRules.exit(from, io.github.verycooltimo.murim.combat.TargetLock.centre(target), target.getBbWidth());
            } else {
                Vec3 f = new Vec3(r[13], 0.0D, r[15]);
                exit = new Vec3(r[0], r[1], r[2]).add(f.scale(9.0D));
            }
            exit = clip(player, from, exit);
            Vec3 path = exit.subtract(from);
            if (path.lengthSqr() > 1.0E-4D) {
                Vec3 dir = path.normalize();
                io.github.verycooltimo.murim.combat.FootworkService.sendDash(player, dir, path.length(), ShowerRules.DIVE_TICKS);
                r[13] = dir.x;
                r[14] = dir.y;
                r[15] = dir.z;
            }
            r[10] = exit.x;
            r[11] = exit.y;
            r[12] = exit.z;
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new ShowerPayload(player.getId(), from, exit, layer, ShowerPayload.DIVE, (int) r[7]));
        }

        // Скольжение за цель: пикирование упирается в землю у её ступней, мастер проезжает дальше.
        if (since == ShowerRules.SLIDE) {
            Vec3 flat = new Vec3(r[13], 0.0D, r[15]);
            if (flat.lengthSqr() > 1.0E-4D) {
                flat = flat.normalize();
                Vec3 from = player.position();
                Vec3 to = clip(player, from, from.add(flat.scale(ShowerRules.SLIDE_REACH)));
                double reach = to.subtract(from).multiply(1.0D, 0.0D, 1.0D).length();
                io.github.verycooltimo.murim.combat.FootworkService.sendDash(player, flat, reach, ShowerRules.SLIDE_TICKS);
            }
        }

        // Проход сквозь цель: отрезок пути тела за тик прошёл у её центра.
        if (target != null && r[8] < 0.0D && since > ShowerRules.DIVE && since <= ShowerRules.DIVE + ShowerRules.HIT_WINDOW) {
            Vec3 c = io.github.verycooltimo.murim.combat.TargetLock.centre(target);
            double reach = ShowerRules.PIERCE_RADIUS + target.getBbWidth() * 0.5D + target.getBbHeight() * 0.15D;
            if (ShowerRules.segmentDistance(c, prev, body) <= reach) {
                r[8] = since;
                r[20] = c.x;
                r[21] = c.y;
                r[22] = c.z;
                Vec3 dir = new Vec3(r[13], r[14], r[15]);
                if (hurt(player, id, target, base * ShowerRules.DMG_PIERCE, r)) {
                    io.github.verycooltimo.murim.combat.TargetLock.freeze(target, ShowerRules.CUTS[ShowerRules.CUTS.length - 1] + 2);
                    PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                            new ShowerPayload(player.getId(), c, c.add(dir), layer, ShowerPayload.HIT, target.getId()));
                } else {
                    r[8] = -2;
                }
            }
        }

        // Ливень разрезов по цели за спиной мастера.
        if (target != null && r[8] >= 0.0D) {
            int t = since - (int) r[8];
            Vec3 c = new Vec3(r[20], r[21], r[22]);
            for (int k = 0; k < ShowerRules.CUTS.length; k++) {
                if (t == ShowerRules.CUTS[k] && io.github.verycooltimo.murim.combat.TargetLock.centre(target).distanceTo(c) < 3.0D
                        && hurt(player, id, target, base * ShowerRules.DMG_CUT, r)) {
                    PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                            new ShowerPayload(player.getId(), io.github.verycooltimo.murim.combat.TargetLock.centre(target),
                                    c.add(r[13], r[14], r[15]), layer, ShowerPayload.CUT, k));
                    if (k == ShowerRules.CUTS.length - 1) {
                        // Последний разрез сбивает вдоль прохода.
                        target.push(r[13] * 0.5D, Math.max(-0.2D, r[14] * 0.3D), r[15] * 0.5D);
                        target.hurtMarked = true;
                    }
                }
            }
        }

        // Приземление: белая вспышка внизу. Удар о землю — только после прокола.
        if (r[19] < 0.5D && since >= ShowerRules.DIVE + 2 && since <= ShowerRules.END && player.onGround()) {
            r[19] = 1.0D;
            boolean pierced = r[8] >= 0.0D;
            Vec3 feet = player.position();
            if (pierced) {
                for (LivingEntity t : candidates(player, player.getBoundingBox().inflate(ShowerRules.LAND_RADIUS))) {
                    if (t instanceof net.minecraft.world.entity.decoration.ArmorStand || t.distanceTo(player) > ShowerRules.LAND_RADIUS) {
                        continue;
                    }
                    if (hurt(player, id, t, base * ShowerRules.DMG_LAND, r)) {
                        Vec3 out = new Vec3(t.getX() - feet.x, 0.0D, t.getZ() - feet.z);
                        out = out.lengthSqr() < 1.0E-4D ? new Vec3(r[13], 0.0D, r[15]) : out.normalize();
                        t.push(out.x * 0.35D, 0.15D, out.z * 0.35D);
                        t.hurtMarked = true;
                    }
                }
            }
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new ShowerPayload(player.getId(), feet, feet.add(r[13], 0.0D, r[15]), layer, ShowerPayload.LAND, pierced ? 1 : 0));
        }
        player.setData(ModAttachments.SHOWER, r);
    }

    /** Урон техники; первое попадание оглушает: моб до конца техники, игрок 0,6 с, босс 0,5 с. */
    private static boolean hurt(ServerPlayer player, ResourceLocation id, LivingEntity t, double amount, double[] r) {
        t.invulnerableTime = 0;
        if (!t.hurt(player.damageSources().playerAttack(player), (float) amount)) {
            return false;
        }
        if (r[9] < 0.5D) {
            r[9] = 1.0D;
            boolean boss = t.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES);
            int ticks = t instanceof net.minecraft.world.entity.player.Player ? 12 : boss ? 10 : ShowerRules.END - ShowerRules.DIVE;
            t.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, ticks, 9, false, false, false));
            t.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, ticks, 9, false, false, false));
        }
        io.github.verycooltimo.murim.mastery.MasteryService.onHit(player, id, t);
        return true;
    }

    /** Путь обрезается первым блоком на высоте пояса (потолок, стена, земля перед целью). */
    private static Vec3 clip(ServerPlayer player, Vec3 from, Vec3 to) {
        Vec3 a = from.add(0.0D, 0.9D, 0.0D);
        Vec3 b = to.add(0.0D, 0.9D, 0.0D);
        // API: reference/minecraft-src/net/minecraft/world/level/BlockGetter.java#clip(ClipContext)
        net.minecraft.world.phys.BlockHitResult wall = player.level().clip(new net.minecraft.world.level.ClipContext(
                a, b, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (wall.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            return to;
        }
        Vec3 d = b.subtract(a);
        double reach = Math.max(0.0D, wall.getLocation().distanceTo(a) - 0.5D);
        return from.add(d.normalize().scale(reach));
    }

    private static java.util.List<LivingEntity> candidates(ServerPlayer player, AABB box) {
        return player.serverLevel().getEntitiesOfClass(LivingEntity.class, box,
                c -> c != player && c.isAlive() && !c.isSpectator());
    }

    private ShowerExecutor() {
    }
}
