package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Author-approved 3 November air forms. Authority lives on the existing transient attachment.
 * API checked in /tmp/mr-ref-1121/{minecraft-src,neoforge-src}: Entity#move/setDeltaMovement,
 * LivingEntity#getAttribute, AttributeInstance#addTransientModifier/removeModifier,
 * Attributes#GRAVITY/SAFE_FALL_DISTANCE, DamageSource#is/getDirectEntity/getEntity,
 * ProjectileImpactEvent (cancellation preserves flight), LivingIncomingDamageEvent (cancellable),
 * PacketDistributor#sendToPlayersTrackingEntityAndSelf. No source files are bundled here.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class FragranceAirService {
    public static final ResourceLocation RISING = id("rising"), FLOATING = id("floating"), RAIN = id("rain");
    private static final ResourceLocation GRAVITY = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "fragrance_gravity");
    private static final ResourceLocation FALL = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "fragrance_fall");
    public static final double GLIDE_QI_PER_SECOND = 2;

    public static final class State {
        ResourceLocation form;
        int left, dodges, stars;
        double originY, topY;
        long until;
        final java.util.Map<java.util.UUID, Long> passed = new java.util.HashMap<>();
        boolean safeLanding, airborne, prepaid;
    }
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "dark_fragrance_" + name); }
    public static boolean isForm(ResourceLocation id) { return RISING.equals(id) || FLOATING.equals(id) || RAIN.equals(id); }
    public static double height(int stars) { return 3 + 5.0 * (Math.clamp(stars, 1, 12) - 1) / 11; }
    public static int glideTicks(int stars) { return 40 + Math.round(160f * (Math.clamp(stars, 1, 12) - 1) / 11); }
    public static int dodgeCount(int stars) { return 2 + Math.round(8f * (Math.clamp(stars, 1, 12) - 1) / 11); }
    static State state(ServerPlayer p) { return p.getData(ModAttachments.WIND_GOD).fragrance; }

    static void request(ServerPlayer p, ResourceLocation form) { requestResult(p, form); }

    /** Null means accepted (including toggle OFF); otherwise the authoritative refusal. */
    public static String requestResult(ServerPlayer p, ResourceLocation form) {
        if (io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.pinned(p)) return "pinned";
        State s = state(p);
        if (FLOATING.equals(form) && FLOATING.equals(s.form)) { stop(p); return null; }
        if (!isForm(form)) return "not_air_form";
        if (!FootworkService.selected(p, form)) return "unselected_footwork";
        if (!MasteryService.knows(p, form)) return "not_learned";
        if (!FootworkService.canSustain(p)) return "cannot_sustain";
        if (Stun.isStunned(p)) return "stunned";
        if (s.form != null || FootworkService.active(p)) return "active_footwork";
        if (RISING.equals(form) && !p.onGround()) return "requires_ground";
        if (FLOATING.equals(form) && p.onGround()) return "requires_airborne";
        long now = p.serverLevel().getGameTime();
        Long last = p.getData(ModAttachments.COOLDOWNS).get(form);
        // Gliding can always be toggled back on after an ordinary jump.
        if (!FLOATING.equals(form) && last != null && now - last < 30) return "cooldown";
        double cost = FLOATING.equals(form) ? GLIDE_QI_PER_SECOND / 20 : 3;
        if (!FootworkService.pay(p, cost * io.github.verycooltimo.murim.cultivation.PillService.costFactor(p))) return "no_qi";
        s.form = form; s.stars = MasteryService.stars(p, form);
        s.left = FLOATING.equals(form) ? glideTicks(s.stars) : 20;
        s.until = now + s.left; s.prepaid = FLOATING.equals(form);
        s.dodges = dodgeCount(s.stars); s.originY = p.getY(); s.topY = p.getY();
        s.airborne = !p.onGround();
        var cds = new java.util.HashMap<>(p.getData(ModAttachments.COOLDOWNS));
        cds.put(form, now); p.setData(ModAttachments.COOLDOWNS, cds);
        if (!RAIN.equals(form)) {
            modifier(p, Attributes.GRAVITY, GRAVITY, RISING.equals(form) ? -1 : -.88, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            if (FLOATING.equals(form)) {
                s.safeLanding = true;
                modifier(p, Attributes.SAFE_FALL_DISTANCE, FALL, 1024, AttributeModifier.Operation.ADD_VALUE);
                p.fallDistance = 0;
                p.setDeltaMovement(p.getDeltaMovement().x, -.08, p.getDeltaMovement().z);
                p.hurtMarked = true;
            }
        }
        if (RISING.equals(form)) io.github.verycooltimo.murim.network.ChannelSafe.send(p,
                new io.github.verycooltimo.murim.network.TraversePayloads.SevenRoute(
                        java.util.List.of(p.position(), p.position().add(0, height(s.stars), 0)), 20, -1, now));
        event(p, TechniqueEventPayload.Event.STARTED);
        io.github.verycooltimo.murim.sect.SectBouts.onCast(p);
        MasteryService.onMiss(p, form);
        return null;
    }

    /** Capture release must never turn an expired or rejected glide ON. */
    public static String releaseResult(ServerPlayer p) {
        if (!FLOATING.equals(state(p).form)) return "floating_not_active";
        return requestResult(p, FLOATING);
    }

    static void tick(ServerPlayer p) {
        State s = state(p);
        s.passed.values().removeIf(until -> until <= p.serverLevel().getGameTime());
        if (s.safeLanding && p.onGround() && s.airborne && !RISING.equals(s.form)) {
            remove(p, Attributes.SAFE_FALL_DISTANCE, FALL); s.safeLanding = false;
        }
        if (!p.onGround()) s.airborne = true;
        if (s.form == null) return;
        if (!FootworkService.selected(p, s.form) || !FootworkService.canSustain(p) || Stun.isStunned(p)) { stop(p); return; }
        if (s.left <= 0 || p.serverLevel().getGameTime() >= s.until || (FLOATING.equals(s.form) && p.onGround())) { stop(p); return; }
        if (RISING.equals(s.form)) {
            double dy = Math.max(0, s.originY + height(s.stars) - p.getY());
            double step = Math.min(height(s.stars) / 20, dy);
            double before = p.getY();
            p.move(MoverType.SELF, new Vec3(0, step, 0));
            s.topY = Math.max(s.topY, p.getY());
            s.safeLanding = true; s.airborne = true;
            modifier(p, Attributes.SAFE_FALL_DISTANCE, FALL, s.topY - s.originY + 1, AttributeModifier.Operation.ADD_VALUE);
            p.setDeltaMovement(0, 0, 0);
            if (p.getY() - before < step - 1e-5) {
                FootworkCorrections.blocked(p);
                stop(p); return;
            }
            if (dy <= step + 1e-5) { stop(p); return; }
        } else if (FLOATING.equals(s.form)) {
            if (!s.prepaid && !FootworkService.pay(p, GLIDE_QI_PER_SECOND / 20
                    * io.github.verycooltimo.murim.cultivation.PillService.costFactor(p))) { stop(p); return; }
            s.prepaid = false;
            // Vanilla WASD travel remains in charge of horizontal acceleration. Always descend.
            Vec3 v = p.getDeltaMovement();
            p.setDeltaMovement(v.x, -.08, v.z); p.hurtMarked = true; p.fallDistance = 0;
        }
        if (--s.left == 0) stop(p);
    }
    public static void attacking(ServerPlayer p) { if (FLOATING.equals(state(p).form)) stop(p); }
    private static void stop(ServerPlayer p) {
        State s = state(p);
        if (s.form != null) event(p, TechniqueEventPayload.Event.CANCELLED);
        remove(p, Attributes.GRAVITY, GRAVITY);
        if (FLOATING.equals(s.form)) {
            p.fallDistance = 0;
            remove(p, Attributes.SAFE_FALL_DISTANCE, FALL); s.safeLanding = false;
        }
        s.form = null; s.left = s.dodges = 0;
    }
    static void selectionChanged(ServerPlayer p) {
        State s = state(p);
        if (s.form != null && !FootworkService.selected(p, s.form)) stop(p);
    }
    @SubscribeEvent
    static void death(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) cleanup(p);
    }
    @SubscribeEvent
    static void tracking(net.neoforged.neoforge.event.entity.player.PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer viewer && event.getTarget() instanceof ServerPlayer p) {
            State s = state(p);
            if (s.form != null) io.github.verycooltimo.murim.network.ChannelSafe.send(viewer,
                    new TechniqueEventPayload(TechniqueEventPayload.Event.STARTED, s.form, p.getId(), s.left, s.stars));
        }
    }
    static void cleanup(ServerPlayer p) {
        stop(p); remove(p, Attributes.SAFE_FALL_DISTANCE, FALL);
        State s = state(p); s.safeLanding = s.airborne = false; s.passed.clear();
    }
    private static boolean dodge(ServerPlayer p) {
        State s = state(p);
        if (!RAIN.equals(s.form) || p.serverLevel().getGameTime() >= s.until || s.left <= 0 || s.dodges <= 0 || !FootworkService.canSustain(p)
                || Stun.isStunned(p) || !FootworkService.selected(p, RAIN)) return false;
        --s.dodges;
        // Each actual dodge triggers a short pose and blur; no displacement/rotation of the camera.
        event(p, TechniqueEventPayload.Event.HIT);
        return true;
    }
    /** Direct non-damage contacts must resolve Rain before applying interruption or other effects. */
    public static boolean dodgeContact(LivingEntity target) {
        return target instanceof ServerPlayer player && dodge(player);
    }
    /** Custom collision paths (Tang is an Entity, not a vanilla Projectile) use this before side effects. */
    public static boolean dodgeProjectile(ServerPlayer p, net.minecraft.world.entity.Entity projectile) {
        State s = state(p);
        long now = p.serverLevel().getGameTime();
        Long until = s.passed.get(projectile.getUUID());
        if (until != null && until > now) return true;
        if (!dodge(p)) return false;
        s.passed.put(projectile.getUUID(), now + 20);
        return true;
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void incoming(LivingIncomingDamageEvent event) {
        if (!event.isCanceled() && event.getEntity() instanceof ServerPlayer p
                && event.getAmount() > 0 && !event.getSource().is(DamageTypeTags.IS_EXPLOSION)
                && (event.getSource().getEntity() instanceof LivingEntity
                || event.getSource().getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile)
                && (event.getSource().getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile projectile
                    ? dodgeProjectile(p, projectile) : dodge(p))) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void projectile(ProjectileImpactEvent event) {
        if (!event.isCanceled() && event.getRayTraceResult() instanceof EntityHitResult hit
                && hit.getEntity() instanceof ServerPlayer p && dodgeProjectile(p, event.getProjectile())) event.setCanceled(true);
    }
    private static void event(ServerPlayer p, TechniqueEventPayload.Event kind) {
        State s = state(p);
        var payload = new TechniqueEventPayload(kind, s.form, p.getId(), s.left, s.stars);
        if (p.connection != null && p.connection.hasChannel(payload)) PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, payload);
    }
    private static void modifier(ServerPlayer p, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr,
                                 ResourceLocation id, double value, AttributeModifier.Operation op) {
        var a = p.getAttribute(attr);
        if (a != null) { a.removeModifier(id); a.addTransientModifier(new AttributeModifier(id, value, op)); }
    }
    private static void remove(ServerPlayer p, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr, ResourceLocation id) {
        var a = p.getAttribute(attr); if (a != null) a.removeModifier(id);
    }
    private FragranceAirService() {}
}
