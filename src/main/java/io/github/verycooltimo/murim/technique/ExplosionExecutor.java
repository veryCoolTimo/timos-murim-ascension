package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.combat.TargetLock;
import io.github.verycooltimo.murim.network.ExplosionPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Сервер Взрыва Цветущей Сливы (см. {@link ExplosionRules}). Состояние — вложение
 * {@code EXPLOSION}: {ступни x, y, z, направление удара x, y, z, слой, id цели или −1, точка удара
 * x, y, z, ось x, y, z, взорвана 0/1, число задетых, id задетых ×24}.
 *
 * <p>На IMPACT ({@link #strike}) выбирается цель: захваченная, иначе ближайший противник под
 * взглядом (в том числе в небе), иначе взгляд; мастер делает полушаг, точка удара — 1,5 блока
 * перед ним. Через {@link ExplosionRules#LUNGE_TICKS} клинок доходит до неё — выброс летит
 * фронтом к цели. Урон — только тем, кого фронт реально накрыл и до кого нет стены блоков;
 * отброс по оси, через {@link ExplosionRules#STUN_DELAY} тиков — оглушение (замедление ≥ 4 гасит
 * ИИ, см. {@link TargetLock}); лепестковая буря дорезает дважды.
 */
public final class ExplosionExecutor {

    private static final int FEET = 0;
    private static final int AIM = 3;
    private static final int LAYER = 6;
    private static final int TARGET = 7;
    private static final int STRIKE = 8;
    private static final int AXIS = 11;
    private static final int BLASTED = 14;
    private static final int HITS = 15;
    private static final int HIT_IDS = 16;
    private static final int MAX_HITS = 24;

    /** Начало каста: сброс состояния (слой, цели ещё нет — она выбирается на ударе). */
    public static void begin(ServerPlayer player, ResourceLocation id) {
        double[] r = new double[HIT_IDS + MAX_HITS];
        r[LAYER] = Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.layer(player, id));
        r[TARGET] = -1;
        player.setData(ModAttachments.EXPLOSION, r);
    }

    /** Захваченная цель, иначе ближайший противник в конусе 35° взгляда (3D), видимый. */
    private static LivingEntity aim(ServerPlayer player, double range) {
        LivingEntity target = TargetLock.locked(player, range + 4.0D);
        if (target != null) {
            return target;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double best = Double.MAX_VALUE;
        double cone = Math.cos(Math.toRadians(35.0D));
        for (LivingEntity t : candidates(player, player.getBoundingBox().inflate(range + 0.5D))) {
            Vec3 to = TargetLock.centre(t).subtract(eye);
            double d = to.length();
            if (t instanceof net.minecraft.world.entity.decoration.ArmorStand || d < 0.5D || d > range
                    || to.normalize().dot(look) < cone || !player.hasLineOfSight(t)) {
                continue;
            }
            if (d < best) {
                best = d;
                target = t;
            }
        }
        return target;
    }

    /** IMPACT: прыжок-удар к середине стены. Слой 0 — учебный удар в ближнем секторе. */
    public static boolean strike(ServerPlayer player, ResourceLocation id) {
        double[] r = player.getData(ModAttachments.EXPLOSION);
        int layer = r.length > LAYER ? (int) r[LAYER] : 0;
        if (layer <= 0 || r.length < HIT_IDS) {
            double base = TechniqueDamage.base(player, id);
            Vec3 look = player.getLookAngle();
            boolean hit = false;
            double cos = Math.cos(Math.toRadians(PlumRules.TRAINING_ARC / 2.0D));
            for (LivingEntity t : candidates(player, player.getBoundingBox().inflate(PlumRules.TRAINING_REACH + 1.0D))) {
                if (BehaviorExecutor.inArc(player.getEyePosition(), look, t.getBoundingBox(), PlumRules.TRAINING_REACH, cos)
                        && t.hurt(player.damageSources().playerAttack(player), (float) (base * ExplosionRules.DMG_TRAINING))) {
                    hit = true;
                }
            }
            return hit;
        }
        Vec3 feet = player.position();
        Vec3 chest = feet.add(0.0D, 1.3D, 0.0D);
        LivingEntity target = aim(player, ExplosionRules.range(layer) + 2.0D);
        Vec3 aim = (target != null ? TargetLock.centre(target).subtract(chest) : player.getLookAngle()).normalize();
        Vec3 strike = ExplosionRules.strikePoint(feet, aim);
        Vec3 f = ExplosionRules.flat(aim);
        r[FEET] = feet.x;
        r[FEET + 1] = feet.y;
        r[FEET + 2] = feet.z;
        r[AIM] = aim.x;
        r[AIM + 1] = aim.y;
        r[AIM + 2] = aim.z;
        r[STRIKE] = strike.x;
        r[STRIKE + 1] = strike.y;
        r[STRIKE + 2] = strike.z;
        r[TARGET] = target == null ? -1 : target.getId();
        player.setData(ModAttachments.EXPLOSION, r);
        // Полушаг в удар; стена блоков на пути — шага нет.
        if (clear(player, feet.add(0.0D, 0.9D, 0.0D), feet.add(f.scale(ExplosionRules.STEP + 0.4D)).add(0.0D, 0.9D, 0.0D))) {
            io.github.verycooltimo.murim.combat.FootworkService.sendDash(player, f, ExplosionRules.STEP, ExplosionRules.LUNGE_TICKS);
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-f.x, f.z));
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new ExplosionPayload(player.getId(), feet, strike, yaw, layer, ExplosionPayload.LUNGE, (int) r[TARGET]));
        return false;
    }

    /** Шкала техники по тикам T: взрыв, фронт, оглушение, дорезы. */
    public static void tick(ServerPlayer player, ResourceLocation id, int t) {
        double[] r = player.getData(ModAttachments.EXPLOSION);
        if (r.length < HIT_IDS + MAX_HITS || r[LAYER] <= 0) {
            return;
        }
        int layer = (int) r[LAYER];
        if (t == ExplosionRules.CONTACT && r[BLASTED] < 0.5D) {
            blast(player, r, layer);
        }
        if (r[BLASTED] < 0.5D) {
            return;
        }
        int s = t - ExplosionRules.CONTACT;
        Vec3 strike = new Vec3(r[STRIKE], r[STRIKE + 1], r[STRIKE + 2]);
        Vec3 axis = new Vec3(r[AXIS], r[AXIS + 1], r[AXIS + 2]);
        double base = TechniqueDamage.base(player, id) * ExplosionRules.power(layer);
        if (s >= 0 && ExplosionRules.front(s - 1, layer) < ExplosionRules.range(layer) || s == 0) {
            front(player, id, r, layer, strike, axis, ExplosionRules.front(s, layer), base);
        }
        if (s == ExplosionRules.STUN_DELAY) {
            for (LivingEntity e : hit(player, r)) {
                stun(e);
            }
        }
        for (int g : ExplosionRules.GRIND) {
            if (s != g) {
                continue;
            }
            for (LivingEntity e : hit(player, r)) {
                if (e.position().distanceTo(strike) > ExplosionRules.range(layer) + 6.0D) {
                    continue;
                }
                e.invulnerableTime = 0;
                if (e.hurt(player.damageSources().playerAttack(player), (float) (base * ExplosionRules.DMG_GRIND))) {
                    Casters.onHit(player, id, e);
                    PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                            new ExplosionPayload(player.getId(), strike, TargetLock.centre(e), 1.0F, layer, ExplosionPayload.HIT, e.getId()));
                }
            }
        }
    }

    /** Клинок дошёл до точки удара: ось выброса — к цели (в небо тоже), не дальше 55° от удара. */
    private static void blast(ServerPlayer player, double[] r, int layer) {
        Vec3 strike = new Vec3(r[STRIKE], r[STRIKE + 1], r[STRIKE + 2]);
        Vec3 aimDir = new Vec3(r[AIM], r[AIM + 1], r[AIM + 2]);
        LivingEntity target = r[TARGET] >= 0 && player.level().getEntity((int) r[TARGET]) instanceof LivingEntity le && le.isAlive()
                && le.distanceTo(player) < ExplosionRules.range(layer) + 6.0D ? le : null;
        if (target == null) {
            target = TargetLock.locked(player, ExplosionRules.range(layer) + 4.0D);
        }
        Vec3 axis = ExplosionRules.axis(strike, aimDir, target == null ? null : TargetLock.centre(target));
        r[AXIS] = axis.x;
        r[AXIS + 1] = axis.y;
        r[AXIS + 2] = axis.z;
        r[BLASTED] = 1.0D;
        player.setData(ModAttachments.EXPLOSION, r);
        // Звук взрыва играет клиент (ExplosionVfx#blast: petal_burst, по попаданию — impact_heavy) всем,
        // кто получил пакет; на слое 0 эффектов нет — только свист клинка из TechniqueService.
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new ExplosionPayload(player.getId(), strike, strike.add(axis), 0.0F, layer, ExplosionPayload.BLAST,
                        target == null ? -1 : target.getId()));
    }

    /** Фронт выброса: каждый, кого он накрыл впервые и до кого нет стены блоков, получает удар. */
    private static void front(ServerPlayer player, ResourceLocation id, double[] r, int layer, Vec3 strike, Vec3 axis, double front,
                              double base) {
        double range = ExplosionRules.range(layer);
        Vec3 mid = strike.add(axis.scale(front * 0.5D));
        AABB box = new AABB(mid, mid).inflate(front * 0.5D + ExplosionRules.HALF_W0 + range * ExplosionRules.SPREAD_H + 2.0D);
        for (LivingEntity e : candidates(player, box)) {
            if (r[HITS] >= MAX_HITS || e instanceof net.minecraft.world.entity.decoration.ArmorStand || already(r, e.getId())) {
                continue;
            }
            Vec3 c = TargetLock.centre(e);
            double pad = Math.max(e.getBbWidth(), e.getBbHeight()) * 0.5D;
            if (ExplosionRules.inside(strike, axis, c, front, layer, pad) < 0.0D || !clear(player, strike, c)) {
                continue;
            }
            e.invulnerableTime = 0;
            if (!e.hurt(player.damageSources().playerAttack(player), (float) (base * ExplosionRules.DMG_BLAST))) {
                continue;
            }
            Casters.onHit(player, id, e);
            int k = (int) r[HITS];
            r[HIT_IDS + k] = e.getId();
            r[HITS] = k + 1;
            player.setData(ModAttachments.EXPLOSION, r);
            // Отброс по оси выброса (в 3D: цель в небе уходит выше и дальше) и подброс.
            boolean boss = e.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES);
            double k2 = boss ? 0.3D : 1.0D;
            Vec3 push = axis.scale(ExplosionRules.KNOCK * k2).add(0.0D, ExplosionRules.LIFT * k2, 0.0D);
            e.setDeltaMovement(e.getDeltaMovement().scale(0.2D).add(push));
            e.hurtMarked = true;
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new ExplosionPayload(player.getId(), strike, c, 0.0F, layer, ExplosionPayload.HIT, e.getId()));
        }
    }

    /** Оглушение после отброса: мобы 2 с, игрок 0,6 с, босс 0,5 с. */
    private static void stun(LivingEntity t) {
        io.github.verycooltimo.murim.combat.Stun.apply(t, ExplosionRules.STUN_MOB);
    }

    /** Лепестки не проходят сквозь камень: путь от точки удара до цели свободен от блоков. */
    private static boolean clear(ServerPlayer player, Vec3 from, Vec3 to) {
        net.minecraft.world.phys.BlockHitResult wall = player.level().clip(new net.minecraft.world.level.ClipContext(
                from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return wall.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    private static boolean already(double[] r, int id) {
        for (int i = 0; i < (int) r[HITS]; i++) {
            if ((int) r[HIT_IDS + i] == id) {
                return true;
            }
        }
        return false;
    }

    private static java.util.List<LivingEntity> hit(ServerPlayer player, double[] r) {
        java.util.List<LivingEntity> out = new java.util.ArrayList<>();
        for (int i = 0; i < (int) r[HITS]; i++) {
            if (player.level().getEntity((int) r[HIT_IDS + i]) instanceof LivingEntity le && le.isAlive()) {
                out.add(le);
            }
        }
        return out;
    }

    private static java.util.List<LivingEntity> candidates(ServerPlayer player, AABB box) {
        return player.serverLevel().getEntitiesOfClass(LivingEntity.class, box,
                c -> c != player && c.isAlive() && !c.isSpectator());
    }

    private ExplosionExecutor() {
    }
}
