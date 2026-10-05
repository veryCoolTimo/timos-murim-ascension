package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.combat.FootworkService;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.FallingPetalPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Сервер Меча Падающего Цветка (см. {@link FallingPetalRules}): выбор живой цели, семь плавных
 * рывков вокруг неё (без телепорта), пять ударов с честной проверкой дистанции, оглушение первым
 * попаданием, обрыв при смерти/уходе цели. Клиенту — только факты: старт и подтверждённые удары.
 *
 * <p>Состояние — {@link ModAttachments#FALLING_PETAL}: {старт x, y, z, ось x, z, слой, id цели или
 * −1, маска попавших ударов, оглушено 0/1, оборвано 0/1, урон в руке, ось финала x, z}.
 */
public final class FallingPetalExecutor {

    private static final int TARGET = 6;
    private static final int MASK = 7;
    private static final int STUNNED = 8;
    private static final int ABORTED = 9;
    private static final int BASE = 10;
    private static final int FIN_X = 11;
    private static final int FIN_Z = 12;

    /** IMPACT: цель по прицелу до 16 блоков, вход-рывок к её передней левой четверти. */
    public static boolean start(ServerPlayer player, ResourceLocation id) {
        int layer = Math.max(0, MasteryService.layer(player, id));
        Vec3 o = player.position();
        Vec3 look = player.getLookAngle();
        Vec3 f = new Vec3(look.x, 0.0D, look.z);
        f = f.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : f.normalize();
        LivingEntity target = pick(player, o, f);
        double base = TechniqueDamage.base(player, id) * FallingPetalRules.power(layer);
        if (target != null) {
            Vec3 to = new Vec3(target.getX() - o.x, 0.0D, target.getZ() - o.z);
            if (to.lengthSqr() > 1.0E-4D) {
                f = to.normalize();
            }
        }
        double[] s = {o.x, o.y, o.z, f.x, f.z, layer, target == null ? -1 : target.getId(), 0, 0, 0, base, f.x, f.z};
        player.setData(ModAttachments.FALLING_PETAL, s);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new FallingPetalPayload(player.getId(), target == null ? -1 : target.getId(), 0, o, f, layer));
        if (target == null) {
            // Цели нет: короткий натиск вперёд и один взмах в пустоту (или по тому, кто подвернулся).
            FootworkService.sendDash(player, f, wallClip(player, f, FallingPetalRules.LUNGE), FallingPetalRules.LUNGE_TICKS);
            return false;
        }
        dash(player, s, target, 0);
        return false;
    }

    /** Шкала после входа: рывки по точкам вокруг живой цели и окна контакта ударов. */
    public static void tick(ServerPlayer player, ResourceLocation id, int since) {
        double[] s = player.getData(ModAttachments.FALLING_PETAL);
        if (s.length < 13 || s[ABORTED] > 0.5D) {
            return;
        }
        Vec3 f = new Vec3(s[3], 0.0D, s[4]);
        if (s[TARGET] < 0) {
            if (since == FallingPetalRules.LUNGE_CUT) {
                lungeCut(player, id, s, f);
            }
            return;
        }
        LivingEntity target = player.level().getEntity((int) s[TARGET]) instanceof LivingEntity le ? le : null;
        if (target == null || !target.isAlive() || flat(target.position(), player.position()) > FallingPetalRules.RANGE + 4.0D) {
            // Цель умерла или ушла: оставшиеся удары отменяются, мастер тормозит на месте.
            s[ABORTED] = 1.0D;
            player.setData(ModAttachments.FALLING_PETAL, s);
            if (target != null) {
                release(target);
            }
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new FallingPetalPayload(player.getId(), (int) s[TARGET], 9, player.position(), f, (int) s[5]));
            return;
        }
        for (int k = 1; k < FallingPetalRules.DASHES.length; k++) {
            if (FallingPetalRules.DASHES[k][0] == since) {
                dash(player, s, target, k);
            }
        }
        if (since == FallingPetalRules.FINALE_LOCK) {
            Vec3 d = new Vec3(target.getX() - player.getX(), 0.0D, target.getZ() - player.getZ());
            if (d.lengthSqr() > 1.0E-4D) {
                d = d.normalize();
                s[FIN_X] = d.x;
                s[FIN_Z] = d.z;
                player.setData(ModAttachments.FALLING_PETAL, s);
            }
        }
        // Окно контакта: сервер видит игрока на 1–2 тика позади плавного рывка клиента.
        for (int k = 0; k < FallingPetalRules.STRIKES.length; k++) {
            int c = FallingPetalRules.STRIKES[k];
            if (since >= c - 1 && since <= c + 2 && ((int) s[MASK] & (1 << k)) == 0
                    && flat(target.position(), player.position()) <= FallingPetalRules.REACH + target.getBbWidth() * 0.5D) {
                s[MASK] = (int) s[MASK] | (1 << k);
                player.setData(ModAttachments.FALLING_PETAL, s);
                strike(player, id, s, target, k);
            }
        }
        if (since == FallingPetalRules.KNEEL && ((int) s[MASK] & (1 << 3)) != 0) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new FallingPetalPayload(player.getId(), target.getId(), 8, target.position(), f, (int) s[5]));
        }
        if (since == FallingPetalRules.END) {
            release(target);
        }
    }

    private static void strike(ServerPlayer player, ResourceLocation id, double[] s, LivingEntity t, int k) {
        t.invulnerableTime = 0;
        Vec3 before = t.getDeltaMovement();
        if (!t.hurt(player.damageSources().playerAttack(player), (float) (s[BASE] * FallingPetalRules.DAMAGE[k]))) {
            return;
        }
        // Отброс гасится: цель остаётся в рисунке обхода; финал чуть толкает вперёд по оси удара.
        if (k == 2) {
            // Плечо: цель резко сгибается от контакта — короткий толчок от мастера и вниз.
            Vec3 away = new Vec3(t.getX() - player.getX(), 0.0D, t.getZ() - player.getZ());
            away = away.lengthSqr() > 1.0E-4D ? away.normalize() : away;
            t.setDeltaMovement(away.x * 0.18D, 0.05D, away.z * 0.18D);
        } else if (k < 4) {
            t.setDeltaMovement(before.x * 0.2D, Math.min(before.y, 0.1D), before.z * 0.2D);
        } else {
            t.setDeltaMovement(s[FIN_X] * 0.25D, 0.12D, s[FIN_Z] * 0.25D);
        }
        t.hurtMarked = true;
        if (s[STUNNED] < 0.5D) {
            s[STUNNED] = 1.0D;
            player.setData(ModAttachments.FALLING_PETAL, s);
            io.github.verycooltimo.murim.combat.Stun.apply(t, FallingPetalRules.END - FallingPetalRules.STRIKES[k] + 2);
        }
        Casters.onHit(player, id, t);
        Vec3 at = k == 3 ? t.position().add(0.0D, t.getBbHeight() * 0.35D, 0.0D)
                : t.position().add(0.0D, t.getBbHeight() * (k == 2 ? 0.8D : k == 4 ? 0.86D : 0.62D), 0.0D);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new FallingPetalPayload(player.getId(), t.getId(), k + 1, at, new Vec3(s[FIN_X], 0.0D, s[FIN_Z]), (int) s[5]));
    }

    private static void lungeCut(ServerPlayer player, ResourceLocation id, double[] s, Vec3 f) {
        double cos = Math.cos(Math.toRadians(40.0D));
        for (LivingEntity t : candidates(player, 4.0D)) {
            Vec3 to = new Vec3(t.getX() - player.getX(), 0.0D, t.getZ() - player.getZ());
            double d = to.length();
            if (d <= FallingPetalRules.REACH + t.getBbWidth() * 0.5D && d > 1.0E-3D && to.normalize().dot(f) >= cos) {
                t.invulnerableTime = 0;
                if (t.hurt(player.damageSources().playerAttack(player), (float) (s[BASE] * FallingPetalRules.DAMAGE[0]))) {
                    Casters.onHit(player, id, t);
                    PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new FallingPetalPayload(player.getId(), t.getId(), 1,
                            t.position().add(0.0D, t.getBbHeight() * 0.62D, 0.0D), f, (int) s[5]));
                }
                return;
            }
        }
    }

    /** Рывок {@code k} к точке вокруг живой цели; точка пересчитывается от её позиции сейчас. */
    private static void dash(ServerPlayer player, double[] s, LivingEntity target, int k) {
        Vec3 f = new Vec3(s[3], 0.0D, s[4]);
        Vec3 goal = FallingPetalRules.point(k, target.position(), f, target.getBbWidth() * 0.5D);
        Vec3 path = new Vec3(goal.x - player.getX(), 0.0D, goal.z - player.getZ());
        double len = path.length();
        if (len < 0.08D) {
            return;
        }
        Vec3 dir = path.scale(1.0D / len);
        FootworkService.sendDash(player, dir, wallClip(player, dir, len), FallingPetalRules.DASHES[k][1]);
    }

    /** Путь обрезается первой стеной на высоте пояса: сквозь блоки ради силуэта не тащим. */
    private static double wallClip(ServerPlayer player, Vec3 dir, double len) {
        Vec3 from = player.position().add(0.0D, 0.9D, 0.0D);
        net.minecraft.world.phys.BlockHitResult wall = player.level().clip(new net.minecraft.world.level.ClipContext(
                from, from.add(dir.scale(len)), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return wall.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? len
                : Math.max(0.0D, wall.getLocation().distanceTo(from) - 0.5D);
    }

    /** Снять оглушение «до конца техники» с цели. */
    private static void release(LivingEntity t) {
        io.github.verycooltimo.murim.combat.Stun.release(t);
    }

    /** Ближайший живой противник в конусе 35° до 16 блоков при прямой видимости; стойки брони — нет. */
    private static LivingEntity pick(ServerPlayer player, Vec3 o, Vec3 f) {
        LivingEntity locked = io.github.verycooltimo.murim.combat.TargetLock.locked(player, FallingPetalRules.RANGE + 2.0D);
        if (locked != null) {
            return locked;
        }
        double cone = Math.cos(Math.toRadians(35.0D));
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity t : candidates(player, FallingPetalRules.RANGE + 0.5D)) {
            Vec3 to = new Vec3(t.getX() - o.x, 0.0D, t.getZ() - o.z);
            double d = to.length();
            if (d < 0.5D || d > FallingPetalRules.RANGE || to.normalize().dot(f) < cone || !player.hasLineOfSight(t)) {
                continue;
            }
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    private static List<LivingEntity> candidates(ServerPlayer player, double r) {
        return player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(r, 3.0D, r),
                t -> t != player && t.isAlive() && !t.isSpectator() && !(t instanceof ArmorStand));
    }

    private static double flat(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private FallingPetalExecutor() {
    }
}
