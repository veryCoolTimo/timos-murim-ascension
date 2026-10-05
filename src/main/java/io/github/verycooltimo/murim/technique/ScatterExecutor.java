package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.network.ScatterPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Сервер Рассеяния Цветущей Сливы (см. {@link ScatterRules}). Состояние — вложение {@code SCATTER}:
 * {ступни мастера x, y, z, центр цели x, y, z, base, слой, id цели или −1, сторона ±1, на земле 0/1,
 * первое попадание 0/1, начало взмаха x, y, z}.
 *
 * <p>В отличие от Казни Семи Цветков (там клоны — иллюзия, бьёт мастер), здесь КАЖДЫЙ клон бьёт сам:
 * на тике, когда удар клона проходит центр, сервер проверяет весь отрезок его хорды — каждое живое
 * существо, чей хитбокс (раздутый на полуширину клинка) задет отрезком, получает урон от этого
 * удара ровно один раз. Владелец урона — мастер. Набор задетых — у каждого удара свой.
 */
public final class ScatterExecutor {

    /** Начало IMPACT: цель (захват — первой), прыжок в сторону, клоны выходят. */
    public static boolean start(ServerPlayer player, ResourceLocation id) {
        int layer = Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.layer(player, id));
        double base = TechniqueDamage.base(player, id);
        Vec3 o = player.position();
        Vec3 look = player.getLookAngle();
        if (layer <= 0) {
            // Слой 0 — учебный удар в ближнем секторе, без эффектов.
            boolean hit = false;
            double cos = Math.cos(Math.toRadians(PlumRules.TRAINING_ARC / 2.0D));
            for (LivingEntity t : candidates(player, player.getBoundingBox().inflate(PlumRules.TRAINING_REACH + 1.0D))) {
                if (BehaviorExecutor.inArc(player.getEyePosition(), look, t.getBoundingBox(), PlumRules.TRAINING_REACH, cos)
                        && t.hurt(player.damageSources().playerAttack(player), (float) (base * ScatterRules.DMG_TRAINING))) {
                    hit = true;
                }
            }
            return hit;
        }
        // Захваченная цель первой, иначе ближайшая в конусе 35° взгляда до 16 блоков — и в небе тоже.
        LivingEntity target = io.github.verycooltimo.murim.combat.TargetLock.locked(player, ScatterRules.RANGE + 2.0D);
        if (target == null) {
            double best = Double.MAX_VALUE;
            double cone = Math.cos(Math.toRadians(35.0D));
            for (LivingEntity t : candidates(player, player.getBoundingBox().inflate(ScatterRules.RANGE + 0.5D))) {
                Vec3 to = io.github.verycooltimo.murim.combat.TargetLock.centre(t).subtract(player.getEyePosition());
                double d = to.length();
                if (t instanceof net.minecraft.world.entity.decoration.ArmorStand || d < 0.5D || d > ScatterRules.RANGE
                        || !io.github.verycooltimo.murim.combat.TargetLock.inCone(player, t, 35.0D) || !player.hasLineOfSight(t)) {
                    continue;
                }
                if (d < best) {
                    best = d;
                    target = t;
                }
            }
        }
        Vec3 f = new Vec3(look.x, 0.0D, look.z);
        f = f.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : f.normalize();
        Vec3 centre;
        boolean grounded;
        if (target != null) {
            centre = io.github.verycooltimo.murim.combat.TargetLock.centre(target);
            grounded = target.onGround();
            // Цель висит/стоит до конца техники: хорды клонов рассчитаны на её место.
            io.github.verycooltimo.murim.combat.TargetLock.freeze(target, ScatterRules.END - ScatterRules.RELEASE);
            if (target instanceof net.minecraft.world.entity.Mob mob) {
                mob.getNavigation().stop();
            }
        } else {
            // Без цели — клоны рубят пустоту в 5 блоках перед мастером.
            centre = o.add(f.scale(5.0D)).add(0.0D, 1.0D, 0.0D);
            grounded = true;
        }
        double baseAngle = Math.atan2(o.z - centre.z, o.x - centre.x);
        // Прыжок в ту сторону, где свободнее (луч на уровне пояса), по умолчанию — вправо.
        int side = 1;
        Vec3 jump = ScatterRules.jump(o, centre, side);
        Vec3 clipped = clipWall(player, o, o.add(jump));
        if (clipped.distanceTo(o) < jump.length() * 0.6D) {
            side = -1;
            jump = ScatterRules.jump(o, centre, side);
            clipped = clipWall(player, o, o.add(jump));
        }
        Vec3 path = clipped.subtract(o);
        if (path.lengthSqr() > 1.0E-4D) {
            io.github.verycooltimo.murim.combat.FootworkService.sendDash(player, path.normalize(), path.length(), ScatterRules.JUMP_TICKS);
        }
        player.invulnerableTime = Math.max(player.invulnerableTime, ScatterRules.JUMP_TICKS + 4);
        player.setData(ModAttachments.SCATTER, new double[] {o.x, o.y, o.z, centre.x, centre.y, centre.z, baseAngle, layer,
                target == null ? -1 : target.getId(), side, grounded ? 1 : 0, 0, 0, 0, 0});
        int facing = Math.floorMod(Math.round(player.getYRot()), 360);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new ScatterPayload(player.getId(), o, centre, (float) baseAngle,
                layer, ScatterPayload.RELEASE, (side > 0 ? 1 : 0) | (grounded ? 2 : 0) | facing << 2, target == null ? -1 : target.getId()));
        return false;
    }

    /** Шкала после прыжка: удары клонов, взмах мастера, рассеяние. */
    public static void tick(ServerPlayer player, ResourceLocation id, int since) {
        double[] s = player.getData(ModAttachments.SCATTER);
        int layer = (int) s[7];
        if (layer <= 0 || s.length < 15) {
            return;
        }
        int t = since + ScatterRules.RELEASE;
        Vec3 centre = new Vec3(s[3], s[4], s[5]);
        double base = s[6];
        boolean grounded = s[10] > 0.5D;
        double dmg = TechniqueDamage.base(player, id)
                * ScatterRules.power(layer);
        // Удары клонов: каждый — по своему отрезку, свой набор задетых.
        for (int i = 0; i < ScatterRules.clones(layer); i++) {
            for (int j = 0; j < ScatterRules.strokes(layer); j++) {
                if (t != ScatterRules.strokeHit(layer, i, j)) {
                    continue;
                }
                Vec3[] chord = ScatterRules.stroke(centre, base, i, j, grounded);
                Vec3 at = null;
                for (LivingEntity e : swept(player, chord[0], chord[1], ScatterRules.BLADE)) {
                    if (hurt(player, id, e, dmg * ScatterRules.DMG_STROKE, s) && at == null) {
                        at = closest(chord[0], chord[1], io.github.verycooltimo.murim.combat.TargetLock.centre(e));
                    }
                }
                io.github.verycooltimo.murim.MurimMod.LOGGER.debug("Рассеяние: тик {}, клон {}, удар {} — {}", t, i, j, at != null ? "попал" : "мимо");
                if (at != null) {
                    PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new ScatterPayload(player.getId(), at, centre,
                            (float) base, layer, ScatterPayload.HIT, i * 8 + j, (int) s[8]));
                }
            }
        }
        // Взмах мастера (full-18): рывок сквозь цель на её высоту, за спину на 2,5 блока.
        if (ScatterRules.finalSwing(layer) && t == ScatterRules.FINAL) {
            Vec3 from = player.position();
            Vec3 aim = centre.subtract(0.0D, 0.9D, 0.0D);
            Vec3 dir = new Vec3(aim.x - from.x, 0.0D, aim.z - from.z);
            dir = dir.lengthSqr() < 1.0E-4D ? new Vec3(Math.cos(base + Math.PI), 0.0D, Math.sin(base + Math.PI)) : dir.normalize();
            Vec3 dest = new Vec3(aim.x, grounded ? from.y : aim.y, aim.z).add(dir.scale(2.5D));
            dest = clipWall(player, from, dest);
            Vec3 path = dest.subtract(from);
            if (path.lengthSqr() > 1.0E-4D) {
                io.github.verycooltimo.murim.combat.FootworkService.sendDash(player, path.normalize(), path.length(), ScatterRules.FINAL_TICKS);
            }
            player.invulnerableTime = Math.max(player.invulnerableTime, ScatterRules.FINAL_TICKS + 4);
            s[12] = from.x;
            s[13] = from.y;
            s[14] = from.z;
            player.setData(ModAttachments.SCATTER, s);
        }
        if (ScatterRules.finalSwing(layer) && t == ScatterRules.FINAL + ScatterRules.FINAL_TICKS - 1) {
            // Проверка по линии от старта взмаха к центру цели и дальше — путь клинка мастера.
            Vec3 from = new Vec3(s[12], s[13] + 1.0D, s[14]);
            Vec3 to = centre.add(centre.subtract(from).normalize().scale(2.0D));
            Vec3 at = null;
            for (LivingEntity e : swept(player, from, to, 1.3D)) {
                if (hurt(player, id, e, dmg * ScatterRules.DMG_FINAL, s) && at == null) {
                    at = io.github.verycooltimo.murim.combat.TargetLock.centre(e);
                }
            }
            if (at != null) {
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new ScatterPayload(player.getId(), at, centre,
                        (float) base, layer, ScatterPayload.FINAL_HIT, 0, (int) s[8]));
            }
        }
        // Рассеяние (full-19): один импульс по области вокруг цели.
        if (ScatterRules.scatter(layer) && t == ScatterRules.SCATTER) {
            double r = ScatterRules.SCATTER_RADIUS;
            boolean any = false;
            for (LivingEntity e : candidates(player, new AABB(centre, centre).inflate(r + 1.0D))) {
                if (e instanceof net.minecraft.world.entity.decoration.ArmorStand
                        || io.github.verycooltimo.murim.combat.TargetLock.centre(e).distanceTo(centre) > r + e.getBbWidth() * 0.5D) {
                    continue;
                }
                any |= hurt(player, id, e, dmg * ScatterRules.DMG_SCATTER, s);
            }
            if (any) {
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new ScatterPayload(player.getId(), centre, centre,
                        (float) base, layer, ScatterPayload.SCATTER_HIT, 0, (int) s[8]));
            }
        }
    }

    /** Урон от удара: без «неуязвимости после удара» (очередь не должна глотаться) и без отброса. */
    private static boolean hurt(ServerPlayer player, ResourceLocation id, LivingEntity t, double amount, double[] s) {
        t.invulnerableTime = 0;
        if (!t.hurt(player.damageSources().playerAttack(player), (float) amount)) {
            return false;
        }
        t.setDeltaMovement(t.getDeltaMovement().multiply(0.1D, 0.0D, 0.1D));
        t.hurtMarked = true;
        Casters.onHit(player, id, t);
        if (s[11] < 0.5D) {
            // Первое попадание оглушает: мобы до конца техники, игрок 0,6 с, босс 0,5 с.
            s[11] = 1.0D;
            player.setData(ModAttachments.SCATTER, s);
            io.github.verycooltimo.murim.combat.Stun.apply(t, ScatterRules.END - ScatterRules.STRIKE0);
        }
        return true;
    }

    /** Существа, чей хитбокс (раздутый на {@code blade}) пересекает отрезок a → b. */
    static List<LivingEntity> swept(ServerPlayer player, Vec3 a, Vec3 b, double blade) {
        AABB box = new AABB(a, b).inflate(blade + 1.0D);
        return candidates(player, box).stream()
                .filter(e -> !(e instanceof net.minecraft.world.entity.decoration.ArmorStand))
                .filter(e -> hits(e.getBoundingBox().inflate(blade), a, b))
                .toList();
    }

    /** Отрезок задевает коробку: начало внутри или луч пересекает её до конца отрезка. */
    public static boolean hits(AABB box, Vec3 a, Vec3 b) {
        // API: reference/minecraft-src/net/minecraft/world/phys/AABB.java#clip(Vec3, Vec3)
        return box.contains(a) || box.contains(b) || box.clip(a, b).isPresent();
    }

    /** Ближайшая к {@code p} точка отрезка a → b. */
    public static Vec3 closest(Vec3 a, Vec3 b, Vec3 p) {
        Vec3 d = b.subtract(a);
        double l = d.lengthSqr();
        double k = l < 1.0E-9D ? 0.0D : Math.max(0.0D, Math.min(1.0D, p.subtract(a).dot(d) / l));
        return a.add(d.scale(k));
    }

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
        return o.add(d.normalize().scale(reach));
    }

    private static List<LivingEntity> candidates(ServerPlayer player, AABB box) {
        return player.serverLevel().getEntitiesOfClass(LivingEntity.class, box,
                c -> c != player && c.isAlive() && !c.isSpectator());
    }

    private ScatterExecutor() {
    }
}
