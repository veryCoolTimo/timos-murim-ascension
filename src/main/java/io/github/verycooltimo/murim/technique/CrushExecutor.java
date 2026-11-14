package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.combat.Stun;
import io.github.verycooltimo.murim.combat.TargetLock;
import io.github.verycooltimo.murim.cultivation.QiRules;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.ChannelSafe;
import io.github.verycooltimo.murim.network.CrushPayload;
import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEvent;

import java.util.List;

/** Server-only timeline. No start hold: opponents may leave the frozen line.
 * Cast data is ephemeral, owned by the player, and cleared on finish/cancel/logout/death.
 * API: verified in /tmp/mr-ref-1121/minecraft-src/net/minecraft/:
 * world/entity/Entity.java (position, getLookAngle, getBoundingBox, collideBoundingBox, movement, id),
 * server/level/ServerPlayer.java (teleportTo, serverLevel), world/entity/LivingEntity.java
 * (hurt, hasLineOfSight, attributes, effects, sprinting), world/level/{EntityGetter,BlockGetter}.java
 * (getEntitiesOfClass, clip), world/phys/{Vec3,AABB}.java, world/phys/shapes/Shapes.java#create(AABB).
 * API: /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/attachment/IAttachmentHolder.java#getData/setData/removeData.
 */
@EventBusSubscriber(modid = "murim", bus = EventBusSubscriber.Bus.GAME)
public final class CrushExecutor {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum_crush");
    // axis 0..2, target, H, qi multiplier, execution tier, exact stars, deflections,
    // recoil expiry, aimed, struck. No saved field or retired-id alias is introduced.
    private static final int TARGET = 3, HAND = 4, QI = 5, TIER = 6, STARS = 7, DEFLECTED = 8,
            RECOIL_UNTIL = 9, AIMED = 10, STRUCK = 11, ANCHOR = 12, SIZE = 15;

    public static void begin(ServerPlayer p, ResourceLocation id) {
        Vec3 axis = flat(p.getLookAngle());
        double[] d = new double[SIZE];
        d[0] = axis.x; d[1] = axis.y; d[2] = axis.z; d[TARGET] = -1;
        d[TIER] = Math.max(0, MasteryService.executionTier(p, id));
        d[STARS] = Math.max(0, MasteryService.stars(p, id));
        d[ANCHOR] = p.position().x; d[ANCHOR + 1] = p.position().y; d[ANCHOR + 2] = p.position().z;
        p.setData(ModAttachments.CRUSH, d);
        send(p, d, CrushPayload.START, p.position(), false);
    }

    public static void tick(ServerPlayer p, ResourceLocation id, int t) {
        double[] d = p.getData(ModAttachments.CRUSH);
        if (d.length != SIZE) return;
        if (t < CrushRules.AIM) {
            if (Math.abs(p.position().x - d[ANCHOR]) + Math.abs(p.position().z - d[ANCHOR + 2]) > .001)
                p.teleportTo(d[ANCHOR], p.position().y, d[ANCHOR + 2]);
            p.setSprinting(false);
            p.setDeltaMovement(0, Math.min(0, p.getDeltaMovement().y), 0);
        }
        if (t == CrushRules.AIM && d[AIMED] == 0) aim(p, id, d);
        if (CrushRules.projectileWindow(t)) reflect(p, d);
        if (t >= CrushRules.STEP && t < CrushRules.STRIKE && d[AIMED] != 0) lunge(p, d);
        if (t == CrushRules.PLANT) send(p, d, CrushPayload.STEP, p.position().add(axis(d).scale(.3)), false);
        if (recoiling(p)) {
            p.setSprinting(false);
            Vec3 v = p.getDeltaMovement();
            if (v.y > 0) p.setDeltaMovement(v.x, 0, v.z);
            p.hurtMarked = true;
        }
    }

    private static void aim(ServerPlayer p, ResourceLocation id, double[] d) {
        LivingEntity target = TargetLock.locked(p, CrushRules.AIM_RANGE);
        if (target == null) {
            double nearest = Double.MAX_VALUE;
            for (LivingEntity e : candidates(p, p.getBoundingBox().inflate(CrushRules.AIM_RANGE + 1))) {
                double distance = TargetLock.centre(e).distanceTo(p.getEyePosition());
                if (distance <= CrushRules.AIM_RANGE && distance < nearest && p.hasLineOfSight(e)
                        && TargetLock.inCone(p, e, CrushRules.AIM_CONE / 2)) { target = e; nearest = distance; }
            }
        }
        Vec3 axis = target == null ? flat(p.getLookAngle()) : flat(target.position().subtract(p.position()));
        d[0] = axis.x; d[1] = axis.y; d[2] = axis.z; d[TARGET] = target == null ? -1 : target.getId();
        d[HAND] = p.getAttributeValue(Attributes.ATTACK_DAMAGE); // H: hand damage sampled exactly at T50.
        var profile = p.getData(ModAttachments.PROFILE);
        double extra = profile.circulating();
        d[QI] = CrushRules.qiMultiplier(extra, profile.maxCirculating(), QiRules.free(p));
        if (!QiRules.free(p)) {
            p.setData(ModAttachments.PROFILE, QiRules.charge(p, profile, extra));
            ProfileNetwork.sync(p);
        }
        d[AIMED] = 1;
        send(p, d, CrushPayload.AIM, p.position(), false);
    }

    /** Vanilla collision solver, with living hitboxes explicitly supplied: no stepping over a wall or target.
     * Resolve the full body box first, then synchronize the safe destination to the player's client.
     */
    private static void lunge(ServerPlayer p, double[] d) {
        Vec3 delta = axis(d).scale(CrushRules.LUNGE / (CrushRules.STRIKE - CrushRules.STEP));
        AABB box = p.getBoundingBox();
        var entities = candidates(p, box.expandTowards(delta).inflate(.1)).stream()
                .map(e -> Shapes.create(e.getBoundingBox().inflate(.02))).toList();
        Vec3 safe = Entity.collideBoundingBox(p, delta, box, p.level(), entities);
        Vec3 at = p.position().add(safe);
        p.teleportTo(at.x, at.y, at.z);
        p.setDeltaMovement(Vec3.ZERO);
    }

    /** Called once by the IMPACT hook at T54. The first body actually under the blade wins, not the aim id. */
    public static boolean strike(ServerPlayer p, ResourceLocation id) {
        double[] d = p.getData(ModAttachments.CRUSH);
        if (d.length != SIZE || d[AIMED] == 0 || d[STRUCK] != 0) return false;
        d[STRUCK] = 1;
        Vec3 axis = axis(d), from = p.position().add(0, 1, 0), end = from.add(axis.scale(1.5));
        var wall = p.level().clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 limit = wall.getType() == HitResult.Type.MISS ? end : wall.getLocation();
        LivingEntity primary = null;
        double best = from.distanceToSqr(limit);
        for (LivingEntity e : candidates(p, new AABB(from, end).inflate(1))) {
            var at = e.getBoundingBox().inflate(.12).clip(from, end);
            if (at.isPresent() && at.get().distanceToSqr(from) < best && p.hasLineOfSight(e)) {
                best = at.get().distanceToSqr(from); primary = e;
            }
        }
        Vec3 point = primary == null ? from.add(axis.scale(2)) : from.add(axis.scale(Math.sqrt(best)));
        // A wall-clipped miss still bursts, but its cone starts on our side of the obstruction.
        if (primary == null && wall.getType() != HitResult.Type.MISS) point = limit.subtract(axis.scale(.1));
        double base = d[HAND] * CrushRules.power((int) d[TIER]) * d[QI];
        boolean hit = false;
        if (primary != null && primary.hurt(p.damageSources().playerAttack(p), (float) (base * CrushRules.DMG_PRIMARY))) {
            hit = true; Casters.onHit(p, id, primary);
            knock(primary, axis, CrushRules.PUSH_PRIMARY);
            Stun.apply(primary, CrushRules.MOB_STUN); // shared service caps players at 12, bosses at 10.
        }
        for (LivingEntity e : candidates(p, new AABB(point, point).inflate(CrushRules.RANGE + 1))) {
            if (e == primary) continue;
            Vec3 to = TargetLock.centre(e).subtract(point);
            double distance = to.length();
            if (distance > CrushRules.RANGE || !inCone(axis, to, CrushRules.CONE)
                    || !visible(p, point, TargetLock.centre(e))) continue;
            if (e.hurt(p.damageSources().playerAttack(p), (float) (base * CrushRules.areaShare(distance)))) {
                hit = true; Casters.onHit(p, id, e); knock(e, axis, CrushRules.PUSH_AREA);
            }
        }
        d[RECOIL_UNTIL] = p.level().getGameTime() + CrushRules.RECOIL;
        // API: minecraft-src/world/effect/MobEffectInstance.java#MobEffectInstance(Holder,int,int,boolean,boolean,boolean)
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, CrushRules.RECOIL, 2, false, false, false));
        p.setSprinting(false);
        send(p, d, CrushPayload.STRIKE, point, hit);
        return hit;
    }

    private static void reflect(ServerPlayer p, double[] d) {
        Vec3 chest = p.position().add(0, 1, 0), axis = axis(d);
        for (Projectile projectile : p.level().getEntitiesOfClass(Projectile.class,
                p.getBoundingBox().inflate(CrushRules.RANGE + 1), e -> e.isAlive() && supported(e))) {
            Vec3 to = projectile.position().subtract(chest);
            if (projectile.getOwner() == p || to.length() > CrushRules.RANGE
                    || !inCone(axis, to, CrushRules.PROJECTILE_CONE) || !visible(p, chest, projectile.position())
                    || projectile.getDeltaMovement().dot(to) >= 0) continue;
            // API: minecraft-src/world/entity/projectile/Projectile.java#getOwner/setOwner;
            // AbstractHurtingProjectile.java#accelerationPower accelerates along delta movement in 1.21.1.
            double speed = Math.max(.8, projectile.getDeltaMovement().length());
            projectile.setOwner(p);
            projectile.setDeltaMovement(axis.scale(-.65).add(0, 1, 0).normalize().scale(speed));
            if (projectile instanceof AbstractHurtingProjectile fire) fire.accelerationPower = .1;
            if (projectile instanceof TangDagger dagger) { dagger.setMode(TangDagger.STRAIGHT); dagger.speed = speed; }
            projectile.hasImpulse = true;
            d[DEFLECTED]++;
        }
    }

    private static boolean supported(Projectile p) {
        return p instanceof AbstractArrow || p instanceof Snowball || p instanceof ThrownEgg
                || p instanceof AbstractHurtingProjectile || p instanceof LlamaSpit || p instanceof TangDagger;
    }
    private static boolean inCone(Vec3 axis, Vec3 to, double degrees) {
        return to.lengthSqr() < 1.e-8 || axis.dot(to.normalize()) >= Math.cos(Math.toRadians(degrees / 2));
    }
    private static Vec3 flat(Vec3 v) {
        Vec3 flat = new Vec3(v.x, 0, v.z);
        return flat.lengthSqr() < 1.e-8 ? new Vec3(0, 0, 1) : flat.normalize();
    }
    private static Vec3 axis(double[] d) { return new Vec3(d[0], d[1], d[2]); }
    private static void knock(LivingEntity e, Vec3 axis, double speed) {
        Vec3 v = axis.scale(speed).add(0, CrushRules.PUSH_LIFT, 0);
        if (v.length() > CrushRules.PUSH_MAX) v = v.normalize().scale(CrushRules.PUSH_MAX);
        e.setDeltaMovement(v.scale(Math.max(0, 1 - e.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE))));
        e.hurtMarked = true;
    }
    private static boolean visible(ServerPlayer p, Vec3 from, Vec3 to) {
        return p.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p))
                .getType() == HitResult.Type.MISS;
    }
    private static List<LivingEntity> candidates(ServerPlayer p, AABB box) {
        return p.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != p && e.isAlive() && !e.isSpectator() && !(e instanceof ArmorStand));
    }
    private static void send(ServerPlayer p, double[] d, int stage, Vec3 position, boolean hit) {
        ChannelSafe.nearEntity(p, new CrushPayload(p.getId(), (int) d[TIER], (int) d[STARS], stage,
                (int) d[TARGET], position, axis(d), hit, (int) d[DEFLECTED]), true);
    }
    public static boolean recoiling(ServerPlayer p) {
        double[] d = p.getData(ModAttachments.CRUSH);
        return d.length == SIZE && d[RECOIL_UNTIL] > p.level().getGameTime();
    }
    // API: /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/event/entity/living/LivingEvent.java#LivingJumpEvent.
    @SubscribeEvent static void jump(LivingEvent.LivingJumpEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && recoiling(p)) {
            Vec3 v = p.getDeltaMovement(); p.setDeltaMovement(v.x, Math.min(0, v.y), v.z); p.hurtMarked = true;
        }
    }
    public static void end(ServerPlayer p) { p.removeData(ModAttachments.CRUSH); }
    private CrushExecutor() {}
}
