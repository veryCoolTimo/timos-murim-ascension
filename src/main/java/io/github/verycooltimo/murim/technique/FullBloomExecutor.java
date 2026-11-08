package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TargetLock;
import io.github.verycooltimo.murim.combat.TechniqueService;
import io.github.verycooltimo.murim.network.BloomPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Chapter 296. All mutable facts live on the caster; BloomPayload carries only the visible front.
 * API verified in /tmp/mr-ref-1121/{minecraft-src,neoforge-src}: Entity, EntityGetter,
 * BlockGetter#clip, AABB#clip, DamageSource, LivingIncomingDamageEvent, PacketDistributor.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class FullBloomExecutor {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "twenty_four_plum_full_bloom");
    public static final int MAX_HOLD = 110, PASS_TICKS = 6;
    public static final double WIDTH = 0.85D;

    public static final class Cast {
        public Vec3 root = Vec3.ZERO, origin = Vec3.ZERO, direction = Vec3.ZERO;
        public int target = -1, tier, release = -1;
        public double reached, reserve, maximum;
        public boolean active, held = true, rushPaid;
    }

    public static void begin(ServerPlayer player, ResourceLocation id) {
        Cast c = new Cast();
        c.active = true;
        c.root = player.position();
        c.origin = RiverExecutor.hand(player);
        c.tier = Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.executionTier(player, id));
        LivingEntity target = TargetLock.locked(player, range(c.tier));
        if (target != null && !permitsContact(player, target)) target = null;
        if (target == null) {
            double best = range(c.tier);
            for (LivingEntity e : player.serverLevel().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(best),
                    e -> e != player && permitsContact(player, e) && e.isAlive() && !e.isSpectator() && !(e instanceof ArmorStand))) {
                double distance = e.distanceTo(player);
                if (distance < best && TargetLock.inCone(player, e, 30.0D) && player.hasLineOfSight(e)) {
                    target = e;
                    best = distance;
                }
            }
        }
        c.direction = target == null ? player.getLookAngle() : TargetLock.centre(target).subtract(c.origin).normalize();
        if (target != null && player.hasLineOfSight(target)) c.target = target.getId();
        c.maximum = c.reserve = 12.0D + TechniqueDamage.base(player, id) * (2.0D + c.tier * 0.25D);
        player.setData(ModAttachments.FULL_BLOOM, c);
        advance(player, c); // The front starts at the sword immediately, without a windup wall.
        send(player, c, BloomPayload.HOLD);
    }

    private static boolean permitsContact(ServerPlayer player, net.minecraft.world.entity.Entity target) {
        return !(target instanceof ServerPlayer victim) || io.github.verycooltimo.murim.sect.SectDuel.permitsContact(player, victim);
    }

    public static double range(int tier) { return 6.0D + Math.min(8, Math.max(0, tier)) * 0.25D; }

    public static void hold(ServerPlayer player, boolean held) {
        Cast c = player.getData(ModAttachments.FULL_BLOOM);
        if (c.active && ID.equals(player.getData(ModAttachments.TECHNIQUE_STATE).techniqueId())) c.held = held;
    }

    public static boolean tick(ServerPlayer player, ResourceLocation id, int tick) {
        Cast c = player.getData(ModAttachments.FULL_BLOOM);
        if (!c.active) return false;
        if (c.release >= 0) {
            if (tick >= c.release + PASS_TICKS) {
                TechniqueService.finish(player);
                return true;
            }
            return false;
        }
        // Server authority: movement packets cannot walk the master out of the stance.
        player.teleportTo(c.root.x, c.root.y, c.root.z);
        player.setDeltaMovement(Vec3.ZERO);
        if ((!c.held && tick >= 2) || tick >= MAX_HOLD) {
            release(player, c, tick);
            return false;
        }
        advance(player, c);
        if (!c.active) return true;
        send(player, c, BloomPayload.HOLD);
        return false;
    }

    private static void advance(ServerPlayer player, Cast c) {
        Vec3 wanted = c.origin.add(c.direction.scale(Math.min(range(c.tier), c.reached + 0.4D)));
        var wall = player.level().clip(new ClipContext(c.origin, wanted, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        c.reached = Math.min(range(c.tier), c.origin.distanceTo(wall.getType() == HitResult.Type.MISS ? wanted : wall.getLocation()));
        Vec3 end = c.origin.add(c.direction.scale(c.reached));
        // Only the selected foe's projectiles meet this front. Other foes can flank it.
        for (Projectile p : player.serverLevel().getEntitiesOfClass(Projectile.class, new AABB(c.origin, end).inflate(3.0D),
                p -> p.isAlive() && p.getOwner() != player && p.getOwner() != null && p.getOwner().getId() == c.target && permitsContact(player, p.getOwner()))) {
            Vec3 previous = new Vec3(p.xo, p.yo, p.zo);
            if (touches(c, previous, p.position()) || touches(c, p.position(), p.position().add(p.getDeltaMovement()))) {
                double cost = p instanceof AbstractArrow a ? a.getBaseDamage() * Math.max(1.0D, p.getDeltaMovement().length())
                        : p instanceof TangDagger dagger ? dagger.damage
                        : p instanceof WedgeProjectile wave ? wave.clashStrength() : 8.0D;
                // An overpowered projectile survives the clash and continues toward the master.
                if (absorbVisual(player, c, cost, p.position()) <= 0.0D) p.discard();
                if (!c.active) return;
            }
        }
        if (!(player.serverLevel().getEntity(c.target) instanceof LivingEntity foe) || !foe.isAlive()
                || !permitsContact(player, foe) || !inReached(c, TargetLock.centre(foe)) || !player.hasLineOfSight(foe)) return;
        Vec3 velocity = foe.getDeltaMovement();
        double inward = -velocity.dot(c.direction);
        if (inward > 0.35D && !c.rushPaid) {
            c.rushPaid = true;
            double strength = (foe.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) == null ? 0.0D : TechniqueDamage.base(foe, ID)) * (1.0D + inward * 2.0D);
            if (absorbVisual(player, c, strength, foe.position()) > 0.0D) return;
        }
        if (inward < 0.1D) c.rushPaid = false;
        // Steady pressure, with ordinary collision physics. No teleporting the opponent.
        foe.setDeltaMovement(velocity.add(c.direction.scale(Math.max(0.0D, inward) + 0.12D)));
        foe.hurtMarked = true;
    }

    public static boolean inReached(Cast c, Vec3 point) {
        Vec3 offset = point.subtract(c.origin);
        double along = offset.dot(c.direction);
        return along >= 0.0D && along <= c.reached && offset.subtract(c.direction.scale(along)).lengthSqr() <= WIDTH * WIDTH;
    }

    private static boolean touches(Cast c, Vec3 from, Vec3 to) {
        // A short swept segment catches fast projectiles without widening the release's reached area.
        int steps = Math.max(1, (int) Math.ceil(from.distanceTo(to) / 0.2D));
        steps = Math.min(256, steps);
        for (int i = 0; i <= steps; i++) if (inReached(c, from.lerp(to, i / (double) steps))) return true;
        return false;
    }

    /** Presentation-only clash notice; the existing absorb path still owns all reserve arithmetic. */
    private static double absorbVisual(ServerPlayer player, Cast c, double strength, Vec3 attacker) {
        if (strength > 0 && c.reserve > 0 && io.github.verycooltimo.murim.mastery.MasteryService.fullMastery(player, ID)) {
            var flash = new BloomPayload(player.getId(), attacker, c.direction, 0, c.tier, BloomPayload.CLASH, true, io.github.verycooltimo.murim.mastery.MasteryService.stars(player, ID));
            PacketDistributor.sendToPlayersTrackingEntity(player, flash);
            if (player.connection.hasChannel(BloomPayload.TYPE)) PacketDistributor.sendToPlayer(player, flash);
        }
        return absorb(player, c, strength);
    }

    /** Returns unabsorbed strength; exhaustion loses the clash and cannot trigger a release attack. */
    static double absorb(ServerPlayer player, Cast c, double strength) {
        double taken = Math.min(c.reserve, Math.max(0.0D, strength));
        c.reserve -= taken;
        if (c.reserve <= 0.001D) TechniqueService.cancel(player);
        return strength - taken;
    }

    private static void release(ServerPlayer player, Cast c, int tick) {
        c.release = tick;
        send(player, c, BloomPayload.RELEASE);
        boolean hit = false;
        if (player.serverLevel().getEntity(c.target) instanceof LivingEntity foe && foe.isAlive()
                && permitsContact(player, foe) && inReached(c, TargetLock.centre(foe)) && player.hasLineOfSight(foe)) {
            // Normal damage pipeline owns spar strength and fainting; never force a knockout here.
            if (foe.hurt(player.damageSources().playerAttack(player), (float) (TechniqueDamage.base(player, ID) * 6.0D))) {
                // full-19: destination flare/spray only after actual damage; no gameplay changes.
                // API: /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/network/PacketDistributor.java
                // #sendToPlayersTrackingEntity/#sendToPlayer; common/extensions/ICommonPacketListener.java#hasChannel.
                var flash = new BloomPayload(player.getId(), TargetLock.centre(foe), c.direction,
                        0, c.tier, BloomPayload.HIT, io.github.verycooltimo.murim.mastery.MasteryService.fullMastery(player, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum_full_bloom")), io.github.verycooltimo.murim.mastery.MasteryService.stars(player, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum_full_bloom")));
                PacketDistributor.sendToPlayersTrackingEntity(player, flash);
                if (player.connection.hasChannel(BloomPayload.TYPE)) PacketDistributor.sendToPlayer(player, flash);
                hit = true;
                io.github.verycooltimo.murim.mastery.MasteryService.onHit(player, ID, foe);
                TechniqueService.onSwordHit(player, ID);
            }
        }
        if (!hit) io.github.verycooltimo.murim.mastery.MasteryService.onMiss(player, ID);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void incoming(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Cast c = player.getData(ModAttachments.FULL_BLOOM);
        var attacker = event.getSource().getEntity();
        if (!c.active || c.release >= 0 || attacker == null || attacker.getId() != c.target
                || !ID.equals(player.getData(ModAttachments.TECHNIQUE_STATE).techniqueId())
                || c.reached <= 0.0D || !player.hasLineOfSight(attacker)) return;
        Vec3 toward = attacker.position().subtract(c.origin).normalize();
        if (toward.dot(c.direction) < 0.7D) return;
        double remaining = absorbVisual(player, c, event.getAmount(), attacker.position());
        if (remaining <= 0.001D) event.setCanceled(true);
        else event.setAmount((float) remaining);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void attack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && p.getData(ModAttachments.FULL_BLOOM).active) event.setCanceled(true);
    }

    public static void end(ServerPlayer player) {
        Cast c = player.getData(ModAttachments.FULL_BLOOM);
        if (!c.active) return;
        send(player, c, BloomPayload.END);
        c.active = false;
        player.setData(ModAttachments.FULL_BLOOM, new Cast());
    }

    private static void send(ServerPlayer p, Cast c, int stage) {
        // Tier zero has input/animation synchronization but the client creates no VFX.
        var payload = new BloomPayload(p.getId(), c.origin, c.direction, (float) c.reached, c.tier, stage, io.github.verycooltimo.murim.mastery.MasteryService.fullMastery(p, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum_full_bloom")), io.github.verycooltimo.murim.mastery.MasteryService.stars(p, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum_full_bloom")));
        // API: NeoForge PacketDistributor#sendToPlayersTrackingEntity excludes self;
        // ICommonPacketListener#hasChannel checks negotiation (GameTest mock clients have none).
        PacketDistributor.sendToPlayersTrackingEntity(p, payload);
        if (p.connection.hasChannel(BloomPayload.TYPE)) PacketDistributor.sendToPlayer(p, payload);
    }
    private FullBloomExecutor() {}
}
