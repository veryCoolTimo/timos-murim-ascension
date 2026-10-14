package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.combat.FootworkService;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.FallingPetalPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Server of the Falling Petal Sword v3 (see {@link FallingPetalRules}): pick a live target, then
 * three blinks — the master is moved behind the target (rear, rear-left, rear-right of its current
 * facing) to a safe spot, cuts once with an honest distance check, and the target turns toward him
 * before the next blink. The first hit holds the target until the end; the client gets only facts.
 *
 * <p>State — {@link ModAttachments#FALLING_PETAL}: {start x, y, z, axis x, z, executionTier, target id or
 * −1, mask of landed cuts, held 0/1, aborted 0/1, damage in hand, last cut axis x, z}.
 */
public final class FallingPetalExecutor {

    private static final int TARGET = 6;
    private static final int MASK = 7;
    private static final int STUNNED = 8;
    private static final int ABORTED = 9;
    private static final int BASE = 10;
    private static final int FIN_X = 11;
    private static final int FIN_Z = 12;

    /** IMPACT: target under the crosshair up to 16 blocks; the stance already happened in place. */
    public static boolean start(ServerPlayer player, ResourceLocation id) {
        int executionTier = Math.max(0, MasteryService.executionTier(player, id));
        Vec3 o = player.position();
        Vec3 look = player.getLookAngle();
        Vec3 f = new Vec3(look.x, 0.0D, look.z);
        f = f.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : f.normalize();
        LivingEntity target = pick(player, o, f);
        double base = TechniqueDamage.base(player, id) * FallingPetalRules.power(executionTier);
        if (target != null) {
            Vec3 to = new Vec3(target.getX() - o.x, 0.0D, target.getZ() - o.z);
            if (to.lengthSqr() > 1.0E-4D) {
                f = to.normalize();
            }
        }
        double[] s = {o.x, o.y, o.z, f.x, f.z, executionTier, target == null ? -1 : target.getId(), 0, 0, 0, base, f.x, f.z};
        player.setData(ModAttachments.FALLING_PETAL, s);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new FallingPetalPayload(player.getId(), target == null ? -1 : target.getId(), 0, o, f, executionTier));
        if (target == null) {
            // No target: a short lunge forward and one cut into the air (or whoever is there).
            FootworkService.sendDash(player, f, wallClip(player, f, FallingPetalRules.LUNGE), FallingPetalRules.LUNGE_TICKS);
        }
        return false;
    }

    /** Timeline after the stance: vanish, reappear behind, cut, the target turns. */
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
            // Target died or left: the remaining cuts are cancelled, the master stops where he is.
            s[ABORTED] = 1.0D;
            player.setData(ModAttachments.FALLING_PETAL, s);
            if (target != null) {
                release(target);
            }
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new FallingPetalPayload(player.getId(), (int) s[TARGET], 9, player.position(), f, (int) s[5]));
            return;
        }
        for (int k = 0; k < FallingPetalRules.VANISH.length; k++) {
            if (since == FallingPetalRules.VANISH[k] + FallingPetalRules.APPEAR_DELAY) {
                blink(player, s, target, k);
            }
            // The target turns toward the master after each cut except the last.
            int hit = FallingPetalRules.STRIKES[k];
            if (k < FallingPetalRules.STRIKES.length - 1 && since >= hit + 2 && since < hit + 2 + FallingPetalRules.TURN_TICKS) {
                turnToward(target, player.position(), FallingPetalRules.TURN_SPEED);
            }
        }
        // Each cut closes the gap: a fast lunge slide over the last ticks before contact.
        for (int k = 0; k < FallingPetalRules.STRIKES.length; k++) {
            if (since == FallingPetalRules.STRIKES[k] - FallingPetalRules.LUNGE_STEP_TICKS) {
                Vec3 d = new Vec3(target.getX() - player.getX(), 0.0D, target.getZ() - player.getZ());
                double len = d.length() - target.getBbWidth() * 0.5D - FallingPetalRules.STRIKE_GAP;
                if (len > 0.1D) {
                    Vec3 dir = d.normalize();
                    FootworkService.sendDash(player, dir, wallClip(player, dir, len), FallingPetalRules.LUNGE_STEP_TICKS);
                }
            }
        }
        // Contact window: the server sees the player 1-2 ticks behind the client.
        for (int k = 0; k < FallingPetalRules.STRIKES.length; k++) {
            int c = FallingPetalRules.STRIKES[k];
            if (since >= c - 1 && since <= c + 2 && ((int) s[MASK] & (1 << k)) == 0
                    && flat(target.position(), player.position()) <= FallingPetalRules.REACH + target.getBbWidth() * 0.5D) {
                s[MASK] = (int) s[MASK] | (1 << k);
                Vec3 d = new Vec3(target.getX() - player.getX(), 0.0D, target.getZ() - player.getZ());
                if (d.lengthSqr() > 1.0E-4D) {
                    d = d.normalize();
                    s[FIN_X] = d.x;
                    s[FIN_Z] = d.z;
                }
                player.setData(ModAttachments.FALLING_PETAL, s);
                strike(player, id, s, target, k);
            }
        }
        if (since == FallingPetalRules.END) {
            release(target);
        }
    }

    /** Reappear behind the target on side {@code k}; the vanish/appear points go to the client. */
    private static void blink(ServerPlayer player, double[] s, LivingEntity target, int k) {
        Vec3 from = player.position();
        Vec3 facing = Vec3.directionFromRotation(0.0F, target.getYRot());
        facing = new Vec3(facing.x, 0.0D, facing.z);
        facing = facing.lengthSqr() < 1.0E-6D ? new Vec3(s[3], 0.0D, s[4]) : facing.normalize();
        Vec3 to = safeBehind(player, target, facing, k);
        if (to == null) {
            // Nowhere safe behind: dash next to the target instead; the cut still lands if in reach.
            Vec3 d = new Vec3(target.getX() - from.x, 0.0D, target.getZ() - from.z);
            double len = d.length() - target.getBbWidth() * 0.5D - 1.2D;
            if (len > 0.1D) {
                FootworkService.sendDash(player, d.normalize(), wallClip(player, d.normalize(), len), 2);
            }
            to = from;
        } else {
            float yaw = (float) Math.toDegrees(Math.atan2(-(target.getX() - to.x), target.getZ() - to.z));
            // API: ServerPlayer#teleportTo(ServerLevel, double, double, double, float, float) — as SectWatch does.
            player.teleportTo(player.serverLevel(), to.x, to.y, to.z, yaw, player.getXRot());
            player.setYHeadRot(yaw);
            player.resetFallDistance();
        }
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new FallingPetalPayload(player.getId(), target.getId(), 10 + k, from, to, (int) s[5]));
    }

    /**
     * Free spot for the player behind the target: the side of cut {@code k}, then ±25° around it,
     * then closer; the box must be free of blocks, stand on ground, and see the target (no walls).
     */
    public static Vec3 safeBehind(ServerPlayer player, LivingEntity target, Vec3 facing, int k) {
        double half = target.getBbWidth() * 0.5D;
        double side = FallingPetalRules.SIDES[k];
        double[][] tries = {{side, FallingPetalRules.BEHIND}, {side + 25.0D, FallingPetalRules.BEHIND},
                {side - 25.0D, FallingPetalRules.BEHIND}, {side, 1.8D}, {side + 50.0D, 2.0D}, {side - 50.0D, 2.0D}, {side, 1.0D}};
        for (double[] t : tries) {
            Vec3 p = FallingPetalRules.behind(target.position(), facing, half, t[0], t[1]);
            for (double dy : new double[] {0.0D, 1.0D, -1.0D}) {
                Vec3 at = new Vec3(p.x, target.getY() + dy, p.z);
                AABB box = player.getDimensions(player.getPose()).makeBoundingBox(at);
                if (!player.level().noCollision(player, box)) {
                    continue;
                }
                if (player.level().noCollision(player, box.move(0.0D, -0.6D, 0.0D))) {
                    continue;   // no ground under the feet
                }
                Vec3 chest = target.position().add(0.0D, target.getBbHeight() * 0.6D, 0.0D);
                if (player.level().clip(new ClipContext(chest, at.add(0.0D, 1.0D, 0.0D), ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.MISS) {
                    continue;
                }
                return at;
            }
        }
        return null;
    }

    /** Turn a (held, AI-less) target toward a point by at most {@code step} degrees. */
    public static void turnToward(LivingEntity t, Vec3 at, float step) {
        float want = (float) Math.toDegrees(Math.atan2(-(at.x - t.getX()), at.z - t.getZ()));
        float cur = t.getYRot();
        float next = cur + Mth.clamp(Mth.wrapDegrees(want - cur), -step, step);
        t.setYRot(next);
        t.setYHeadRot(next);
        t.setYBodyRot(next);
    }

    private static void strike(ServerPlayer player, ResourceLocation id, double[] s, LivingEntity t, int k) {
        t.invulnerableTime = 0;
        Vec3 before = t.getDeltaMovement();
        if (!t.hurt(player.damageSources().playerAttack(player), (float) (s[BASE] * FallingPetalRules.DAMAGE[k]))) {
            return;
        }
        // Knockback is cancelled: the target stays for the next blink; the last cut pushes it a little.
        if (k < FallingPetalRules.STRIKES.length - 1) {
            t.setDeltaMovement(before.x * 0.2D, Math.min(before.y, 0.1D), before.z * 0.2D);
        } else {
            t.setDeltaMovement(s[FIN_X] * 0.25D, 0.12D, s[FIN_Z] * 0.25D);
        }
        t.hurtMarked = true;
        if (s[STUNNED] < 0.5D) {
            s[STUNNED] = 1.0D;
            player.setData(ModAttachments.FALLING_PETAL, s);
            io.github.verycooltimo.murim.combat.Stun.hold(t, player, FallingPetalRules.END - FallingPetalRules.STRIKES[k] + 2);
        }
        Casters.onHit(player, id, t);
        Vec3 at = t.position().add(0.0D, t.getBbHeight() * (k == 1 ? 0.62D : 0.8D), 0.0D);
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

    /** The path stops at the first wall at waist height: no pulling through blocks for a silhouette. */
    private static double wallClip(ServerPlayer player, Vec3 dir, double len) {
        Vec3 from = player.position().add(0.0D, 0.9D, 0.0D);
        net.minecraft.world.phys.BlockHitResult wall = player.level().clip(new ClipContext(
                from, from.add(dir.scale(len)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return wall.getType() == HitResult.Type.MISS ? len : Math.max(0.0D, wall.getLocation().distanceTo(from) - 0.5D);
    }

    /** Release the "until the end of the technique" hold. */
    private static void release(LivingEntity t) {
        io.github.verycooltimo.murim.combat.Stun.unhold(t);
    }

    /** Nearest live enemy in a 35° cone up to 16 blocks in line of sight; armour stands never. */
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
