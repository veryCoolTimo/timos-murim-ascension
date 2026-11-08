package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientConfig;
import io.github.verycooltimo.murim.network.BambooPayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.technique.BambooRules;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import java.util.*;

/** Uses the existing VfxDraw/MurimRenderTypes world-effect backend (this build has no VfxManager).
 * Actual stars select presentation only; existing animation reaction hooks remain shared.
 * Mastered impact accents are local geometry; bounded facts expire even if END is lost.
 * API: reference client Minecraft#renderBuffers, BlockState#getCollisionShape, VoxelShape#bounds,
 * NeoForge RenderLevelStageEvent#getPartialTick returns DeltaTracker, AFTER_PARTICLES.
 * Added calls verified in /tmp/mr-ref-1121: Vec3 vector operations, Entity position/onGround,
 * Options#particles/OptionInstance#get and ParticleStatus ALL/DECREASED/MINIMAL.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class BambooVfx {
    private record Hand(int stars, long until, long born, boolean sword) { }
    private record Wind(long born, Vec3 at, Vec3 forward, int stars, Vec3 turnAt, Vec3 turnForward, long turned) { }
    private static final Map<Integer, Wind> winds = new HashMap<>();
    private record Mark(BambooPayload p, long born, Vec3 ground, int blockColour, Vec3 forward, double floor) { }
    private static final class Trail {
        final BambooPayload cue;
        final long until;
        boolean sampled;
        Vec3 previous;
        boolean airborne;
        int idle;
        Trail(BambooPayload cue, long until) { this.cue = cue; this.until = until; previous = cue.at().add(0, cue.kind() == BambooPayload.KNOCKDOWN ? .7 : 0, 0); }
    }
    private static boolean captionShown;
    private static final Map<Integer, Trail> trails = new HashMap<>();
    private static final Map<Integer, Long> recoil = new HashMap<>();
    private static final Map<Integer, Long> stagger = new HashMap<>();
    private static net.minecraft.client.multiplayer.ClientLevel world;
    private static boolean ready() {
        var level = Minecraft.getInstance().level;
        if (level != world) { hands.clear(); winds.clear(); marks.clear(); fallen.clear(); landed.clear(); trails.clear(); stagger.clear(); recoil.clear(); world = level; }
        return level != null;
    }
    private static final Map<Integer, Hand> hands = new HashMap<>();
    private static final List<Mark> marks = new ArrayList<>();
    private static final Map<Integer, Long> fallen = new HashMap<>();
    private static final Set<Integer> landed = new HashSet<>();
    public static void technique(TechniqueEventPayload p) {
        var mc = Minecraft.getInstance();
        if (!ready() || !BambooRules.is(p.techniqueId())) return;
        if (!BambooLook.mastered(p.masteryStars())) BambooClassicVfx.technique(p);
        if (p.event() == TechniqueEventPayload.Event.STARTED) {
            io.github.verycooltimo.murim.client.QiSwordClient.sheathe(p.sourceId());
            if (BambooLook.mastered(p.masteryStars()) && !captionShown && mc.player != null && p.sourceId() == mc.player.getId()) {
                captionShown = true;
                // API: MC network/chat/Component.java#empty/translatable; existing caption uses Reggae One.
                TechniqueCaption.show(net.minecraft.network.chat.Component.empty(),
                        net.minecraft.network.chat.Component.translatable("technique.murim.bamboo_leaf_hand.caption"), 60);
            }
            var d = TechniqueLoader.get(p.techniqueId());
            if (d != null && p.executionTier() > 0) hands.put(p.sourceId(), new Hand(p.masteryStars(), mc.level.getGameTime() + d.totalTicks(), mc.level.getGameTime(),
                    p.techniqueId().getPath().equals("bamboo_sword_fingers")));
            if (p.executionTier() > 0 && p.masteryStars() == 12 && p.techniqueId().getPath().equals("bamboo_wind_leaf") && winds.size() < 16) {
                var caster = mc.level.getEntity(p.sourceId());
                if (caster != null) winds.put(p.sourceId(), new Wind(mc.level.getGameTime(),
                        caster.position().add(0, 1.2, 0), caster.getLookAngle().multiply(1, 0, 1).normalize(), p.masteryStars(), null, null, 0));
            }
        } else if (p.event() == TechniqueEventPayload.Event.CANCELLED || p.event() == TechniqueEventPayload.Event.FINISHED) hands.remove(p.sourceId());
    }
    public static void accept(BambooPayload p) {
        var mc = Minecraft.getInstance();
        if (!ready() || p.stars() <= 0) return;
        if (!BambooLook.mastered(p.stars())) BambooClassicVfx.accept(p);
        if (BambooLook.mastered(p.stars())) BambooMasterVfx.contactLight(p);
        if (BambooLook.mastered(p.stars()) && p.kind() == BambooPayload.GROVE && p.duration() == 0) {
            // First confirmed touch grows the grove; the sixth cue must not restart or pop it.
            accept(new BambooPayload(p.caster(), p.target(), p.stars(), BambooPayload.GROVE_RING, p.at()));
        }
        if (p.kind() == BambooPayload.KNOCKDOWN) {
            long until = mc.level.getGameTime() + Math.clamp(p.duration(), 0, 30);
            fallen.put(p.target(), until);
            landed.remove(p.target());
            track(p, until);
            var caster = mc.level.getEntity(p.caster());
            Vec3 forward = caster == null ? new Vec3(0, 0, 1) : caster.getLookAngle().multiply(1, 0, 1).normalize();
            if (BambooLook.mastered(p.stars()) && marks.size() < 128)
                marks.add(new Mark(p, mc.level.getGameTime(), p.at(), 0x59503D, forward, p.at().y));
            return;
        }
        if (p.kind() == BambooPayload.LANDING && fallen.containsKey(p.target())) landed.add(p.target());
        if (p.kind() == BambooPayload.ASIDE || p.kind() == BambooPayload.COLLAPSE)
            stagger.put(p.target(), mc.level.getGameTime() + 6);
        if (p.kind() == BambooPayload.WALL && p.duration() > 0) recoil.put(p.target(), mc.level.getGameTime() + p.duration());
        if (p.kind() == BambooPayload.JAB) recoil.put(p.target(), mc.level.getGameTime() + 8);
        if (!BambooLook.mastered(p.stars())) return;
        if (p.kind() == BambooPayload.RETURN) {
            track(p, mc.level.getGameTime() + 60);
            // RETURN confirms the actual reversal point. Seed it now: a short flight can
            // impact before the next client tick, so movement polling alone can miss it.
            accept(new BambooPayload(p.caster(), p.target(), p.stars(), BambooPayload.TRAIL, p.at()));
            Trail returned = trails.get(p.target());
            if (returned != null) returned.sampled = true;
            if (Boolean.getBoolean("murim.capture")) MurimMod.LOGGER.info(
                    "CAPTURE-BAMBOO-TRAIL started projectile={} reason=confirmed_return_point", p.target());
            if (Boolean.getBoolean("murim.capture")) MurimMod.LOGGER.info(
                    "CAPTURE-BAMBOO-RETURN fact_received projectile={} found={}", p.target(), mc.level.getEntity(p.target()) != null);
        }
        Vec3 ground = p.at();
        int colour = 0x59503D;
        if (p.kind() == BambooPayload.GROUND) {
            // Find the actual collision surface, including slabs; never change a block.
            BlockPos pos = BlockPos.containing(p.at().add(0, .25, 0));
            for (int i = 0; i < 3; i++, pos = pos.below()) {
                var shape = mc.level.getBlockState(pos).getCollisionShape(mc.level, pos);
                if (!shape.isEmpty()) {
                    ground = new Vec3(p.at().x, pos.getY() + shape.bounds().maxY + .006, p.at().z);
                    colour = mc.level.getBlockState(pos).getMapColor(mc.level, pos).col;
                    break;
                }
            }
        }
        if (p.kind() == BambooPayload.GROVE_RING && marks.stream().anyMatch(
                m -> m.p.kind() == BambooPayload.GROVE_RING && m.p.target() == p.target()
                        && mc.level.getGameTime() - m.born < 56)) return;
        // Drop new ambient trail samples before evicting any live contact or pressure point.
        if (p.kind() == BambooPayload.TRAIL && marks.size() >= 96) return;
        if (marks.size() >= 128) return;
        if (p.kind() == BambooPayload.GROVE_RING && marks.stream().filter(
                m -> m.p.kind() == BambooPayload.GROVE_RING).count() >= 4) return;
        var caster = mc.level.getEntity(p.caster());
        Vec3 forward = caster == null ? new Vec3(0, 0, 1) : caster.getLookAngle().multiply(1, 0, 1).normalize();
        if ((p.kind() == BambooPayload.WALL || p.kind() == BambooPayload.RETURN) && caster != null) {
            Vec3 palm = BoneAnchorLayer.position(caster, BoneAnchorLayer.Bone.RIGHT_FINGERS);
            ground = palm == null ? caster.position().add(forward.scale(.65)).add(0, 1.25, 0) : palm;
        }
        if (p.kind() == BambooPayload.RETURN) {
            // The fact arrives after reversal; use the projectile line, including its vertical slope.
            // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/Entity.java#getDeltaMovement.
            var arrow = mc.level.getEntity(p.target());
            if (arrow != null && arrow.getDeltaMovement().lengthSqr() > .001) {
                Vec3 line = arrow.getDeltaMovement().normalize();
                // Entity motion packets can lag the fact; keep the line oriented toward the shooter.
                forward = line.dot(forward) < 0 ? line.scale(-1) : line;
            }
        }
        if (p.kind() == BambooPayload.ASIDE && caster != null) {
            var attacker = mc.level.getEntity(p.target());
            Vec3 blade = attacker == null ? null : BoneAnchorLayer.position(attacker, BoneAnchorLayer.Bone.RIGHT_FINGERS);
            if (blade != null) ground = blade;
        }
        if (p.kind() == BambooPayload.JAB && caster != null) {
            Vec3 fingers = BoneAnchorLayer.position(caster, BoneAnchorLayer.Bone.RIGHT_FINGERS);
            forward = caster.getLookAngle();
            // Freeze the blade at actual contact so its triangles become shards without a whole-shape pop.
            ground = fingers == null ? p.at().subtract(forward.scale(2.5)) : fingers;
        }
        if (p.kind() == BambooPayload.ASIDE && p.duration() < 0) forward = forward.scale(-1);
        if (p.kind() == BambooPayload.RETURN) {
            Wind wind = winds.get(p.caster());
            if (wind != null && wind.turnAt == null) winds.put(p.caster(), new Wind(wind.born, wind.at, wind.forward,
                    wind.stars, ground, forward, mc.level.getGameTime()));
        }
        long visualBorn = mc.level.getGameTime();
        // Stable visual clock survives removal of the hand at the end of the clip.
        // Gameplay, shared hit response and recoil still use the confirmed fact immediately.
        // The new blade shatters at the actual contact, never at a delayed cast age.
        marks.add(new Mark(p, visualBorn, ground, colour, forward, caster == null ? p.at().y : caster.position().y));
    }
    // API: read-only neoforge-src/.../event/entity/ProjectileImpactEvent#getProjectile/getRayTraceResult;
    // minecraft-src/world/phys/HitResult#getLocation. LOWEST observes cancellation without changing the projectile.
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    static void returnedImpact(net.neoforged.neoforge.event.entity.ProjectileImpactEvent event) {
        if (!ready() || event.getProjectile().level() != world || event.isCanceled()) return;
        Trail trail = trails.get(event.getProjectile().getId());
        if (trail == null || trail.cue.kind() != BambooPayload.RETURN || !BambooLook.mastered(trail.cue.stars())) return;
        Vec3 at = event.getRayTraceResult().getLocation();
        accept(new BambooPayload(trail.cue.caster(), trail.cue.target(), trail.cue.stars(), BambooPayload.RETURN_HIT, at));
        TechniqueLight.add(at, 0x7FD99A, 3.8F, .8F, 1, 0, 8);
        trails.remove(trail.cue.target());
    }
    @SubscribeEvent static void tick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        if (!ready()) return;
        long now = mc.level.getGameTime();
        winds.entrySet().removeIf(x -> now - (x.getValue().turnAt == null ? x.getValue().born : x.getValue().turned) >= (x.getValue().turnAt == null ? 34 : 24));
        hands.entrySet().removeIf(x -> x.getValue().until <= now || mc.level.getEntity(x.getKey()) == null);
        fallen.entrySet().removeIf(x -> x.getValue() <= now || mc.level.getEntity(x.getKey()) == null);
        landed.removeIf(id -> !fallen.containsKey(id));
        recoil.entrySet().removeIf(x -> x.getValue() <= now);
        stagger.entrySet().removeIf(x -> x.getValue() <= now);
        marks.removeIf(x -> now - x.born >= BambooLook.lifetime(x.p.kind()));
        var iterator = trails.values().iterator();
        while (iterator.hasNext()) {
            Trail trail = iterator.next();
            var entity = mc.level.getEntity(trail.cue.target());
            if (entity == null || now >= trail.until) {
                if (trail.cue.kind() == BambooPayload.RETURN && Boolean.getBoolean("murim.capture"))
                    MurimMod.LOGGER.info("CAPTURE-BAMBOO-TRAIL projectile={} reason={}", trail.cue.target(),
                            entity == null ? "projectile_missing_or_impacted" : "tracking_expired");
                iterator.remove(); continue;
            }
            boolean thrown = trail.cue.kind() == BambooPayload.KNOCKDOWN;
            trail.airborne |= !entity.onGround();
            Vec3 at = entity.position().add(0, thrown ? .7 : 0, 0);
            if (thrown && trail.airborne && entity.onGround()) {
                landed.add(entity.getId());
                accept(new BambooPayload(trail.cue.caster(), entity.getId(), trail.cue.stars(),
                        BambooPayload.LANDING, entity.position().add(0, .1, 0)));
                iterator.remove();
            } else if (at.distanceToSqr(trail.previous) > .0025) {
                // A bounded five-tick history follows the actual entity, including the full throw arc.
                if (thrown) {
                    Vec3 segment = at.subtract(trail.previous);
                    int samples = density(3);
                    for (int i = 1; i <= samples; i++) accept(new BambooPayload(trail.cue.caster(), entity.getId(),
                            trail.cue.stars(), BambooPayload.TRAIL, 1, trail.previous.add(segment.scale(i / (double) samples))));
                } else {
                    // Arrow speed can exceed one block/tick: sample the real segment, not only its endpoint.
                    Vec3 segment = at.subtract(trail.previous);
                    double length = Math.min(.9, segment.length());
                    if (!trail.sampled && Boolean.getBoolean("murim.capture")) MurimMod.LOGGER.info(
                            "CAPTURE-BAMBOO-TRAIL started projectile={} segment_length={}", entity.getId(), segment.length());
                    trail.sampled = true;
                    int samples = density(8);
                    for (int i = 0; i < samples; i++) accept(new BambooPayload(trail.cue.caster(), entity.getId(),
                            trail.cue.stars(), BambooPayload.TRAIL, at.subtract(segment.normalize().scale(length * i / Math.max(1, samples - 1)))));
                }
                trail.previous = at;
                trail.idle = 0;
            } else if (!thrown && ++trail.idle >= 3) {
                if (Boolean.getBoolean("murim.capture")) MurimMod.LOGGER.info(
                        "CAPTURE-BAMBOO-TRAIL projectile={} reason=no_client_movement", trail.cue.target());
                iterator.remove();
            }
        }
    }
    @SubscribeEvent static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !ready()) return;
        if (winds.isEmpty() && hands.values().stream().noneMatch(h -> BambooLook.mastered(h.stars))
                && marks.stream().noneMatch(m -> BambooLook.mastered(m.p.stars()))) return;
        var mc = Minecraft.getInstance();
        Vec3 camera = e.getCamera().getPosition();
        var ps = e.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        double now = mc.level.getGameTime() + e.getPartialTick().getGameTimeDeltaPartialTick(false);
        boolean glow = TechniqueGlow.want(.8F);
        ps.pushPose();
        try {
            ps.translate(-camera.x, -camera.y, -camera.z);
            // Flush solid geometry before requesting the glow buffer (same dynamic buffer source).
            for (int pass = 0; pass < (glow ? 2 : 1); pass++) {
                var type = pass == 0 ? MurimRenderTypes.bambooLeaves() : TechniqueGlow.preset();
                var v = buffers.getBuffer(type);
                int room = Math.min(1200, Math.max(0, VfxBudget.TOTAL - VfxBudget.live()));
                for (Mark m : marks) {
                    if (!BambooLook.mastered(m.p.stars()) || room <= 0) continue;
                    float age = (float) (now - m.born);
                    if (age < 0 || camera.distanceToSqr(m.ground) > 32 * 32) continue;
                    var caster = mc.level.getEntity(m.p.caster());
                    double floor = m.floor;
                    double[] touches = {-1, -1, -1, -1, -1, -1};
                    if (m.p.kind() == BambooPayload.GROVE_RING) for (Mark touch : marks) {
                        if (touch.p.kind() == BambooPayload.GROVE && touch.p.caster() == m.p.caster()
                                && touch.p.target() == m.p.target() && touch.born >= m.born
                                && touch.p.duration() >= 0 && touch.p.duration() < 6)
                            touches[touch.p.duration()] = now - touch.born;
                    }
                    Vec3 at = m.ground;
                    var target = mc.level.getEntity(m.p.target());
                    if (m.p.kind() == BambooPayload.GROVE && target instanceof net.minecraft.world.entity.LivingEntity living)
                        at = grovePoint(living, m.p.duration()).add(0, -.9, 0);
                    if (m.p.kind() == BambooPayload.RETURN && caster != null) {
                        Vec3 hand = BoneAnchorLayer.position(caster, BoneAnchorLayer.Bone.RIGHT_FINGERS);
                        if (hand != null) at = hand;
                    }
                    room -= BambooMasterVfx.mark(v, ps.last(), m.p, at, m.forward, camera, age,
                            floor, m.born * 31 + m.p.caster(), pass == 1,
                            particleScale() * Math.min(1, ClientConfig.trailSegments() / 28.0), touches);
                }
                for (var entry : hands.entrySet()) {
                    Hand h = entry.getValue();
                    if (!BambooLook.mastered(h.stars)) continue;
                    var entity = mc.level.getEntity(entry.getKey());
                    if (entity == null) continue;
                    Vec3 finger = BoneAnchorLayer.position(entity, BoneAnchorLayer.Bone.RIGHT_FINGERS);
                    if (finger == null || camera.distanceToSqr(finger) > 32 * 32) continue;
                    boolean hit = marks.stream().anyMatch(m -> m.p.caster() == entry.getKey()
                            && m.p.kind() == BambooPayload.JAB && m.born >= h.born);
                    if (h.sword && !hit) BambooMasterVfx.sword(v, ps.last(), finger, entity.getLookAngle(),
                            camera, (float) (now - h.born), pass == 1);
                }
                for (Wind w : winds.values()) {
                    if (camera.distanceToSqr(w.at) > 32 * 32) continue;
                    BambooMasterVfx.ribbon(v, ps.last(), w.at, w.forward, camera, (float) (now - w.born),
                            w.born, pass == 1, w.turnAt, w.turnForward, (float)(now-w.turned),
                            particleScale() * Math.min(1, ClientConfig.trailSegments() / 28.0));
                }
                buffers.endBatch(type);
            }
        } finally { ps.popPose(); }
    }
    /** Body-local pressure points on the weapon side, following the foe throughout the hold.
     * API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/LivingEntity.java
     * #yBodyRot/getMainArm; Entity.java#position/getBbHeight/getBbWidth; HumanoidArm.java.
     */
    private static Vec3 grovePoint(net.minecraft.world.entity.LivingEntity foe, int contact) {
        int index = Math.clamp(contact, 0, 5);
        double yaw = Math.toRadians(foe.yBodyRot);
        Vec3 front = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 side = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw))
                .scale(foe.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT ? 1 : -1);
        double height = switch (index) {
            case 0 -> .79; case 1 -> .65; case 2 -> .51;
            case 3 -> .75; case 4 -> .61; default -> .47;
        };
        double width = index < 3 ? .57 : index == 3 ? 0 : .34;
        return foe.position().add(0, foe.getBbHeight() * height, 0)
                .add(side.scale(foe.getBbWidth() * width)).add(front.scale(foe.getBbWidth() * .40));
    }
    private static void track(BambooPayload p, long until) {
        if (trails.size() >= 32) {
            if (Boolean.getBoolean("murim.capture")) MurimMod.LOGGER.info(
                    "CAPTURE-BAMBOO-TRAIL projectile={} reason=tracking_capacity", p.target());
            return;
        }
        trails.put(p.target(), new Trail(p, until));
    }
    private static int density(int full) {
        return Math.max(1, (int) Math.ceil(full * particleScale() * ClientConfig.trailSegments() / 28.0));
    }
    private static double particleScale() {
        return switch (Minecraft.getInstance().options.particles().get()) {
            case ALL -> 1; case DECREASED -> .6; case MINIMAL -> .3;
        };
    }
    /** Presentation only: the server attack lock and caster cancellation stay authoritative. */
    public static float staggerStrength(int entityId, float partial) {
        if (!ready()) return 0;
        double left = stagger.getOrDefault(entityId, 0L) - world.getGameTime() - partial;
        return left <= 0 ? 0 : (float) (Math.min(1, (6 - left) / 5) * Math.min(1, left));
    }
    /** Confirmed Wall stun or Sword contact: render-only backward recoil. */
    public static float recoilStrength(int entityId, float partial) {
        if (!ready()) return 0;
        double left = recoil.getOrDefault(entityId, 0L) - world.getGameTime() - partial;
        return (float) Math.clamp(left / 4, 0, 1);
    }
    /** Upright backward tilt from throw confirmation until a sampled airborne-to-ground transition. */
    public static boolean airborneThrow(int entityId) {
        return ready() && fallen.containsKey(entityId) && !landed.contains(entityId);
    }
    /** Render-only knockdown blockout, only once the victim lands. No pose/hitbox state is changed.
     * API verified: MC LivingEntityRenderer#render posts Pre before its own push and Post after pop;
     * EntityRenderDispatcher#render owns an outer push/pop even when a later Pre listener cancels.
     * Apply/invert the transform without an extra push, so cancellation cannot leak stack entries.
     */
    public static boolean lying(net.minecraft.world.entity.LivingEntity entity) {
        var level = Minecraft.getInstance().level;
        return level != null && landed.contains(entity.getId()) && entity.onGround() && fallen.getOrDefault(entity.getId(), 0L) > level.getGameTime();
    }
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    static void before(net.neoforged.neoforge.client.event.RenderLivingEvent.Pre<?, ?> e) {
        if (!lying(e.getEntity())) return;
        e.getPoseStack().translate(0, .15, 0);
        e.getPoseStack().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90));
    }
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    static void after(net.neoforged.neoforge.client.event.RenderLivingEvent.Post<?, ?> e) {
        if (!lying(e.getEntity())) return;
        e.getPoseStack().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(-90));
        e.getPoseStack().translate(0, -.15, 0);
    }
    private BambooVfx() { }
}
