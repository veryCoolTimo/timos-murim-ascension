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
 * Shared impact/caption helpers are called only from confirmed Bamboo facts. Bounded facts expire even if END is lost.
 * API: reference client Minecraft#renderBuffers, BlockState#getCollisionShape, VoxelShape#bounds,
 * NeoForge RenderLevelStageEvent#getPartialTick returns DeltaTracker, AFTER_PARTICLES.
 * Added calls verified in /tmp/mr-ref-1121: Vec3 vector operations, Entity position/onGround,
 * Options#particles/OptionInstance#get and ParticleStatus ALL/DECREASED/MINIMAL.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class BambooClassicVfx {
    private record Chip(Vec3 at, int colour, float alpha, int light) { }
    private record Hand(int stars, long until, long born, boolean sword) { }
    private record Wind(long born, Vec3 at, Vec3 forward, int stars, Vec3 turnAt, Vec3 turnForward, long turned) { }
    private static final Map<Integer, Wind> winds = new HashMap<>();
    private record Mark(BambooPayload p, long born, Vec3 ground, int blockColour, Vec3 forward) { }
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
    private static long lastImpact = Long.MIN_VALUE;
    private static final Map<Integer, Trail> trails = new HashMap<>();
    private static final Map<Integer, Long> recoil = new HashMap<>();
    private static final Map<Integer, Long> stagger = new HashMap<>();
    private static net.minecraft.client.multiplayer.ClientLevel world;
    private static boolean ready() {
        var level = Minecraft.getInstance().level;
        if (level != world) { hands.clear(); winds.clear(); marks.clear(); fallen.clear(); landed.clear(); trails.clear(); stagger.clear(); recoil.clear(); world = level; lastImpact = Long.MIN_VALUE; }
        return level != null;
    }
    private static final Map<Integer, Hand> hands = new HashMap<>();
    private static final List<Mark> marks = new ArrayList<>();
    private static final Map<Integer, Long> fallen = new HashMap<>();
    private static final Set<Integer> landed = new HashSet<>();
    private static final VfxColour CORE = new VfxColour(53 / 255F, 201 / 255F, 135 / 255F);
    private static final VfxColour EDGE = new VfxColour(18 / 255F, 61 / 255F, 48 / 255F);
    public static void technique(TechniqueEventPayload p) {
        var mc = Minecraft.getInstance();
        if (!ready() || !BambooRules.is(p.techniqueId())) return;
        if (p.event() == TechniqueEventPayload.Event.STARTED) {
            io.github.verycooltimo.murim.client.QiSwordClient.sheathe(p.sourceId());
            if (!captionShown && mc.player != null && p.sourceId() == mc.player.getId()) {
                captionShown = true;
                // API: MC network/chat/Component.java#empty/translatable; existing caption uses Reggae One.
                TechniqueCaption.show(net.minecraft.network.chat.Component.empty(),
                        net.minecraft.network.chat.Component.translatable("technique.murim.bamboo_leaf_hand.caption"), 60);
            }
            var d = TechniqueLoader.get(p.techniqueId());
            if (d != null && p.masteryStars() > 0) hands.put(p.sourceId(), new Hand(p.masteryStars(), mc.level.getGameTime() + d.totalTicks(), mc.level.getGameTime(),
                    p.techniqueId().getPath().equals("bamboo_sword_fingers")));
            if (p.masteryStars() > 0 && p.techniqueId().getPath().equals("bamboo_wind_leaf") && winds.size() < 16) {
                var caster = mc.level.getEntity(p.sourceId());
                if (caster != null) winds.put(p.sourceId(), new Wind(mc.level.getGameTime(),
                        caster.position().add(0, 1.2, 0), caster.getLookAngle().multiply(1, 0, 1).normalize(), p.masteryStars(), null, null, 0));
            }
        } else if (p.event() == TechniqueEventPayload.Event.CANCELLED || p.event() == TechniqueEventPayload.Event.FINISHED) hands.remove(p.sourceId());
    }
    public static void accept(BambooPayload p) {
        var mc = Minecraft.getInstance();
        if (!ready() || p.stars() <= 0) return;
        if (ClassicBambooScale.blow(p.kind()) && mc.player != null && p.caster() == mc.player.getId()
                && (lastImpact == Long.MIN_VALUE || mc.level.getGameTime() - lastImpact >= 7)) {
            lastImpact = mc.level.getGameTime();
            ImpactFrames.trigger(p.at());
            SpeedLines.radial(.5F, .45F, .55F, 5, 0xB1FFD0);
            io.github.verycooltimo.murim.client.CameraShakeHandler.request(.35F + .45F * p.stars() / 12F);
        }
        if (p.kind() == BambooPayload.GROVE && p.duration() == 0) {
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
            if (marks.size() < 128) marks.add(new Mark(p, mc.level.getGameTime(), p.at(), 0x59503D, forward));
            return;
        }
        if (p.kind() == BambooPayload.LANDING && fallen.containsKey(p.target())) landed.add(p.target());
        if (p.kind() == BambooPayload.ASIDE || p.kind() == BambooPayload.COLLAPSE)
            stagger.put(p.target(), mc.level.getGameTime() + 6);
        if (p.kind() == BambooPayload.WALL && p.duration() > 0) recoil.put(p.target(), mc.level.getGameTime() + p.duration());
        if (p.kind() == BambooPayload.JAB) recoil.put(p.target(), mc.level.getGameTime() + 8);
        if (p.kind() == BambooPayload.RETURN) {
            track(p, mc.level.getGameTime() + 60);
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
        if (p.kind() == BambooPayload.ASIDE && p.duration() < 0) forward = forward.scale(-1);
        if (p.kind() == BambooPayload.RETURN) {
            Wind wind = winds.get(p.caster());
            if (wind != null) winds.put(p.caster(), new Wind(wind.born, wind.at, wind.forward,
                    wind.stars, ground, forward, mc.level.getGameTime()));
        }
        long visualBorn = mc.level.getGameTime();
        Hand hand = hands.get(p.caster());
        // Stable visual clock survives removal of the hand at the end of the clip.
        // Gameplay, shared hit response and recoil still use the confirmed fact immediately.
        if (p.kind() == BambooPayload.JAB && hand != null)
            visualBorn = Math.max(visualBorn, hand.born + 4);
        marks.add(new Mark(p, visualBorn, ground, colour, forward));
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
        marks.removeIf(x -> now - x.born >= lifetime(x.p.kind()));
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
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        var mc = Minecraft.getInstance(); if (!ready()) return;
        Vec3 camera = e.getCamera().getPosition();
        var ps = e.getPoseStack(); var buffers = mc.renderBuffers().bufferSource();
        var type = MurimRenderTypes.bambooLeaves(); var v = buffers.getBuffer(type);
        var debrisType = MurimRenderTypes.bambooDebris();
        List<Chip> chips = new ArrayList<>();
        double now = mc.level.getGameTime() + e.getPartialTick().getGameTimeDeltaPartialTick(false);
        ps.pushPose();
        try {
            ps.translate(-camera.x, -camera.y, -camera.z);
            // One batched leaf instance pool; reserve original pressure marks and hand anchors.
            int stormRoom = Math.min(2048, Math.max(0, VfxBudget.TOTAL - VfxBudget.live() - 320));
            int signatures = 16;
            // Grove owns the accent pool first so six point bursts cannot starve the finale.
            for (int pass = 0; pass < 2; pass++) for (Mark mark : marks) {
                boolean grove = mark.p.kind() == BambooPayload.GROVE_RING;
                if (grove != (pass == 0)) continue;
                float age = (float) (now - mark.born);
                var target = mc.level.getEntity(mark.p.target());
                Vec3 at = mark.p.kind() == BambooPayload.GROVE && target instanceof net.minecraft.world.entity.LivingEntity living
                        ? grovePoint(living, mark.p.duration()) : grove ? mark.ground : mark.p.kind() == BambooPayload.KNOCKDOWN && target != null
                        ? target.position().add(0, .8, 0) : mark.ground;
                if (mark.p.kind() == BambooPayload.JAB && target != null)
                    at = target.position().add(0, target.getBbHeight() * .65, 0);
                if (mark.p.kind() == BambooPayload.CONTACT || mark.p.kind() == BambooPayload.LANDING)
                    at = at.add(0, .9, 0);
                double distance = camera.distanceToSqr(at);
                if (age < 0 || distance > 32 * 32) continue;
                if (mark.p.kind() == BambooPayload.KNOCKDOWN)
                    ClassicBambooScale.bending(v, ps.last(), mark.ground, camera, age, mark.p.stars());
                if (grove) ClassicBambooScale.grove(v, ps.last(), at, camera, age, mark.p.stars());
                else if (signatures > 0 && ClassicBambooScale.blow(mark.p.kind())) {
                    var caster = mc.level.getEntity(mark.p.caster());
                    double groundY = caster == null ? at.y - 1.25 : caster.position().y;
                    ClassicBambooScale.signature(v, ps.last(), mark.p, at, mark.forward, camera, age, groundY);
                    if (mark.p.kind() == BambooPayload.JAB
                            && target instanceof net.minecraft.world.entity.LivingEntity living) {
                        // Separate body-local Sword anchors; confirmed hit facts only.
                        for (int point = 0; point < 6; point++) {
                            float fade = ClassicBambooEnding.pointFade(age, point);
                            ClassicBambooScale.pointFlash(v, ps.last(), swordPoint(living, point), camera, fade);
                        }
                    }
                    signatures--;
                }
                double quality = particleScale() * Math.min(1, ClientConfig.trailSegments() / 28.0);
                // Grove's tall crown requires a wider shot; its full-size storm is
                // still the nearby signature, rather than distant ambient detail.
                if (!grove && distance > 16 * 16) quality *= .15;
                stormRoom -= ClassicBambooScale.storm(v, ps.last(), mark.p, at, mark.forward, camera, age, stormRoom, quality);
            }
            // Hard per-frame ceiling for the original anchors and six gameplay point marks.
            int remaining = Math.min(320, Math.max(0, VfxBudget.TOTAL - VfxBudget.live()));
            // Fixed launch frame preserves the trajectory after the 24-tick cast ends.
            for (Wind wind : winds.values()) {
                double distance = camera.distanceToSqr(wind.at);
                if (distance > 32 * 32) continue;
                double quality = particleScale() * Math.min(1, ClientConfig.trailSegments() / 28.0);
                if (distance > 16 * 16) quality *= .15;
                stormRoom -= ClassicBambooScale.ribbons(v, ps.last(), wind.at, wind.forward, camera,
                        (float) (now - wind.born), stormRoom, quality * (.08 + .92 * Math.clamp((wind.stars - 1) / 11.0, 0, 1)), true,
                        wind.turnAt, wind.turnForward, (float) (now - wind.turned),
                        .25 + .75 * Math.clamp((wind.stars - 1) / 11.0, 0, 1));
            }
            for (var entry : hands.entrySet()) {
                if (remaining < 3) break;
                var entity = mc.level.getEntity(entry.getKey()); if (entity == null) continue;
                Vec3 finger = BoneAnchorLayer.position(entity, BoneAnchorLayer.Bone.RIGHT_FINGERS);
                if (finger == null || camera.distanceToSqr(finger) > 32 * 32) continue;
                Vec3 axis = entity.getLookAngle().scale(.11);
                int stars = entry.getValue().stars;
                remaining -= 3;
                leaf(v, ps.last(), finger, axis, camera, .08, .8F, stars >= 5);
                if (entry.getValue().sword) {
                    double reach = 1.45;
                    for (Mark mark : marks) if (mark.p.kind() == BambooPayload.JAB
                            && mark.p.caster() == entry.getKey() && mark.born >= entry.getValue().born) {
                        var foe = mc.level.getEntity(mark.p.target());
                        Vec3 contact = foe == null ? mark.ground : foe.position().add(0, foe.getBbHeight() * .65, 0);
                        reach = Math.clamp(contact.subtract(finger).dot(entity.getLookAngle()) + .18, .18, 1.65);
                        break;
                    }
                    ClassicBambooScale.sword(v, ps.last(), finger, entity.getLookAngle(),
                            (float) (now - entry.getValue().born), reach);
                }
            }
            for (Mark m : marks) {
                float age = (float) (now - m.born);
                var target = mc.level.getEntity(m.p.target());
                Vec3 centre = m.p.kind() == BambooPayload.GROVE && target instanceof net.minecraft.world.entity.LivingEntity living
                        ? grovePoint(living, m.p.duration())
                        : m.p.kind() == BambooPayload.GROVE_RING && target != null ? target.position() : m.p.at();
                double distance = camera.distanceToSqr(centre);
                if (age < 0 || distance > 32 * 32 || remaining <= 0) continue;
                int kind = m.p.kind();
                if (kind == BambooPayload.KNOCKDOWN || kind == BambooPayload.JAB
                        || kind == BambooPayload.GROVE_RING || kind == BambooPayload.CONTACT
                        || kind == BambooPayload.WALL) continue;
                float fade = Math.max(0, 1 - age / (kind == BambooPayload.TRAIL ? 5F : 8F));
                int desired = kind == BambooPayload.GROVE_RING ? 22 : kind == BambooPayload.GROVE ? 6
                        : kind == BambooPayload.COLLAPSE ? 10 : kind == BambooPayload.ASIDE ? 6 : kind == BambooPayload.TRAIL ? (m.p.duration() == 1 ? 3 : 1) : 12;
                int count = Math.min(remaining / 3, density(desired));
                if (kind == BambooPayload.WALL) count = Math.min(remaining / 3, density(8));
                if (kind == BambooPayload.JAB) count = Math.min(remaining / 3, 2);
                if (distance > 16 * 16) count = Math.min(count, 4);
                double size = .8 + .2 * Math.clamp((m.p.stars() - 1) / 11.0, 0, 1);
                if (kind == BambooPayload.GROVE) {
                    size *= 1.3;
                    // Every point shares the sixth contact's hold/fade deadline (cast ticks 20..24).
                    float left = 24 - 2 * Math.clamp(m.p.duration(), 0, 5) - age;
                    float markFade = Math.min(1, Math.max(0, left / 4));
                    if (remaining >= 1) {
                        VfxDraw.billboard(v, ps.last(), centre, camera, .06,
                                (age < 1 ? 1 : .5F) * markFade, 232/255F, 1, 240/255F);
                        remaining--;
                    }
                    if (age >= 4) continue;
                    fade = age < 2 ? 1 : Math.max(0, (4 - age) / 2);
                    count = Math.min(count, remaining / 3);
                }
                if (kind == BambooPayload.WALL) fade = age < 6 ? 1 : Math.max(0, (10 - age) / 4);
                if (kind == BambooPayload.ASIDE || kind == BambooPayload.RETURN) {
                    float arcFade = kind == BambooPayload.RETURN ? Math.max(0, Math.min(1, 4 - age)) : age < 3 ? 1 : Math.max(0, (5 - age) / 2);
                    if (arcFade > 0 && remaining >= (kind == BambooPayload.RETURN ? 15 : 12)) {
                        Vec3 arcCentre = m.ground;
                        Vec3 arcAxis = m.forward;
                        if (kind == BambooPayload.ASIDE) {
                            // Sweep from the sword hand along its blade, toward the selected push side.
                            Vec3 hand = target == null ? null : BoneAnchorLayer.position(target, BoneAnchorLayer.Bone.RIGHT_FINGERS);
                            if (hand != null) arcCentre = hand;
                            Vec3 blade = target == null ? m.forward.scale(-1) : target.getLookAngle().multiply(1, 0, 1).normalize();
                            Vec3 push = m.forward.cross(new Vec3(0, 1, 0)).normalize();
                            arcAxis = blade.add(0, .35, 0).normalize();
                            arcCentre = arcCentre.add(arcAxis.scale(.55)).add(push.scale(.6 * Math.min(1, age / 3)));
                        }
                        if (kind == BambooPayload.RETURN) {
                            hook(v, ps.last(), arcCentre, camera, arcAxis, age, arcFade);
                            remaining -= 15;
                        } else {
                            crescent(v, ps.last(), arcCentre, camera, arcAxis, 1.2 * size, .30 * size, arcFade);
                            remaining -= 12;
                        }
                        count = Math.min(count, remaining / 3);
                    }
                    if (kind == BambooPayload.RETURN) continue;
                }
                Vec3 right = m.forward.cross(new Vec3(0, 1, 0)).normalize();
                for (int i = 0; i < count; i++) {
                    double angle = i * 2.39996;
                    Vec3 radial = new Vec3(Math.cos(angle), 0, Math.sin(angle));
                    Vec3 at = kind == BambooPayload.GROVE ? centre : m.ground;
                    if (kind == BambooPayload.GROVE || kind == BambooPayload.COLLAPSE) {
                        angle = 2 * Math.PI * i / Math.max(1, count);
                        radial = right.scale(Math.cos(angle)).add(0, Math.sin(angle), 0);
                    }
                    Vec3 axis = radial.add(0, .4, 0).normalize().scale(.085);
                    if (kind == BambooPayload.ASIDE) {
                        Vec3 blade = target == null ? m.forward.scale(-1) : target.getLookAngle().multiply(1, 0, 1).normalize();
                        Vec3 hand = target == null ? null : BoneAnchorLayer.position(target, BoneAnchorLayer.Bone.RIGHT_FINGERS);
                        if (hand != null) at = hand;
                        axis = blade.add(0, .35, 0).normalize();
                        at = at.add(axis.scale(.15 + .95 * i / Math.max(1, count - 1)))
                                .add(right.scale(.6 * Math.min(1, age / 3)));
                    } else if (kind == BambooPayload.WALL) {
                        double fan = -1.25 + 2.5 * i / Math.max(1, count - 1);
                        Vec3 visibleRight = new Vec3(0, 1, 0).cross(camera.subtract(m.ground)).normalize();
                        axis = visibleRight.scale(Math.sin(fan)).add(0, Math.cos(fan), 0).scale(.16);
                        at = m.ground.add(axis.scale(1.05));
                    } else if (kind == BambooPayload.JAB) {
                        // Sword follow-through is exclusively the eight large leaves in ClassicBambooScale.
                        continue;
                    } else if (kind == BambooPayload.GROVE_RING) {
                        if (target == null) break;
                        double phase = angle + age * .006;
                        at = target.position().add(Math.cos(phase) * .7,
                                .15 + (i % 3) * .32 + (1 - ((age + i * 7) % 36) / 36) * .8, Math.sin(phase) * .7);
                        fade = Math.max(0, 1 - age / 30);
                    } else if (kind == BambooPayload.TRAIL) {
                        double phase = angle + m.born * .6 + age * .15;
                        if (m.p.duration() == 1) at = at.add(Math.cos(phase) * .18, Math.sin(phase) * .18, 0);
                        axis = m.forward.scale(.11);
                    } else if (kind == BambooPayload.GROUND) {
                        Vec3 crack = radial.scale(.4);
                        VfxDraw.segment(v, ps.last(), m.ground.subtract(crack.scale(.3)), m.ground.add(crack),
                                camera, .014, fade * .6F, .16F, .20F, .13F);
                        if (age < 10) {
                            Vec3 chip = m.ground.add(crack.scale(age * .2)).add(0, .07 * age - .006 * age * age, 0);
                            // API: LevelRenderer#getLightColor(BlockAndTintGetter, BlockPos).
                            int light = net.minecraft.client.renderer.LevelRenderer.getLightColor(mc.level, BlockPos.containing(chip));
                            chips.add(new Chip(chip, m.blockColour, fade * .65F, light));
                        }
                        continue;
                    } else {
                        // Fast ease-out bursts, independent of the slow falling ring.
                        boolean burst = kind == BambooPayload.COLLAPSE || kind == BambooPayload.GROVE;
                        double progress = Math.min(1, age / (kind == BambooPayload.GROVE ? 2 : 4));
                        double radius = burst ? (kind == BambooPayload.GROVE ? .5 : .9) * (1 - Math.pow(1 - progress, 3))
                                : .08 + age * (kind == BambooPayload.CONTACT ? .035 : .075);
                        at = at.add(radial.scale(radius));
                        if (!burst) at = at.add(0, .025 * age - .003 * age * age, 0);
                    }
                    double length = kind == BambooPayload.WALL ? .32 : kind == BambooPayload.COLLAPSE ? .24
                            : kind == BambooPayload.GROVE_RING || kind == BambooPayload.TRAIL ? .22 : .25;
                    double width = kind == BambooPayload.WALL || kind == BambooPayload.GROVE ? .12
                            : kind == BambooPayload.GROVE_RING || kind == BambooPayload.TRAIL ? .10 : .11;
                    leaf(v, ps.last(), at, axis.normalize().scale(length * size / 2), camera, width * size,
                            fade, (kind == BambooPayload.WALL || kind == BambooPayload.GROVE) && age < 2);
                }
                remaining -= count * 3;
            }
            // Finish one dynamic buffer before requesting another material.
            buffers.endBatch(type);
            if (!chips.isEmpty()) {
                var debris = buffers.getBuffer(debrisType);
                for (Chip chip : chips) VfxDraw.billboard(debris, ps.last(), chip.at, camera, .035, chip.alpha,
                        ((chip.colour >> 16) & 255) / 255F, ((chip.colour >> 8) & 255) / 255F,
                        (chip.colour & 255) / 255F, chip.light);
            }
        } finally { ps.popPose(); }
        buffers.endBatch(type);
        buffers.endBatch(debrisType);
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
    /** Sword-only chest, shoulder and belly pairs; Grove keeps its original anchors.
     * API: read-only minecraft-src Entity#position/getBbHeight/getBbWidth and LivingEntity#yBodyRot.
     */
    private static Vec3 swordPoint(net.minecraft.world.entity.LivingEntity foe, int point) {
        double yaw = Math.toRadians(foe.yBodyRot);
        Vec3 front = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 side = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        double height = point < 2 ? .67 : point < 4 ? .82 : .43;
        double width = point < 2 ? .24 : point < 4 ? .46 : .25;
        return foe.position().add(0, foe.getBbHeight() * height, 0)
                .add(side.scale(foe.getBbWidth() * width * (point % 2 == 0 ? -1 : 1)))
                .add(front.scale(foe.getBbWidth() * .52));
    }

    private static void track(BambooPayload p, long until) {
        if (trails.size() >= 32) {
            if (Boolean.getBoolean("murim.capture")) MurimMod.LOGGER.info(
                    "CAPTURE-BAMBOO-TRAIL projectile={} reason=tracking_capacity", p.target());
            return;
        }
        trails.put(p.target(), new Trail(p, until));
    }
    private static int lifetime(int kind) {
        return kind == BambooPayload.GROVE_RING ? ClassicBambooScale.groveLifetime() : kind == BambooPayload.GROUND ? 40
                : kind == BambooPayload.WALL ? 38 : kind == BambooPayload.JAB ? 30 : kind == BambooPayload.RETURN ? 30
                : kind == BambooPayload.GROVE ? 30 : kind == BambooPayload.COLLAPSE ? 30 : kind == BambooPayload.ASIDE ? 38
                : kind == BambooPayload.TRAIL ? 10 : 38;
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
    private static void leaf(com.mojang.blaze3d.vertex.VertexConsumer v, com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                             Vec3 at, Vec3 axis, Vec3 camera, double breadth, float alpha, boolean bright) {
        Vec3 normal = camera.subtract(at).normalize();
        Vec3 direction = axis.subtract(normal.scale(axis.dot(normal))).normalize();
        if (direction.lengthSqr() < .001) direction = normal.cross(new Vec3(0, 1, .1)).normalize();
        axis = direction.scale(axis.length());
        Vec3 width = direction.cross(normal).normalize().scale(breadth / 2);
        Vec3 base = at.subtract(axis), tip = at.add(axis);
        Vec3 shoulder = base.add(axis.scale(.65));
        VfxDraw.quad(v, pose, base, shoulder.add(width), tip, at, normal, alpha, bright ? new VfxColour(133/255F, 245/255F, 176/255F) : CORE);
        VfxDraw.quad(v, pose, base, at, tip, shoulder.subtract(width), normal, alpha, EDGE);
        VfxDraw.segment(v, pose, base.add(normal.scale(.001)), tip.add(normal.scale(.001)), camera,
                breadth * .10, alpha, 133/255F, 245/255F, 176/255F);
    }
    /** A constant-width ribbon in the incoming line's vertical plane, grown from its leading tip.
     * forward points back toward the shooter; the initial tangent opposes it, the final tangent returns to it.
     * API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/phys/Vec3.java vector operations.
     */
    private static void hook(com.mojang.blaze3d.vertex.VertexConsumer v, com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                             Vec3 hand, Vec3 camera, Vec3 forward, float age, float alpha) {
        Vec3 up = new Vec3(0, 1, 0).subtract(forward.scale(forward.y)).normalize();
        Vec3 normal = forward.cross(up).normalize();
        Vec3 centre = hand.add(forward.scale(.5)).add(up.scale(.485));
        double sweep = Math.toRadians(200) * Math.min(1, age / 3.0);
        for (int i = 0; i < 12; i++) {
            double a = sweep * i / 12, b = sweep * (i + 1) / 12;
            // Only the leading end tapers; the rest stays .13 blocks thick (.55 outer radius).
            double wa = .065 * Math.min(1, (sweep - a) / .35);
            double wb = .065 * Math.min(1, (sweep - b) / .35);
            Vec3 ra = forward.scale(-Math.sin(a)).add(up.scale(-Math.cos(a)));
            Vec3 rb = forward.scale(-Math.sin(b)).add(up.scale(-Math.cos(b)));
            VfxDraw.quad(v, pose, centre.add(ra.scale(.485 + wa)), centre.add(rb.scale(.485 + wb)),
                    centre.add(rb.scale(.485 - wb)), centre.add(ra.scale(.485 - wa)), normal, alpha, CORE);
        }
        Vec3 tip = centre.add(forward.scale(-Math.sin(sweep) * .485)).add(up.scale(-Math.cos(sweep) * .485));
        Vec3 tangent = forward.scale(-Math.cos(sweep)).add(up.scale(Math.sin(sweep)));
        leaf(v, pose, tip, tangent.scale(.09), camera, .08, alpha, true);
    }
    private static void crescent(com.mojang.blaze3d.vertex.VertexConsumer v, com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                                 Vec3 centre, Vec3 camera, Vec3 forward, double length, double width, float alpha) {
        Vec3 normal = camera.subtract(centre).normalize();
        Vec3 right = new Vec3(0, 1, 0).cross(normal).normalize();
        if (right.dot(forward.cross(new Vec3(0, 1, 0))) < 0) right = right.scale(-1);
        right = forward.subtract(normal.scale(forward.dot(normal))).normalize();
        Vec3 up = normal.cross(right).normalize();
        for (int i = 0; i < 12; i++) {
            double a = i / 12.0, b = (i + 1) / 12.0;
            Vec3 p = centre.add(right.scale((a - .5) * length)).add(up.scale(Math.sin(a * Math.PI) * width));
            Vec3 q = centre.add(right.scale((b - .5) * length)).add(up.scale(Math.sin(b * Math.PI) * width));
            VfxDraw.quad(v, pose, p, q, q.subtract(up.scale(Math.sin(b * Math.PI) * width)),
                    p.subtract(up.scale(Math.sin(a * Math.PI) * width)), normal, alpha, CORE);
        }
    }
    private BambooClassicVfx() { }
}
