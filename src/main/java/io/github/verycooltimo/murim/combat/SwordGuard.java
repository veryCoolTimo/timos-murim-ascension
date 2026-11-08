package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.BanditSwordsman;
import io.github.verycooltimo.murim.network.ChannelSafe;
import io.github.verycooltimo.murim.network.SwordGuardPayloads;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.TangDagger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Server rules: docs/design/research/combat-block-parry-1021.md, variant A + author's salvo parry.
 * API verified against /tmp/NeoForge-1.21.1/src/main/java/.../event/entity/living and
 * patches/net/minecraft/world/entity/LivingEntity.java.patch (incoming cancellation skips vanilla KB).
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SwordGuard {
    /** Shared client/server hand policy. Source: docs/design/mvp-fun-1101.md, R4.
     * Item#getUseAnimation(ItemStack): existing item/MealBowlItem API path.
     * Plain inert items (sticks, ingots) permit guard. Specialized items reserve use conservatively,
     * including instant-use mod items and buckets with no animation.
     */
    public static boolean handsAllowGuard(net.minecraft.world.item.ItemStack main,
                                          net.minecraft.world.item.ItemStack off, boolean vanilla) {
        if (!off.isEmpty() && (off.getItem().getClass() != net.minecraft.world.item.Item.class
                || off.getItem().getUseAnimation(off) != net.minecraft.world.item.UseAnim.NONE)) return false;
        if (!QiSword.holdsSword(main)) return false;
        String namespace = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(main.getItem()).getNamespace();
        return namespace.equals(MurimMod.MODID) || vanilla && namespace.equals("minecraft");
    }
    public static boolean handsAllowGuard(LivingEntity e) {
        return handsAllowGuard(e.getMainHandItem(), e.getOffhandItem(),
                io.github.verycooltimo.murim.Config.SWORD_GUARD_VANILLA_SWORDS.get());
    }
    public static SwordGuardState state(LivingEntity entity) { return entity.getData(ModAttachments.SWORD_GUARD); }
    public static boolean eligible(LivingEntity e) {
        return e.isAlive() && !Stun.isStunned(e) && !FootworkService.exposed(e) && (e instanceof BanditSwordsman || handsAllowGuard(e))
                && (!(e instanceof ServerPlayer p) || (!p.isSpectator()
                && !p.getData(ModAttachments.TECHNIQUE_STATE).isActive()
                && !p.getData(ModAttachments.MEDITATION).active()
                && !io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.pinned(p)));
    }
    public static void swing(LivingEntity e) {
        if (e instanceof ServerPlayer p && !e.level().isClientSide && io.github.verycooltimo.murim.sect.SectBladeExam.flinch(p)) return;
        if (!eligible(e) || e.level().isClientSide) return;
        if (state(e).swing == e.level().getGameTime()) return;
        int recovery = e instanceof ServerPlayer p ? (int) Math.ceil(p.getCurrentItemAttackStrengthDelay()) : 12;
        state(e).swing(e.level().getGameTime(), recovery);
        sync(e);
    }
    public static void input(ServerPlayer p, int action) {
        if (action == 0) { hold(p, false); return; }
        if (!eligible(p)) return;
        if (action == 1) hold(p, true);
        // Ordinary air swings: throttle to a real sword cycle; style swings use their accepted path.
        if (action == 2 && !FoundationService.active(p).filter(SwordBasicCombo::isBasic).isPresent()
                && (p.level().getGameTime() >= state(p).ready || state(p).swing == p.level().getGameTime())) {
            swing(p);
            p.resetAttackStrengthTicker(); // A miss spends the ordinary attack cycle too.
        }
    }
    public static void hold(LivingEntity e, boolean down) {
        if (e.level().isClientSide) return;
        if (down && e instanceof ServerPlayer p && io.github.verycooltimo.murim.sect.SectBladeExam.flinch(p)) return;
        state(e).hold(down && eligible(e), e.level().getGameTime());
        if (state(e).blocking(e.level().getGameTime())) e.setSprinting(false);
        sync(e);
    }
    public static void breakGuard(LivingEntity e, int ticks) {
        state(e).brokenUntil = e.level().getGameTime() + ticks;
        state(e).hold(false, e.level().getGameTime());
        sync(e);
    }
    public static boolean blocking(LivingEntity e) {
        return eligible(e) && (!(e instanceof BanditSwordsman b)
                || b.state() == io.github.verycooltimo.murim.entity.Bandit.IDLE
                || b.state() == io.github.verycooltimo.murim.entity.Bandit.RECOVER)
                && state(e).blocking(e.level().getGameTime());
    }
    public static boolean frontal(LivingEntity e, Vec3 origin) {
        Vec3 delta = origin.subtract(e.position()).multiply(1, 0, 1);
        Vec3 look = e.getLookAngle().multiply(1, 0, 1);
        return delta.lengthSqr() > 1.0E-8 && look.lengthSqr() > 1.0E-8
                && look.normalize().dot(delta.normalize()) >= 0.5D; // 120 degrees total.
    }
    private static boolean supported(Entity e) { return e instanceof AbstractArrow || e instanceof TangDagger; }
    private static boolean hostile(LivingEntity defender, Entity projectile) {
        if (!(projectile instanceof net.minecraft.world.entity.projectile.Projectile p)) return false;
        Entity owner = p.getOwner();
        if (owner == defender || (owner != null && defender.isAlliedTo(owner))) return false;
        return !(defender instanceof ServerPlayer target && owner instanceof ServerPlayer attacker)
                || attacker.canHarmPlayer(target);
    }
    /** Existing techniques sometimes credit area damage as playerAttack; keep that attribution,
     * but explicitly exclude the area contact from sword defense. Scoped per victim, never static. */
    public static boolean unguarded(LivingEntity victim, java.util.function.BooleanSupplier contact) {
        var s = state(victim);
        s.areaDepth++;
        try { return contact.getAsBoolean(); }
        finally { s.areaDepth--; }
    }
    private static boolean direct(DamageSource source) {
        // Whitelist melee damage IDs: indirect magic and ground/explosion techniques stay unguardable.
        String id = source.getMsgId();
        return (id.equals("player") || id.equals("mob")) && source.getDirectEntity() instanceof LivingEntity;
    }
    public static boolean parryProjectile(LivingEntity e, Entity projectile) {
        if (e.level().isClientSide || !blocking(e) || !supported(projectile) || !hostile(e, projectile) || !frontal(e, projectile.position())) return false;
        long now = e.level().getGameTime();
        var s = state(e);
        if (!s.deflecting(now) && !s.exact(now)) return false;
        Vec3 delta = projectile.position().subtract(e.position());
        parry(e, projectile, s.exact(now));
        e.knockback(0.1D, delta.x, delta.z);
        e.hurtMarked = true; // Cancellation skips hurt's normal velocity synchronization.
        return true;
    }
    private static void parry(LivingEntity e, Entity incoming, boolean salvo) {
        long now = e.level().getGameTime();
        state(e).consume(now);
        state(e).knockbackScale = 1;
        if (!net.neoforged.fml.loading.FMLEnvironment.production && Boolean.getBoolean("murim.capture")) {
            io.github.verycooltimo.murim.MurimMod.LOGGER.info("Parry: {} parried {} ({})", e.getName().getString(),
                    incoming.getType().getDescriptionId(), salvo ? "exact" : "after a swing");
        }
        // A bout of the sect counts the strikes its partner had parried (the trial "win by a parry").
        if (e instanceof ServerPlayer sp) io.github.verycooltimo.murim.sect.SectBouts.onParry(sp, incoming);
        if (supported(incoming)) incoming.discard();
        if (salvo) {
            // One contact sweeps the approaching frontal volley, never weapons behind the defender.
            for (Entity other : e.level().getEntities(e, e.getBoundingBox().inflate(2.5D), SwordGuard::supported)) {
                if (supported(other) && hostile(e, other) && e.hasLineOfSight(other) && frontal(e, other.position())
                        && other.getDeltaMovement().dot(e.position().subtract(other.position())) > 0) other.discard();
            }
        }
        e.level().playSound(null, e.getX(), e.getY(), e.getZ(), net.minecraft.sounds.SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.25F, 1.9F);
        ChannelSafe.nearEntity(e, new SwordGuardPayloads.View(e.getId(), 3), true);
        sync(e);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void incoming(LivingIncomingDamageEvent event) {
        if (FootworkService.timedDodge(event)) return; // A failed Weight Shift takes the full melee contact.
        if (event.isCanceled()) return;
        if (io.github.verycooltimo.murim.technique.SixExecutor.tryCounter(event)) return;
        LivingEntity e = event.getEntity();
        if (e.level().isClientSide || !e.hasData(ModAttachments.SWORD_GUARD)) return;
        var s = state(e);
        s.knockbackScale = 1; // Every new contact replaces the previous decision, including rear hits.
        Entity incoming = event.getSource().getDirectEntity();
        if (s.areaDepth > 0 || event.getAmount() <= 0 || incoming == null || !blocking(e)
                || (!supported(incoming) && !direct(event.getSource())) || !frontal(e, incoming.position())) return;
        long now = e.level().getGameTime();
        boolean exact = s.exact(now);
        if (exact || s.deflecting(now)) {
            Vec3 delta = incoming.position().subtract(e.position());
            // Cancel all secondary on-hit effects; replace the cancelled contact's impulse once.
            event.setCanceled(true);
            parry(e, incoming, exact);
            e.knockback(0.1D, delta.x, delta.z);
            // Preserve the order of vanilla's base and sprint/NPC impulses (each halves old velocity).
            if (incoming instanceof ServerPlayer attacker && attacker.isSprinting())
                e.knockback(0.125D, delta.x, delta.z);
            if (incoming instanceof BanditSwordsman bandit)
                e.knockback((bandit.move() == io.github.verycooltimo.murim.entity.BanditMove.CHOP ? 0.3D : 0.45D) * 0.25D,
                        delta.x, delta.z);
            e.hurtMarked = true;
        } else {
            event.setAmount(event.getAmount() * 0.5F);
            s.knockbackTick = now;
            s.knockbackScale = 0.75F;
            s.knockbackX = incoming.getX() - e.getX();
            s.knockbackZ = incoming.getZ() - e.getZ();
            e.level().playSound(null, e.getX(), e.getY(), e.getZ(), net.minecraft.sounds.SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 0.45F, 0.75F);
        }
    }
    // API: /tmp/NeoForge-1.21.1/.../event/entity/ProjectileImpactEvent.java.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void projectile(net.neoforged.neoforge.event.entity.ProjectileImpactEvent event) {
        if (!event.getProjectile().level().isClientSide
                && event.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult hit
                && hit.getEntity() instanceof LivingEntity defender
                && parryProjectile(defender, event.getProjectile())) event.setCanceled(true);
    }
    @SubscribeEvent
    static void knockback(LivingKnockBackEvent event) {
        LivingEntity e = event.getEntity();
        if (e.level().isClientSide || !e.hasData(ModAttachments.SWORD_GUARD)) return;
        var s = state(e);
        // Cover vanilla hurt + sprint/NPC follow-up impulses; don't scale a different direction.
        double dot = event.getRatioX() * s.knockbackX + event.getRatioZ() * s.knockbackZ;
        if (s.knockbackTick == e.level().getGameTime() && dot > 0)
            event.setStrength(event.getStrength() * s.knockbackScale);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void attack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && eligible(p)) {
            var pending = p.getData(ModAttachments.BASIC_COMBO);
            // Delayed style contact must not restart its own windup/guard timer.
            if (!SwordBasicCombo.isBasic(pending.basic()) || p.level().getGameTime() - pending.lastTick() > 2) swing(p);
        }
    }
    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            if (!eligible(p)) hold(p, false);
            if (blocking(p)) p.setSprinting(false);
            sync(p);
        }
    }
    private static void reset(ServerPlayer p) {
        p.setData(ModAttachments.SWORD_GUARD, new SwordGuardState());
        ChannelSafe.nearEntity(p, new SwordGuardPayloads.View(p.getId(), 4), true);
    }
    @SubscribeEvent
    static void dimension(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) reset(p);
    }
    @SubscribeEvent
    static void death(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) reset(p);
    }
    @SubscribeEvent
    static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) p.setData(ModAttachments.SWORD_GUARD, new SwordGuardState());
    }
    public static void sync(LivingEntity e) {
        var s = state(e);
        long now = e.level().getGameTime();
        int view = !blocking(e) ? 0 : s.deflecting(now) ? 2 : 1;
        if (view != s.shown || e.tickCount % 20 == 0) {
            s.shown = view;
            ChannelSafe.nearEntity(e, new SwordGuardPayloads.View(e.getId(), view), true);
        }
    }
    private SwordGuard() {}
}
