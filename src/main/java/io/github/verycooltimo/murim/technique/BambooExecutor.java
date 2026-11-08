package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.*;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.BambooPayload;
import io.github.verycooltimo.murim.network.ChannelSafe;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Comparator;

/** Server-only cast state. API checked in /tmp/mr-ref-1121/{minecraft-src,neoforge-src}:
 * Entity/LivingEntity, Projectile#setOwner/shoot, EntityGetter#getEntitiesOfClass,
 * CollisionGetter#noCollision, DamageSource#typeHolder, AttributeInstance transient modifiers;
 * LivingIncomingDamageEvent and ProjectileImpactEvent cancellation precedes contact effects.
 * No saved fields: expiry state is transient, clients receive only animation/contact facts.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class BambooExecutor {
    public static final ResourceLocation WEAKNESS = ResourceLocation.fromNamespaceAndPath("murim", "bamboo_pressure_points");
    public static final class Cast {
        public ResourceLocation id;
        public long start;
        public int stars, target = -1, catches, side = 1, hits;
        public boolean spent;
    }
    public static boolean empty(LivingEntity p) { return p.getMainHandItem().isEmpty(); }
    public static void begin(ServerPlayer p, ResourceLocation id, int side) {
        Cast c = new Cast(); c.id = id; c.start = p.level().getGameTime();
        c.stars = Math.max(0, MasteryService.stars(p, id)); c.side = side < 0 ? -1 : 1;
        LivingEntity t = nearest(p);
        if (t != null) c.target = t.getId();
        p.setData(ModAttachments.BAMBOO_HAND, c);
        SwordGuard.hold(p, false);
        QiSword.sheathe(p);
    }
    public static void end(ServerPlayer p) {
        if (p.hasData(ModAttachments.BAMBOO_HAND)) {
            Cast c = p.getData(ModAttachments.BAMBOO_HAND);
            if (c.id != null && c.hits == 0) MasteryService.onMiss(p, c.id);
            p.removeData(ModAttachments.BAMBOO_HAND);
        }
    }
    public static void release(ServerPlayer p) {
        if (p.hasData(ModAttachments.BAMBOO_HAND)
                && BambooRules.form(p.getData(ModAttachments.BAMBOO_HAND).id) == 4) TechniqueService.finish(p);
    }
    private static boolean active(ServerPlayer p, Cast c) {
        return c.id != null && empty(p) && p.isAlive() && !Stun.isStunned(p)
                && c.id.equals(p.getData(ModAttachments.TECHNIQUE_STATE).techniqueId());
    }
    public static void tick(ServerPlayer p, int tick) {
        Cast c = p.getData(ModAttachments.BAMBOO_HAND);
        if (!active(p, c)) { TechniqueService.cancel(p); return; }
        int form = BambooRules.form(c.id);
        if (form == 2 && tick == 2) {
            LivingEntity t = target(p, c);
            if (inReach(p, t) && !FragranceAirService.dodgeContact(t)) {
                boolean collapsed = interrupt(t);
                boolean damaged = t.hurt(p.damageSources().playerAttack(p), 2);
                if (collapsed || damaged) {
                    hit(p, c, t, t.getEyePosition());
                    if (collapsed) fact(p, c, t.getId(), t.position().add(0, 1.35, 0), BambooPayload.COLLAPSE);
                }
            }
        } else if (form == 3 && tick == 0) {
            LivingEntity t = target(p, c);
            if (inReach(p, t) && rushing(p, t)) {
                Vec3 side = side(p, c).scale(.8);
                if (!p.level().noCollision(p, p.getBoundingBox().move(side))) return;
                p.connection.teleport(p.getX() + side.x, p.getY(), p.getZ() + side.z, p.getYRot(), p.getXRot());
                Vec3 v = t.getKnownMovement().multiply(1, 0, 1).normalize().scale(.8);
                // 0.46 peaks near one block under Stun gravity; horizontal throw stays 0.8.
                t.setDeltaMovement(v.x, .46, v.z); t.hurtMarked = true;
                int down = Stun.apply(t, 30, 30);
                if (down > 0) ChannelSafe.nearEntity(p, new BambooPayload(p.getId(), t.getId(), c.stars, 2, down, t.position()), true);
                hit(p, c, t, t.position());
            }
        } else if (form == 5 && tick < 12 && tick % 2 == 0) {
            LivingEntity t = target(p, c);
            if (!inReach(p, t)) return;
            // Six contacts in twelve ticks; preserve unrelated vanilla invulnerability windows.
            // duration carries the presentation-only contact index; gameplay timing is unchanged.
            ChannelSafe.nearEntity(p, new BambooPayload(p.getId(), t.getId(), c.stars,
                    BambooPayload.GROVE, tick / 2, t.position()), true);
            if (tick == 10) {
                weaken(t);
                fact(p, c, t.getId(), t.position(), BambooPayload.GROVE_RING);
                if (p.getRandom().nextFloat() < .5F) dropWeapon(t);
                if (c.hits++ == 0) MasteryService.onHit(p, c.id, t);
            }
        }
    }
    public static boolean rushing(LivingEntity p, LivingEntity t) {
        Vec3 toward = p.position().subtract(t.position()).multiply(1, 0, 1).normalize();
        // API: Entity#getKnownMovement and ServerPlayer override; server walking velocity comes from accepted move packets.
        return t.getKnownMovement().dot(toward) > BambooRules.RUSH_SPEED;
    }
    public static boolean interrupt(LivingEntity t) {
        if (t instanceof Casters.Caster caster) return caster.interruptChargingTechnique();
        if (!(t instanceof ServerPlayer p)) return false;
        var s = p.getData(ModAttachments.TECHNIQUE_STATE);
        TechniqueDefinition d = s.isActive() ? TechniqueLoader.get(s.techniqueId()) : null;
        TechniquePhase phase = d == null ? null : d.phaseAt(s.tick());
        boolean charging = phase == TechniquePhase.RITUAL || phase == TechniquePhase.WINDUP;
        boolean sustaining = phase == TechniquePhase.IMPACT && (d.behavior() instanceof TechniqueBehavior.PlumSea
                || BambooRules.form(d.id()) == 4);
        var threads = p.level().getEntitiesOfClass(TangDagger.class, p.getBoundingBox().inflate(48),
                q -> q.getOwner() == p && q.mode() == TangDagger.STAR);
        if (!charging && !sustaining && threads.isEmpty()) return false;
        // An interruption starts the full cooldown now, even after a long gathering phase.
        TechniqueService.cancel(p);
        var cds = new java.util.HashMap<>(p.getData(ModAttachments.COOLDOWNS));
        if (charging || sustaining) restartCooldown(p, cds, d.id());
        for (TangDagger q : threads) {
            if (q.technique != null) restartCooldown(p, cds, q.technique);
            TangExecutor.threadCut(q); q.discard();
        }
        p.setData(ModAttachments.COOLDOWNS, cds);
        return true;
    }
    private static void restartCooldown(ServerPlayer p, java.util.Map<ResourceLocation, Long> cds, ResourceLocation id) {
        cds.put(id, p.level().getGameTime());
        // API: NeoForge ICommonPacketListener#hasChannel via ChannelSafe; fake/vanilla peers have no channel.
        ChannelSafe.send(p,
                new io.github.verycooltimo.murim.network.TechniqueEventPayload(
                        io.github.verycooltimo.murim.network.TechniqueEventPayload.Event.COOLDOWN_RESET, id, p.getId(), 0, 0));
    }
    public static void weaken(LivingEntity t) {
        AttributeInstance a = t.getAttribute(Attributes.ATTACK_DAMAGE);
        if (a == null) return;
        a.removeModifier(WEAKNESS);
        a.addTransientModifier(new AttributeModifier(WEAKNESS, -.30, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        t.setData(ModAttachments.BAMBOO_WEAK_END, t.level().getGameTime() + 120);
    }
    public static boolean dropWeapon(LivingEntity t) {
        if (!(t instanceof Mob) || t.getType().is(Tags.EntityTypes.BOSSES) || t.getMainHandItem().isEmpty()) return false;
        if (t.spawnAtLocation(t.getMainHandItem().copy()) == null) return false;
        t.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        return true;
    }
    private static Vec3 side(ServerPlayer p, Cast c) {
        return p.getLookAngle().multiply(1, 0, 1).normalize().cross(new Vec3(0, 1, 0)).scale(c.side);
    }
    private static LivingEntity nearest(ServerPlayer p) {
        return p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(2.5),
                t -> inReach(p, t)).stream().min(Comparator.comparingDouble(p::distanceToSqr)).orElse(null);
    }
    private static boolean inReach(ServerPlayer p, LivingEntity t) {
        return t != null && t != p && t.isAlive() && !t.isAlliedTo(p) && !t.isSpectator()
                && (!(t instanceof ServerPlayer other) || io.github.verycooltimo.murim.sect.SectDuel.permitsContact(p, other))
                && p.distanceToSqr(t) <= 6.25 && SwordGuard.frontal(p, t.position()) && p.hasLineOfSight(t);
    }
    private static LivingEntity target(ServerPlayer p, Cast c) {
        return p.level().getEntity(c.target) instanceof LivingEntity t ? t : null;
    }
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void incoming(LivingIncomingDamageEvent e) {
        if (e.getEntity().level().isClientSide()) return;
        if (melee(e.getSource()) && e.getSource().getDirectEntity() instanceof LivingEntity attacker && attackLocked(attacker)) {
            e.setCanceled(true); return;
        }
        if (!(e.getEntity() instanceof ServerPlayer p) || !p.hasData(ModAttachments.BAMBOO_HAND)) return;
        Cast c = p.getData(ModAttachments.BAMBOO_HAND);
        int form = BambooRules.form(c.id);
        if (!active(p, c) || c.spent || form > 1 || form < 0 || c.stars < BambooRules.UNLOCK[form]
                || p.level().getGameTime() - c.start >= BambooRules.window(c.stars)) return;
        Entity direct = e.getSource().getDirectEntity();
        boolean melee = melee(e.getSource());
        boolean palm = form == 0 && palmQi(direct);
        if ((!melee && !palm) || !(e.getSource().getEntity() instanceof LivingEntity attacker)
                || !SwordGuard.frontal(p, direct.position())) return;
        e.setCanceled(true); c.spent = true;
        if (palm) direct.discard();
        if (form == 1) {
            int wallStun = Stun.apply(attacker, BambooRules.wallStun(c.stars), 12);
            hit(p, c, attacker, p.getEyePosition());
            // Carry the actual capped duration, presentation only; no new stun state.
            ChannelSafe.nearEntity(p, new BambooPayload(p.getId(), attacker.getId(), c.stars,
                    BambooPayload.WALL, wallStun, p.getEyePosition()), true);
        } else {
            fact(p, c, attacker.getId(), attacker.position().add(0, 1.2, 0), BambooPayload.ASIDE);
            Vec3 direction = side(p, c);
            LivingEntity neighbour = p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(2.5),
                    t -> t != p && t != attacker && t.isAlive() && !t.isAlliedTo(p) && !t.isSpectator()
                            && p.distanceToSqr(t) <= 6.25 && p.hasLineOfSight(t)
                            && t.position().subtract(p.position()).normalize().dot(direction) > .5)
                    .stream().min(Comparator.comparingDouble(p::distanceToSqr)).orElse(null);
            // Use the original type with the defender as the redirecting owner. Mark spent BEFORE nested hurt.
            if (neighbour != null) neighbour.hurt(new DamageSource(e.getSource().typeHolder(), p, p), e.getAmount());
            Vec3 at = neighbour == null ? p.position().add(direction.scale(1.4)) : neighbour.position();
            hit(p, c, neighbour == null ? attacker : neighbour, at);
            if (neighbour == null) fact(p, c, -1, at, 1);
            attacker.setData(ModAttachments.BAMBOO_ATTACK_END, attacker.level().getGameTime() + 20);
        }
    }
    private static boolean melee(DamageSource source) {
        return source.getDirectEntity() instanceof LivingEntity && source.getDirectEntity() == source.getEntity()
                && (source.is(DamageTypes.MOB_ATTACK) || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)
                || source.is(DamageTypes.PLAYER_ATTACK));
    }
    /** Only explicitly palm/fist qi qualifies. Sword wedges, magic and explosions never do. */
    private static boolean palmQi(Entity projectile) {
        if (!(projectile instanceof WedgeProjectile qi) || qi.technique == null) return false;
        TechniqueDefinition d = TechniqueLoader.get(qi.technique);
        return d != null && (d.behavior() instanceof TechniqueBehavior.PalmBlast
                || "palm".equals(d.form()) || "fist".equals(d.form()));
    }
    public static boolean catchProjectile(ServerPlayer p, Projectile q) {
        if (p.level().isClientSide() || !p.hasData(ModAttachments.BAMBOO_HAND) || q.getOwner() == p) return false;
        Cast c = p.getData(ModAttachments.BAMBOO_HAND);
        if (!active(p, c) || BambooRules.form(c.id) != 4 || p.level().getGameTime() - c.start >= 20
                || c.catches >= BambooRules.catches(c.stars) || !(q instanceof AbstractArrow || q instanceof TangDagger)
                || !SwordGuard.frontal(p, q.position())
                || q.getDeltaMovement().dot(p.getEyePosition().subtract(q.position())) <= 0) return false;
        ++c.catches;
        Entity previousOwner = q.getOwner();
        Vec3 v = q.getDeltaMovement().scale(-1);
        q.setOwner(p);
        q.shoot(v.x, v.y, v.z, (float) v.length(), 0);
        q.hurtMarked = true;
        if (q instanceof TangDagger dagger) dagger.bambooReturn(v);
        hit(p, c, previousOwner instanceof LivingEntity owner ? owner : p, q.position());
        fact(p, c, q.getId(), q.position(), BambooPayload.RETURN);
        return true;
    }
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void projectile(ProjectileImpactEvent e) {
        if (e.getRayTraceResult() instanceof EntityHitResult hit && hit.getEntity() instanceof ServerPlayer p
                && catchProjectile(p, e.getProjectile())) e.setCanceled(true);
    }
    private static void hit(ServerPlayer p, Cast c, LivingEntity t, Vec3 at) {
        if (c.hits++ == 0) MasteryService.onHit(p, c.id, t == null ? p : t);
        int form = BambooRules.form(c.id);
        // Turning, Wall and returns send their own reaction facts (Wall includes its capped stun duration).
        if (form == 0 || form == 1 || form == 4) return;
        int kind = form == 2 ? BambooPayload.JAB : BambooPayload.CONTACT;
        fact(p, c, t == null ? -1 : t.getId(), at, kind);
    }
    private static void fact(ServerPlayer p, Cast c, int target, Vec3 at, int kind) {
        ChannelSafe.nearEntity(p, new BambooPayload(p.getId(), target, c.stars, kind, kind == BambooPayload.ASIDE ? c.side : 0, at), true);
    }
    private static boolean attackLocked(LivingEntity t) {
        return t.hasData(ModAttachments.BAMBOO_ATTACK_END) && t.level().getGameTime() < t.getData(ModAttachments.BAMBOO_ATTACK_END);
    }
    @SubscribeEvent public static void attack(AttackEntityEvent e) {
        if (!e.getEntity().level().isClientSide() && attackLocked(e.getEntity())) e.setCanceled(true);
    }
    /** Off-balance prevents a new ranged release without freezing movement or old projectiles.
     * API: neoforge-src/.../event/entity/EntityJoinLevelEvent.java and LivingEntityUseItemEvent.Start.
     */
    @SubscribeEvent public static void launch(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) {
        if (!e.getLevel().isClientSide() && e.getEntity() instanceof Projectile q
                && q.getOwner() instanceof LivingEntity owner && attackLocked(owner)) e.setCanceled(true);
    }
    @SubscribeEvent public static void use(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Start e) {
        if (!e.getEntity().level().isClientSide() && attackLocked(e.getEntity())
                && e.getItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem) e.setCanceled(true);
    }
    @SubscribeEvent public static void expiry(EntityTickEvent.Pre e) {
        if (!(e.getEntity() instanceof LivingEntity t) || t.level().isClientSide()) return;
        if (t.hasData(ModAttachments.BAMBOO_WEAK_END) && t.level().getGameTime() >= t.getData(ModAttachments.BAMBOO_WEAK_END)) {
            var a = t.getAttribute(Attributes.ATTACK_DAMAGE);
            if (a != null) a.removeModifier(WEAKNESS);
            t.removeData(ModAttachments.BAMBOO_WEAK_END);
        }
        if (t.hasData(ModAttachments.BAMBOO_ATTACK_END) && !attackLocked(t)) t.removeData(ModAttachments.BAMBOO_ATTACK_END);
    }
    private BambooExecutor() { }
}
