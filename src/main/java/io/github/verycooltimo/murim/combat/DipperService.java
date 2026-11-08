package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Server-only, transient pressure. Design: docs/adr/seven-stars-three-forms-1103.md.
 * API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/{Entity,LivingEntity}.java
 * #move/setDeltaMovement/position/setYRot/getAttribute/hasLineOfSight; AttributeInstance#addTransientModifier/removeModifier;
 * AttributeModifier(ResourceLocation,double,Operation). NeoForge event/tick/EntityTickEvent.Post
 * and event/entity/living/LivingDamageEvent.Post#getNewDamage/getSource (same reference root neoforge-src).
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class DipperService {
    public static final ResourceLocation FORM = ResourceLocation.fromNamespaceAndPath("murim", "seven_stars_dipper");
    private static final ResourceLocation ROOT = ResourceLocation.fromNamespaceAndPath("murim", "dipper_root");
    public static final int DURATION = 28, COOLDOWN = 400;
    /** Each 4-tick step: TRAVEL ticks of motion along the arc, the cut on the landing tick, then the next step.
     * Author 3.11: «Ковш всё ещё дёрганный» — 2 ticks of travel and a 2-tick stop read as a stop-go jerk. */
    public static final int TRAVEL = 3;
    public static final class State {
        final LivingEntity target;
        final Vec3 anchor;
        final ResourceLocation basic;
        final java.util.List<Vec3> points;
        int age, visited, strikes;
        java.util.List<Vec3> leg = java.util.List.of();
        Vec3 legStart;
        boolean landed, contact;
        State(LivingEntity target, ResourceLocation basic, java.util.List<Vec3> points) {
            this.target = target; this.anchor = target.position(); this.basic = basic; this.points = points;
        }
    }
    public static boolean active(ServerPlayer player) { return player.getData(ModAttachments.WIND_GOD).dipper != null; }

    /** Seven terrestrial Dipper points: handle, neck, then the four corners of the bowl.
     * API: MC world/phys/Vec3#add/scale; no world state or randomness in this geometry. */
    static java.util.List<Vec3> points(Vec3 centre, Vec3 forward) {
        Vec3 side = new Vec3(-forward.z, 0, forward.x);
        return io.github.verycooltimo.murim.technique.DipperTrace.POINTS.stream()
                .map(p -> centre.add(side.scale(p.x())).add(forward.scale(p.z()))).toList();
    }

    /** Null means accepted; otherwise the authoritative failed gate, also used by capture. */
    public static String start(ServerPlayer player) {
        var target = FootworkService.softTarget(player, 4);
        var basic = FoundationService.active(player);
        long now = player.level().getGameTime();
        Long last = player.getData(ModAttachments.COOLDOWNS).get(FORM);
        if (!MasteryService.knows(player, FORM)) return "dipper_not_learned_or_below_star_8";
        if (MasteryService.stars(player, FORM) < 8) return "dipper_requires_star_8";
        if (!FootworkService.selected(player, FORM)) return "dipper_not_equipped";
        if (!FootworkService.canSustain(player)) return "body_or_active_technique_blocks_dipper";
        if (FootworkService.active(player)) return "footwork_already_active";
        if (Stun.isStunned(player)) return "stunned";
        if (target == null || target instanceof ServerPlayer victim && !io.github.verycooltimo.murim.sect.SectDuel.permitsContact(player, victim)) return "no_target_within_4_blocks";
        var equippedBasic = player.getData(ModAttachments.LOADOUT).foundation();
        if (equippedBasic.isEmpty()) return "sword_foundation_not_equipped";
        if (!MasteryService.knows(player, equippedBasic.get())) return "sword_foundation_not_learned";
        if (basic.isEmpty()) return "sword_required_in_hand";
        if (!(basic.get().getPath().equals("six_harmonies") || basic.get().getPath().equals("seven_plum_basic")))
            return "requires_hua_sword_foundation";
        if (target.getData(ModAttachments.WIND_GOD).rootOwner != null) return "target_already_rooted";
        if (last != null && now - last < COOLDOWN) return "dipper_cooldown";
        Vec3 forward = new Vec3(player.getLookAngle().x, 0, player.getLookAngle().z).normalize();
        var state = new State(target, basic.get(), points(target.position(), forward));
        if (state.points.stream().noneMatch(p -> safePoint(player, p))) return "no_safe_dipper_point";
        if (!FootworkService.pay(player, 4
                * io.github.verycooltimo.murim.cultivation.PillService.costFactor(player))) return "insufficient_qi";
        player.getData(ModAttachments.WIND_GOD).dipper = state;
        player.setData(ModAttachments.BASIC_ATTACK, SwordBasicAttack.Pending.IDLE);
        player.getData(ModAttachments.WIND_GOD).sevenStart = Long.MIN_VALUE;
        var root = target.getData(ModAttachments.WIND_GOD);
        root.rootYaw = target.getYRot(); root.rootBodyYaw = target.yBodyRot; root.rootHeadYaw = target.yHeadRot;
        // API: MC LivingEntity public yaw fields; Mob#isNoAi/setNoAi. Restore the original AI flag on every release.
        if (target instanceof net.minecraft.world.entity.Mob mob) { root.rootNoAi = mob.isNoAi(); mob.setNoAi(true); }
        root.rootOwner = player.getUUID(); root.rootAnchor = target.position(); root.rootUntil = now + DURATION + 2;
        facing(target, DURATION + 2);
        var speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.addTransientModifier(new AttributeModifier(ROOT, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        var cooldowns = new java.util.HashMap<>(player.getData(ModAttachments.COOLDOWNS));
        cooldowns.put(FORM, now); player.setData(ModAttachments.COOLDOWNS, cooldowns);
        io.github.verycooltimo.murim.sect.SectBouts.onCast(player);
        FootworkService.sevenEvent(player, FORM, TechniqueEventPayload.Event.STARTED);
        return null;
    }

    private static boolean safePoint(ServerPlayer player, Vec3 point) {
        // The repeated endpoint deliberately reuses the existing full body/support/liquid check.
        return FootworkService.safeRoute(player, java.util.List.of(point, point));
    }

    /** Sweep an arc around the foe, never a chord through its body. Blocked points consume their slot. */
    static java.util.List<Vec3> route(ServerPlayer player, State state, Vec3 end) {
        Vec3 from = player.position().subtract(state.anchor), to = end.subtract(state.anchor);
        double start = Math.atan2(from.z, from.x), angle = Math.atan2(to.z, to.x) - start;
        while (angle > Math.PI) angle -= Math.PI * 2;
        while (angle < -Math.PI) angle += Math.PI * 2;
        var route = new java.util.ArrayList<Vec3>(); route.add(player.position());
        double radius = Math.max(1.8, Math.sqrt(from.x * from.x + from.z * from.z));
        for (int i = 0; i <= 24; i++) {
            double a = start + angle * i / 24;
            route.add(state.anchor.add(Math.cos(a) * radius, 0, Math.sin(a) * radius));
        }
        route.add(end);
        return FootworkService.safeRoute(player, route) ? route : java.util.List.of();
    }

    static void tick(ServerPlayer player) {
        State state = player.getData(ModAttachments.WIND_GOD).dipper;
        if (state == null) return;
        if (!player.isAlive() || !state.target.isAlive() || player.isPassenger() || Stun.isStunned(player)
                || !FootworkService.canSustain(player) || !FootworkService.selected(player, FORM)
                || !FoundationService.active(player).filter(state.basic::equals).isPresent()
                || player.level() != state.target.level() || state.target.position().distanceTo(state.anchor) > 1) {
            end(player, false); return;
        }
        root(state.target);
        player.setDeltaMovement(0, player.getDeltaMovement().y, 0);
        int phase = state.age % 4;
        if (phase == 0) {
            state.legStart = player.position(); state.landed = false;
            state.leg = route(player, state, state.points.get(state.age / 4));
            if (!state.leg.isEmpty()) io.github.verycooltimo.murim.network.ChannelSafe.send(player,
                    new io.github.verycooltimo.murim.network.TraversePayloads.SevenRoute(state.leg, TRAVEL, state.target.getId(), player.level().getGameTime()));
        }
        if (phase < TRAVEL && !state.leg.isEmpty()) {
            for (int sample = 1; sample <= 64; sample++) {
                Vec3 at = io.github.verycooltimo.murim.technique.SevenStarsRules.routePoint(state.leg, io.github.verycooltimo.murim.technique.SevenStarsRules.smoothstep((phase + sample / 64.0) / TRAVEL));
                if (!FootworkService.safeRoute(player, java.util.List.of(player.position(), at))) {
                    state.leg = java.util.List.of(); break;
                }
                player.move(MoverType.SELF, at.subtract(player.position()).add(0, -.001 / 64, 0));
            }
            if (phase == TRAVEL - 1 && !state.leg.isEmpty() && player.position().distanceTo(state.leg.getLast()) < .15) {
                state.visited++; state.landed = true;
                // Event carries the authoritative ground contact, not a client-interpolated endpoint.
                FootworkService.send(player, 18, state.age / 4, player.position());
            }
        }
        Vec3 facing = state.target.position().subtract(player.position());
        player.setYRot((float) Math.toDegrees(Math.atan2(-facing.x, facing.z)));
        if (phase == TRAVEL && state.landed) {
            state.contact = true;
            try {
                if (FoundationService.dipperStrike(player, state.target, state.age / 4)) {
                    state.strikes++;
                    FootworkService.send(player, 19, state.age / 4, state.target.position());
                }
            } finally { state.contact = false; }
        }
        if (++state.age >= DURATION) end(player, true);
    }

    public static void end(ServerPlayer player, boolean finished) {
        State state = player.getData(ModAttachments.WIND_GOD).dipper;
        if (state == null) return;
        player.getData(ModAttachments.WIND_GOD).dipper = null;
        var root = state.target.getData(ModAttachments.WIND_GOD);
        if (player.getUUID().equals(root.rootOwner)) release(state.target);
        if (finished) MasteryService.onMiss(player, FORM);
        FootworkService.sevenEvent(player, FORM, finished ? TechniqueEventPayload.Event.FINISHED : TechniqueEventPayload.Event.CANCELLED);
    }
    private static void release(LivingEntity target) {
        var root = target.getData(ModAttachments.WIND_GOD);
        root.rootOwner = null;
        facing(target, 0);
        if (target instanceof net.minecraft.world.entity.Mob mob) mob.setNoAi(root.rootNoAi);
        var speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(ROOT);
    }
    private static void facing(LivingEntity target, int ticks) {
        var root = target.getData(ModAttachments.WIND_GOD);
        io.github.verycooltimo.murim.network.ChannelSafe.nearEntity(target,
                new io.github.verycooltimo.murim.network.TraversePayloads.HeldFacing(target.getId(),
                        root.rootYaw, root.rootBodyYaw, root.rootHeadYaw, ticks), true);
    }
    private static void root(LivingEntity target) {
        var root = target.getData(ModAttachments.WIND_GOD);
        if (root.rootOwner == null) return;
        if (target instanceof ServerPlayer victim
                && (!(target.level().getPlayerByUUID(root.rootOwner) instanceof ServerPlayer owner)
                    || !io.github.verycooltimo.murim.sect.SectDuel.permitsContact(owner, victim))) { release(target); return; }
        if (target.level().getGameTime() >= root.rootUntil) { release(target); return; }
        // Refresh for observers who begin tracking during the form; bounded by the server root expiry.
        if (target.level().getGameTime() % 4 == 0) facing(target, (int) (root.rootUntil - target.level().getGameTime()));
        Vec3 correction = root.rootAnchor.subtract(target.position());
        target.move(MoverType.SELF, correction);
        target.setDeltaMovement(Vec3.ZERO);
        target.setYRot(root.rootYaw); target.yRotO = root.rootYaw;
        target.setYBodyRot(root.rootBodyYaw); target.yBodyRotO = root.rootBodyYaw;
        target.setYHeadRot(root.rootHeadYaw); target.yHeadRotO = root.rootHeadYaw;
        target.hurtMarked = true;
    }
    @SubscribeEvent
    static void rootTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof LivingEntity target && !target.level().isClientSide()) root(target);
    }
    /** API: /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/event/entity/player/AttackEntityEvent.java
     * #getEntity/setCanceled. Only the seven authored contacts can attack during this form. */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    static void attack(net.neoforged.neoforge.event.entity.player.AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && active(player)
                && !player.getData(ModAttachments.WIND_GOD).dipper.contact) event.setCanceled(true);
    }
    @SubscribeEvent
    static void damage(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0) return;
        if (event.getSource().getEntity() instanceof ServerPlayer attacker && event.getSource().getDirectEntity() == attacker
                && (FoundationService.active(attacker).isPresent()
                || io.github.verycooltimo.murim.technique.SevenStarsRules.huaSword(attacker.getData(ModAttachments.TECHNIQUE_STATE).techniqueId()))) {
            var seven = attacker.getData(ModAttachments.WIND_GOD);
            seven.chasedFoe = event.getEntity().getUUID(); seven.chasedHit = attacker.level().getGameTime();
        }
        if (event.getEntity() instanceof ServerPlayer player && active(player)
                && event.getSource().getEntity() instanceof LivingEntity attacker
                && attacker != player.getData(ModAttachments.WIND_GOD).dipper.target) end(player, false);
    }
    private DipperService() { }
}
