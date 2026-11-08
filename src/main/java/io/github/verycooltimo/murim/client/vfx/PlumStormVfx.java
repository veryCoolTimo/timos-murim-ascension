package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.RiverPayload;
import io.github.verycooltimo.murim.technique.RiverRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;

/** Twenty-four Plum presentation, using the existing batched mesher and quality settings.
 * API verified against /tmp/mr-ref-1121/minecraft-src/net/minecraft/:
 * world/phys/Vec3.java (vector operations), client/{Minecraft,Options,OptionInstance,Camera,DeltaTracker}.java,
 * client/renderer/{RenderBuffers,MultiBufferSource}.java; NeoForge client/event/{ClientTickEvent,
 * RenderLevelStageEvent}.java in /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/.
 * Reference frame paths below are relative to docs/design/reference/techniques/.
 * PoseStack operations are verified by vanilla EntityRenderDispatcher.java call sites (the Mojang
 * library declaration is absent from the supplied tree). ModConfigSpec.ConfigValue#get is verified
 * in neoforge/common/ModConfigSpec.java. Existing rendering helpers remain unchanged.
 * All clocks are presentation clocks; damage, hit times, reach and cooldowns remain server owned.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class PlumStormVfx {
    enum Flow { RAIN, VORTEX, RIVER, BURST, BLOOM }
    private static final VfxColour WHITE = colour(0xFFF6FA), EDGE = colour(0xC92353);
    private static final VfxColour BLUE = colour(0xDBF7FF), BLUE_EDGE = colour(0x76CFFF);
    private static final int[][] PALETTES = {
            {0xF78BAE, 0xE64A80, 0xFFD5E3}, {0xF78BB3, 0xE85591, 0xBD295D},
            {0xF56F9F, 0xD93670, 0xFFE0EC}, {0xF777A4, 0xF777A4, 0xFFD3E4},
            {0xFFB3D0, 0xDA3D76, 0xFFF8FC}};
    private static final List<Storm> STORMS = new ArrayList<>();
    private static final List<Contact> CONTACTS = new ArrayList<>();
    private static final List<Preparation> PREPARATIONS = new ArrayList<>();
    private static ClientLevel level;
    private record PetalContact(Vec3 at, int tick) {}
    private static final class Storm {
        final int owner, count, tier, stars;
        final SeaScatterResidue residue = new SeaScatterResidue();
        boolean crimsonFinish() { return flow == Flow.VORTEX || flow == Flow.BURST; }
        final PlumLayers.Form form;
        boolean has(PlumLayers.Kind kind) { return PlumLayers.has(form, stars, kind); }
        final PlumLayerCapture capture = new PlumLayerCapture();
        final Flow flow;
        final double scale;
        Vec3 centre, forward, bud;
        Vec3 casterFeet, destination;
        Vec3 bloomBase, bloomTip;
        TechniqueLight.Light rootLight, trunkLight;
        Vec3 riverOrigin, riverRender, riverVelocity = new Vec3(0, 0, 0);
        double riverFrame = -1;
        double reach;
        int node = -1, burst = -1;
        String dissolveCause = "none";
        final List<Vec3> bladePath = new ArrayList<>();
        final java.util.Map<Integer, PetalContact> petalContacts = new java.util.HashMap<>();
        final List<net.minecraft.world.entity.LivingEntity> neighbours = new ArrayList<>();
        int age, ending = -1, release = -1;
        Storm(int owner, int tier, int stars, Flow flow, Vec3 centre, Vec3 forward) {
            this.owner = owner;
            this.tier = tier;
            this.stars = stars;
            this.form = switch (flow) {
                case RAIN -> PlumLayers.Form.RAIN; case RIVER -> PlumLayers.Form.RIVER;
                case BURST -> PlumLayers.Form.SCATTER; case VORTEX -> PlumLayers.Form.SEA;
                case BLOOM -> PlumLayers.Form.BLOOM;
            };
            this.flow = flow;
            this.centre = centre;
            this.forward = direction(forward);
            this.bud = centre.add(this.forward.scale(1.5));
            this.casterFeet = centre;
            this.destination = centre.add(this.forward.scale(8));
            var caster = level.getEntity(owner);
            if (caster != null) {
                this.casterFeet = caster.position();
                if (flow == Flow.RAIN) {
                    // Ch.210: the fighter is the funnel's heart. The marked enemy remains the
                    // authoritative contact destination, four blocks behind the completed dash.
                    this.centre = caster.position();
                    this.forward = direction(centre.subtract(this.centre));
                    this.bud = this.centre.add(this.forward.scale(1.5));
                }
                Vec3 tip = BoneAnchorLayer.position(caster, BoneAnchorLayer.Bone.BLADE_TIP);
                if (tip != null) this.bud = tip;
            }
            // [НЕПРОВЕРЕНО: initial blade anchor availability; inspect the first bud in the film.]
            scale = PlumLayers.scale(form, stars);
            int maximum = switch (flow) {
                case RAIN -> 2400;
                case VORTEX -> PlumPolish.seaSlots();
                case RIVER -> RiverStormTimeline.APEX_SLOTS;
                case BURST -> 480;
                case BLOOM -> 700;
            };
            // Exact mastery controls presentation density; execution tier remains the gameplay index.
            count = quality((int) (maximum * PlumLayers.density(form, stars)));
        }
    }
    private static final class Contact {
        final Vec3 at, forward;
        final int owner;
        final double scale;
        final Storm storm;
        final PlumLayerCapture capture = new PlumLayerCapture();
        int age;
        boolean defensive;
        Contact(Storm storm, Vec3 at) {
            this.storm = storm; this.owner = storm.owner; this.at = at;
            this.forward = storm.forward; this.scale = storm.scale;
        }
    }
    private static final class Preparation {
        final int owner;
        final List<Vec3> tips = new ArrayList<>();
        Vec3 blade = new Vec3(0, 0, 1);
        int age;
        Preparation(int owner) { this.owner = owner; }
    }
    private static VfxColour colour(int rgb) {
        return new VfxColour((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F);
    }
    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private static double smooth(double value) { double u = clamp(value); return u * u * (3 - 2 * u); }
    // Stable independent coordinates: do not correlate radius/height with one of four ribbon lanes.
    private static double seed(int i, int salt) {
        double n = Math.sin(i * 127.1 + salt * 311.7) * 43758.5453;
        return n - Math.floor(n);
    }
    private static Vec3 direction(Vec3 v) { return v.lengthSqr() < 1E-6 ? new Vec3(0, 0, 1) : v.normalize(); }
    private static int quality(int count) {
        count = switch (io.github.verycooltimo.murim.client.ClientConfig.VFX_QUALITY.get()) {
            case HIGH -> count;
            case MEDIUM -> count * 2 / 3;
            case LOW -> Math.max(8, count / 3);
        };
        return switch (Minecraft.getInstance().options.particles().get()) {
            case ALL -> count;
            case DECREASED -> count / 2;
            case MINIMAL -> Math.max(12, count / 8);
        };
    }
    private static void changeLevel() {
        ClientLevel current = Minecraft.getInstance().level;
        if (current != level) {
            STORMS.clear(); CONTACTS.clear(); PREPARATIONS.clear(); level = current;
        }
    }
    static void prepare(int owner) {
        changeLevel();
        PREPARATIONS.removeIf(p -> p.owner == owner);
        if (level != null && PREPARATIONS.size() < 12) PREPARATIONS.add(new Preparation(owner));
    }
    static void start(int owner, int tier, int stars, Flow flow, Vec3 centre, Vec3 forward) {
        changeLevel();
        if (tier <= 0 || level == null) return;
        for (Storm s : STORMS) if (s.owner == owner && s.flow == flow && s.ending < 0) {
            // AIM refines the blade-local gather's direction without restarting its 20-tick clock.
            s.forward = direction(forward);
            if (flow == Flow.BURST) s.centre = centre;
            return;
        }
        if ((flow == Flow.VORTEX || flow == Flow.BURST)
                ? STORMS.stream().filter(s -> s.ending < 0).count() < 6 && STORMS.size() < 24
                : STORMS.size() < 6) {
            STORMS.add(new Storm(owner, tier, stars, flow, centre, forward));
            if (flow != Flow.RIVER) TechniqueLight.add(centre.add(0, 1, 0), (flow == Flow.VORTEX || flow == Flow.BURST ? 0xE3264E : 0xF6AECF), 4, (.28F) * PlumLayers.light(stars), 4, 2, 12);
        }
    }
    /** Presentation only: use the server's clipped, already reached front. */
    static void bloomFront(int owner, Vec3 origin, Vec3 forward, double reach) {
        for (Storm s : STORMS) if (s.owner == owner && s.flow == Flow.BLOOM && s.ending < 0) {
            s.centre = origin;
            s.forward = direction(forward);
            s.reach = Math.max(0, reach);
        }
    }
    private static boolean riverCapture() {
        return Boolean.getBoolean("murim.capture") && "1".equals(System.getenv("MURIM_CAPTURE_PLUM_CAMERA"));
    }
    static void riverStage(int owner, int tier, int stars, Vec3 hand, Vec3 target, int stage) {
        boolean found = false;
        for (Storm s : STORMS) if (s.owner == owner && s.flow == Flow.RIVER) {
            found = true;
            if (stage == RiverPayload.BURST && riverCapture())
                MurimMod.LOGGER.info("CAPTURE-PLUM-RIVER storm_found=true active={} age={} endAge={} dissolve={} burstBefore={}",
                        s.ending < 0, s.age, s.ending, s.dissolveCause, s.burst);
            s.centre = hand;
            s.destination = target;
            s.forward = direction(target.subtract(hand));
            if (stage == RiverPayload.CONTACT) {
                s.node = s.age;
                TechniqueLight.add(target, 0xFF3475, 7, (.8F) * PlumLayers.light(s.stars), 2, 5, 16);
                TechniqueLight.add(target, 0xFFFFFF, 4, (.65F) * PlumLayers.light(s.stars), 2, 4, 14);
                TechniqueLight.add(s.casterFeet.add(0, 1, 0), 0xFF8FB5, 4, (.5F) * PlumLayers.light(s.stars), 2, 4, 14);
            }
            if (stage == RiverPayload.BURST) {
                s.forward = direction(s.forward.add(0, .65, 0));
                s.burst = s.age;
                TechniqueLight.add(target, 0xFF3475, 10, (1F) * PlumLayers.light(s.stars), 1, 4, 24);
                TechniqueLight.add(target, 0xFFFFFF, 6, (.9F) * PlumLayers.light(s.stars), 1, 3, 18);
                TechniqueLight.add(s.casterFeet.add(0, 1, 0), 0xFF8FB5, 5, (.65F) * PlumLayers.light(s.stars), 1, 3, 22);
                s.ending = -1;
            }
        }
        if (!found && stage == RiverPayload.BURST) {
            if (riverCapture()) MurimMod.LOGGER.info("CAPTURE-PLUM-RIVER storm_found=false owner={} recovering=true", owner);
            // A confirmed late packet owns its payoff even if an earlier END already cleaned up.
            start(owner, tier, stars, Flow.RIVER, hand, target.subtract(hand));
            for (Storm s : STORMS) if (s.owner == owner && s.flow == Flow.RIVER) {
                s.destination = target;
                s.forward = direction(s.forward.add(0, .65, 0));
                s.age = 60; // Same mature population as the normal T60 → T120 path.
                s.burst = s.age;
            }
        }
    }
    static void releaseBloom(int owner) {
        for (Storm s : STORMS) if (s.owner == owner && s.flow == Flow.BLOOM && s.release < 0) s.release = s.age;
    }
    static void defendBloom(int owner, Vec3 attacker) {
        changeLevel();
        for (Storm s : STORMS) if (s.owner == owner && s.flow == Flow.BLOOM && bloomEnd(s) < 0) {
            Vec3 outward = direction(attacker.subtract(s.casterFeet.add(0, 1, 0)));
            Vec3 at = s.casterFeet.add(0, 1.2, 0).add(outward.scale(1.1));
            if (CONTACTS.size() < 48) {
                Contact c = new Contact(s, at);
                c.defensive = true;
                CONTACTS.add(c);
            }
            TechniqueLight.add(attacker.add(0, 1, 0), 0xFFD3E4, 3, (.7F) * PlumLayers.light(s.stars), 1, 1, 8);
            return;
        }
    }
    static void impact(int owner, Vec3 point) {
        changeLevel();
        for (int i = STORMS.size() - 1; i >= 0; i--) {
            Storm s = STORMS.get(i);
            if (s.owner != owner || s.stars < 9) continue;
            // rain-22/25 and river-14: convergence belongs to the actual server-reported contact.
            if (CONTACTS.stream().anyMatch(c -> c.owner == owner && !c.defensive && c.age == 0)) return;
            if (CONTACTS.size() < 48) CONTACTS.add(new Contact(s, point));
            if (s.flow != Flow.RIVER) {
                TechniqueLight.add(point, s.crimsonFinish() ? 0xE3264E : 0xFF6FA8, 6, (.9F) * PlumLayers.light(s.stars), 1, 1, 10);
                TechniqueLight.add(s.casterFeet.add(0, 1, 0), s.crimsonFinish() ? 0xE3264E : 0xF6AECF, 3, (.45F) * PlumLayers.light(s.stars), 1, 1, 8);
            }
            return;
        }
    }
    static void dissolve(int owner) { dissolve(owner, "other-form-END"); }
    static void dissolve(int owner, String cause) {
        for (Storm s : STORMS) if (s.owner == owner && s.ending < 0) {
            s.ending = s.age;
            s.dissolveCause = cause;
        }
    }
    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        changeLevel();
        Minecraft mc = Minecraft.getInstance();
        if (level == null || mc.isPaused()) return;
        for (Storm s : STORMS) {
            s.age++;
            // API: ClientLevel#getEntity/entitiesForRendering; Entity#position/isAlive/getId;
            // LivingEntity#swinging. Read-only 1.21.1 sources, paths in class documentation.
            var caster = level.getEntity(s.owner);
            if (caster != null) {
                if (s.flow != Flow.BLOOM || bloomEnd(s) < 0) s.casterFeet = caster.position();
                if (s.flow == Flow.RIVER && s.age < 40) {
                    Vec3 hand = BoneAnchorLayer.position(caster, BoneAnchorLayer.Bone.RIGHT_HAND);
                    if (hand != null) s.centre = hand;
                }
                Vec3 tip = BoneAnchorLayer.position(caster, BoneAnchorLayer.Bone.BLADE_TIP);
                if (tip != null && s.age <= 16) {
                    s.bud = tip;
                    s.bladePath.add(tip);
                    if (s.bladePath.size() > 8) s.bladePath.remove(0);
                }
            }
            if (s.flow == Flow.BLOOM && bloomEnd(s) < 0) {
                if (s.age % 8 == 1) {
                    s.rootLight = TechniqueLight.add(s.casterFeet.add(0, .12, 0), 0xF777A4, 4.5F, (.55F) * PlumLayers.light(s.stars), 4, 4, 12);
                    s.trunkLight = TechniqueLight.add(s.casterFeet.add(0, 1.1, 0), 0xFFD3E4, 2.2F, (.3F) * PlumLayers.light(s.stars), 4, 4, 12);
                }
                if (s.rootLight != null) s.rootLight.moveTo(s.casterFeet.add(0, .12, 0));
                if (s.trunkLight != null) s.trunkLight.moveTo(s.casterFeet.add(0, 1.1, 0));
            }
            if (s.flow == Flow.RIVER && s.age >= 40 && s.riverOrigin == null) s.riverOrigin = s.riverRender != null ? s.riverRender : s.centre;
            s.neighbours.clear();
            for (var actor : level.entitiesForRendering()) {
                if (actor instanceof net.minecraft.world.entity.LivingEntity living
                        && living.getId() != s.owner && living.isAlive()
                        && living.position().distanceToSqr(s.centre) < 196 && s.neighbours.size() < 16) {
                    s.neighbours.add(living);
                }
            }
            collideLoosePetals(s);
            if (s.crimsonFinish()) {
                s.residue.tick(s.age);
                if (s.has(PlumLayers.Kind.PETALS) && s.ending >= 0 && s.age - s.ending < 32) {
                    int slot = (s.age - s.ending) * 2;
                    for (int k = 0; k < 2; k++) {
                        int sample = slot + k;
                        if (sample < s.count && PlumLayers.petalSlot(s.form, s.stars, sample, s.count))
                            s.residue.land(position(s, sample, s.ending), s.age, sample, (.05 + .06 * seed(sample, 7)) * s.scale);
                    }
                }
                if (s.ending < 0 && s.age % 8 == 0) {
                    TechniqueLight.add(s.casterFeet.add(0, 1, 0), 0xE3264E, 4, .3F * PlumLayers.light(s.stars), 3, 3, 6);
                    TechniqueLight.add(s.centre.add(0, .5, 0), 0xE3264E, 6, .4F * PlumLayers.light(s.stars), 3, 3, 6);
                }
            }
            int end = switch (s.flow) {
                // Rain starts at MARK=66: downpour at 144, matching authoritative cohorts.
                case RAIN -> PlumBeats.RAIN.end();
                case VORTEX -> PlumBeats.SEA.end();
                case RIVER -> 110; // T60 + 110 = spec T170; retain the complete falling tail.
                case BURST -> PlumBeats.SCATTER.end();
                case BLOOM -> s.release < 0 ? 150 : s.release + 36;
            };
            if (s.age >= end && s.ending < 0) {
                s.ending = s.age;
                s.dissolveCause = "automatic-" + s.flow;
            }
        }
        for (Contact c : CONTACTS) c.age++;
        for (Preparation p : PREPARATIONS) {
            p.age++;
            if (level.getEntity(p.owner) instanceof net.minecraft.client.player.AbstractClientPlayer player) {
                Vec3 tip = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.BLADE_TIP);
                Vec3 hand = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.RIGHT_HAND);
                if (tip != null) {
                    p.tips.add(tip);
                    if (hand != null) p.blade = direction(tip.subtract(hand));
                }
                if (p.tips.size() > 4) p.tips.remove(0);
            }
        }
        PREPARATIONS.removeIf(p -> p.age > 11);
        CONTACTS.removeIf(c -> c.age > 40);
        STORMS.removeIf(s -> RiverStormTimeline.expired(s.age, s.ending, s.burst) && (!s.crimsonFinish() || s.residue.empty()));
    }
    /** Tick-only collision for loose petals, including River after the confirmed burst. */
    private static void collideLoosePetals(Storm s) {
        if (s.flow != Flow.BLOOM && s.flow != Flow.RIVER) return; // Keep Sea's build population circulating.
        if (s.flow == Flow.RIVER && s.burst < 0) return;
        if (s.flow == Flow.BLOOM && (bloomEnd(s) < 0 || s.age <= bloomEnd(s))) return;
        for (int i = 0; i < s.count; i++) {
            if (s.petalContacts.containsKey(i)) continue;
            Vec3 from = position(s, i, s.age - 1), to = position(s, i, s.age);
            if (from.distanceToSqr(to) > 4) continue; // Sea's faded wrap is a new incoming cohort.
            if (!level.hasChunkAt(net.minecraft.core.BlockPos.containing(from))
                    || !level.hasChunkAt(net.minecraft.core.BlockPos.containing(to))) continue;
            Vec3 contact = null;
            var block = level.clip(new net.minecraft.world.level.ClipContext(from, to,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
                    net.minecraft.world.phys.shapes.CollisionContext.empty()));
            if (block.getType() != net.minecraft.world.phys.HitResult.Type.MISS) contact = block.getLocation();
            for (var actor : s.neighbours) {
                var body = actor.getBoundingBox();
                var hit = body.clip(from, to);
                Vec3 at = body.contains(from) ? from : hit.orElse(null);
                if (at != null && (contact == null || from.distanceToSqr(at) < from.distanceToSqr(contact))) contact = at;
            }
            if (contact != null) s.petalContacts.put(i, new PetalContact(contact, s.age));
        }
    }

    /** 6 Nov: straight thin sword trails, matching Rainfall's dash trail. */
    private static Vec3 cutPoint(Storm s, int stroke, double u, double length) {
        Vec3 side = new Vec3(-s.forward.z, 0, s.forward.x);
        double sign = stroke % 2 == 0 ? 1 : -1;
        return s.centre.add(s.forward.scale((u - .25) * length * .65 * s.scale))
                .add(side.scale((u - .5) * length * .72 * sign * s.scale))
                .add(0, (2.4 + sign * (u - .5) * 3) * s.scale, 0);
    }
    /** rain-09 (enclosing spiral), river-10/14 (gather/release), full-19 (petals on cuts). */
    private static Vec3 position(Storm s, int i, double t) {
        double phase = i * 2.399963;
        int lane = i % 4;
        double currentAlong = ((i / 4) + .5) / Math.ceil(s.count / 4.0);
        double curl = currentAlong * Math.PI * 1.55 + t * (.032 + lane * .003) + lane * 1.57;
        Vec3 side = new Vec3(-s.forward.z, 0, s.forward.x);
        double a, r;
        switch (s.flow) {
            case RAIN -> {
                // One broad, almost horizontal sky spiral. It hangs before any petal falls.
                // Keep its low white eye in the existing close side camera's upper field;
                // the far canopy spreads overhead rather than requiring a camera pullback.
                double h = seed(i, 1), depth = seed(i, 2);
                double fallAt = PlumBeats.RAIN.strike() + seed(i, 4) * 28;
                a = phase + h * 4.8 + Math.min(t, fallAt) * .012;
                r = 1.5 + Math.sqrt(depth) * 8.5;
                Vec3 sky = s.centre.add(Math.cos(a) * r * s.scale,
                        (3.4 + h * .9 + Math.sin(a * 2) * .25) * s.scale,
                        Math.sin(a) * r * s.scale);
                // Each path has its own start and takes 24 ticks to reach the ground.
                // Falling continues through T196; no reset and no local explosion.
                double fall = clamp((t - fallAt) / 24);
                double angle = Math.floor(seed(i, 10) * 7) * Math.PI * 2 / 7
                        + seed(i, 12) * .55;
                double radius = (2.1 + seed(i, 6) * .6) * s.scale;
                Vec3 outward = new Vec3(Math.cos(angle), 0, Math.sin(angle));
                Vec3 destination = s.centre.add(s.forward.scale(4)).add(outward.scale(radius)).add(0, .15, 0);
                if (fall < 1) return sky.lerp(destination, fall);
                double age = Math.max(0, t - fallAt - 24);
                double travel = 1.8 * (1 - Math.exp(-age / 7));
                double height = age * (.24 + seed(i, 17) * .08) - age * age * .014;
                return destination.add(outward.scale(travel)).add(0, height, 0);
            }
            case VORTEX -> {
                // Three loose planes, five travelling curved crests; each parcel owns its phase.
                double angle = seed(i, 2) * Math.PI * 2;
                double radius = 9 * Math.sqrt(seed(i, 3));
                double clock = Math.max(0, t - SeaPetalMotion.delay(i));
                double seaPhase = SeaPetalMotion.phase(i) + clock * .06 * SeaPetalMotion.speed(i);
                if (s.has(PlumLayers.Kind.BOW_WAVE) && i % 3 != 0) {
                    int crest = i % 5;
                    angle = crest * 1.2566 + (seed(i, 2) - .5) * 1.8;
                    radius = PlumPolish.crestRadius(crest, clock) + (seed(i, 3) - .5) * 1.25;
                }
                return s.centre.add(s.forward.scale(Math.cos(angle) * radius))
                        .add(side.scale(Math.sin(angle) * radius))
                        .add(0, PlumPolish.seaHeight(i, seaPhase), 0);
            }
            case RIVER -> {
                Vec3 riverSide = direction(s.forward.cross(new Vec3(0, 1, 0)));
                Vec3 up = direction(riverSide.cross(s.forward));
                if (s.burst >= 0) {
                    var point = RiverStormTimeline.point(i, t - s.burst, s.scale);
                    return s.destination.add(s.forward.scale(point.along()))
                            .add(riverSide.scale(point.side())).add(up.scale(point.up()));
                }
                if (s.node >= 0) {
                    double radius = .65 * (1 - smooth((t - s.node) / 9)) + .035;
                    return s.destination.add(riverSide.scale(Math.cos(phase) * radius))
                            .add(up.scale(Math.sin(phase) * radius));
                }
                double gatherU = seed(i, 1);
                double gatherRadius = (1.65 - 1.05 * smooth((t - 20) / 20)) * (.2 + .8 * gatherU) * s.scale;
                double spin = i % 3 * 2.1 + gatherU * 5 + t * .12;
                Vec3 origin = s.riverOrigin != null ? s.riverOrigin : s.riverRender != null ? s.riverRender : s.centre;
                Vec3 gather = origin.add(riverSide.scale(Math.cos(spin) * gatherRadius))
                        .add(up.scale(Math.sin(spin) * gatherRadius))
                        .add(s.forward.scale((gatherU - .5) * .7 * s.scale));
                if (t < 40) return gather;
                double u = RiverStormTimeline.streamProgress(i, t, RiverRules.flight(origin.distanceTo(s.destination)));
                double radius = (1 - u) * .6 * (.2 + .8 * gatherU) * s.scale;
                double releaseAngle = i % 3 * 2.1 + gatherU * 5 + 40 * .12;
                return origin.lerp(s.destination, u)
                        .add(riverSide.scale(Math.cos(releaseAngle) * radius))
                        .add(up.scale(Math.sin(releaseAngle) * radius))
                        .add(s.forward.scale((gatherU - .5) * .7 * s.scale * (1 - u)));
            }
            case BURST -> {
                int stroke = i % 8;
                double age = t - (stroke == 7 ? 92 : 40 + stroke * 3);
                double u = clamp(age / 3) * (.25 + .75 * (i / 8) / (double) Math.max(1, (s.count + 7) / 8 - 1));
                Vec3 cut = cutPoint(s, stroke, u, stroke == 7 ? 9 : 6.5)
                        .add(side.scale(Math.sin(curl) * clamp(age / 8) * (.5 + lane * .3) * s.scale))
                        .add(0, Math.sin(curl * 1.3) * clamp(age / 8) * s.scale
                                - Math.max(0, age - 4) * .012, 0);
                // The strokes feed one continuous engulfing storm; retain a third on the cuts.
                a = phase + t * (.055 + seed(i, 3) * .04);
                r = 2.4 + Math.sqrt(seed(i, 2)) * 3.8;
                Vec3 storm = s.centre.add(s.forward.scale(1.5 * s.scale))
                        .add(Math.cos(a) * r * s.scale, (.3 + seed(i, 1) * 2.5 + Math.sin(a * 3) * .3) * s.scale,
                                Math.sin(a) * r * s.scale);
                // The enclosing shape holds first; sword strokes then tear petals from its lobes.
                return storm.lerp(cut, i % 3 == 0 ? smooth(age / 5) : 0);
            }
            case BLOOM -> {
                // Half the unchanged population opens directly on the live blade; the rest
                // forms an irregular crown around the swordsman, independent of server reach.
                double u = .08 + .90 * seed(i, 1);
                Vec3 at = i % 2 == 0
                        ? (s.bloomBase == null ? s.bud : s.bloomBase).lerp(
                                s.bloomTip == null ? s.bud : s.bloomTip, u)
                        : s.casterFeet.add(Math.cos(phase) * (1 + seed(i, 2) * .85),
                                .65 + seed(i, 3) * 2.0, Math.sin(phase) * (1 + seed(i, 2) * .85));
                double end = bloomEnd(s);
                if (end >= 0) {
                    double pass = Math.max(0, t - end - seed(i, 26) * 3);
                    at = at.add(s.forward.scale(pass * .035))
                            .add(side.scale(Math.sin(phase) * pass * .045)).add(0, -pass * pass * .006, 0);
                }
                return at;
            }
        }
        throw new IllegalStateException();
    }
    /** Read after entity rendering, every frame; never interpolate a tick-old blade path. */
    private static void followBloomBlade(Storm s) {
        if (s.flow != Flow.BLOOM || bloomEnd(s) >= 0) return;
        var caster = level.getEntity(s.owner);
        if (caster == null) { s.bloomBase = null; s.bloomTip = null; return; }
        s.bloomBase = BoneAnchorLayer.position(caster, BoneAnchorLayer.Bone.BLADE_BASE);
        s.bloomTip = BoneAnchorLayer.position(caster, BoneAnchorLayer.Bone.BLADE_TIP);
    }

    private record Emission(Vec3 at, double size, int cell, float spin, float alpha, VfxColour tint) {}

    private static void emit(List<Emission> out, Storm s, Emission e) {
        float intensity = PlumLayers.light(s.stars);
        if (intensity > 0) out.add(new Emission(e.at(), e.size(), e.cell(), e.spin(), e.alpha() * intensity, e.tint()));
    }

    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != level) return;
        var stack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var buffers = mc.renderBuffers().bufferSource();
        List<Emission> emission = new ArrayList<>();
        stack.pushPose();
        try {
            stack.translate(-camera.x, -camera.y, -camera.z);
            var footDust = buffers.getBuffer(MurimRenderTypes.dustPuffs());
            for (Storm s : STORMS) bloomFeet(footDust, stack.last(), camera, s, s.age + partial, true);
            buffers.endBatch(MurimRenderTypes.dustPuffs());
            var bands = buffers.getBuffer(MurimRenderTypes.airBand());
            for (Storm s : STORMS) bloomFeet(bands, stack.last(), camera, s, s.age + partial, false);
            for (Storm s : STORMS) {
                followBloomBlade(s);
                capture(s, "structure");
                if (s.crimsonFinish()) finishGround(bands, stack.last(), camera, s, s.age + partial);
                PlumLayerCapture.layer("PlumStormVfx#canopy");
                canopy(bands, stack.last(), camera, s, s.age + partial);
                PlumLayerCapture.layer("PlumStormVfx#bloomTree");
                bloomTree(bands, stack.last(), camera, s, s.age + partial);
                s.capture.end();
            }
            for (Preparation p : PREPARATIONS) {
                // river-01: energy follows the blade, not an upright ground-rooted aura.
                if (p.tips.size() < 2) continue;
                for (int j = 0; j < 3; j++) {
                    Vec3[] path = new Vec3[p.tips.size()];
                    double[] width = new double[path.length];
                    for (int k = 0; k < path.length; k++) {
                        double u = k / (double) (path.length - 1);
                        path[k] = p.tips.get(path.length - 1).add(p.blade.scale(-(1 - u) * (1.5 + j * .4)));
                        width[k] = Math.sin(Math.PI * u) * (.025 + j * .01);
                    }
                    PlumVfx.strip(bands, stack.last(), camera, path, width, (float) clamp((11 - p.age - partial) / 3), BLUE_EDGE);
                    PlumVfx.strip(bands, stack.last(), camera, path, PlumVfx.scale(width, .45), (float) clamp((11 - p.age - partial) / 3), BLUE);
                }
            }
            buffers.endBatch(MurimRenderTypes.airBand());
            var petals = buffers.getBuffer(MurimRenderTypes.plumPetals());
            for (Storm s : STORMS) {
                petals = buffers.getBuffer(MurimRenderTypes.plumPetals());
                capture(s, "petals");
                PlumLayerCapture.layer("PlumStormVfx#render.stormPetals(position/blossom)");
                double t = s.age + partial;
                if (s.flow == Flow.RIVER) followRiverHand(s, t);
                double local = s.flow == Flow.RAIN && s.scale <= .251 ? t - 72 : t;
                List<Vec3> cutGap = cutSamples(s, local);
                // Use the oldest still-live contact: cohorts must not restart the same flash each tick.
                Contact contact = null;
                for (Contact c : CONTACTS) if (c.storm == s) { contact = c; break; }
                if (s.flow == Flow.RIVER && s.has(PlumLayers.Kind.PETALS) && s.ending < 0 && s.burst < 0) {
                    Vec3 heart = s.node >= 0 ? s.destination : s.centre;
                    double radius = s.node >= 0 ? .055 : .10 * smooth(t / 8);
                    PlumVfx.petal(petals, stack.last(), camera, heart, radius, 0, 0, .95F, 1, 1, 1);
                }
                boolean debugBurst = riverCapture() && s.flow == Flow.RIVER && s.burst >= 0 && s.age - s.burst <= 20;
                int burstPoints = 0;
                double minAlong = Double.POSITIVE_INFINITY, maxAlong = Double.NEGATIVE_INFINITY;
                double minAlpha = 1, maxAlpha = 0, maxRadius = 0;
                for (int i = 0; i < s.count; i++) {
                    if (!PlumLayers.petalSlot(s.form, s.stars, i, s.count)) continue;
                    if (s.stars < 10 && i >= 8) continue; // DETACH: a few fragments, no sea or clone field.
                    double size = (.05 + .11 * seed(i, 7)) * s.scale;
                    double flowerSize = (.13 + seed(i, 8) * .32) * s.scale;
                    double fade = clamp((local - i % (s.scale <= .251 ? 6 : 16)) / 4);
                    if (s.flow == Flow.RIVER && s.node >= 0 && s.burst < 0) {
                        fade *= .22 * (1 - smooth((t - s.node) / 9));
                        flowerSize *= .1;
                    }
                    if (s.flow == Flow.RIVER && s.burst >= 0)
                        fade *= RiverStormTimeline.point(i, t - s.burst, s.scale).alpha();
                    if (s.flow == Flow.VORTEX) {
                        double clock = s.ending < 0 ? t : s.ending;
                        double motion = SeaPetalMotion.clock(i, clock, seed(i, 3) * 14.4 - 7.2);
                        double u = (seed(i, 3) + motion * .012) % 1;
                        double edgeFade = smooth(u / .07) * smooth((1 - u) / .07);
                        double crest = smooth((clock - 52) / 12) * (1 - smooth((clock - 78) / 12));
                        fade *= edgeFade + (1 - edgeFade) * crest;
                    }
                    if (s.flow == Flow.BURST) fade *= smooth((t - seed(i, 9) * 8) / 8);
                    boolean growing = s.flow == Flow.BLOOM || s.flow == Flow.RAIN && s.scale > .251;
                    // Ch.327: first a single closed bud, then a sequential, accelerating grove.
                    double birth = s.flow == Flow.BLOOM ? 12 + seed(i, 32) * 24
                            : 12 + (i == 0 ? 0 : 4 + 20 * Math.sqrt(i / (double) s.count));
                    double birthClock = s.flow == Flow.BLOOM && bloomEnd(s) >= 0
                            ? Math.min(local, bloomEnd(s)) : s.ending < 0 ? local : Math.min(local, s.ending);
                    double opening = clamp((birthClock - birth) / 9);
                    if (growing) fade = clamp((birthClock - birth) / 3);
                    Vec3 at;
                    VfxColour tint = s.flow == Flow.RAIN && s.scale <= .251
                            ? colour(i % 9 == 0 ? 0xFFF4FA : i % 3 == 0 ? 0xD9477C : 0xF582AD)
                            : colour(PALETTES[s.flow.ordinal()][i % 9 == 0 ? 2 : i % 3 == 0 ? 1 : 0]);

                    if (s.flow == Flow.RIVER && s.burst >= 0) {
                        double cooling = smooth((t - s.burst - 10) / 24);
                        if (i % 32 == 0 || i % 5 == 0)
                            tint = new VfxColour(1, (float) (1 - cooling * .42), (float) (1 - cooling * .22));
                    }
                    if (s.flow == Flow.VORTEX) size *= .65 + 1.65 * seed(i, 25);
                    if (s.flow == Flow.BLOOM) tint = colour(i % 9 == 0 ? 0xFFD3E4 : i % 3 == 0 ? 0xE85591 : 0xF777A4);
                    if (s.flow == Flow.BLOOM && bloomEnd(s) >= 0) {
                        fade *= 1 - smooth((t - bloomEnd(s) - seed(i, 26) * 3) / 18);
                        size *= .5 + .5 * fade;
                    }

                    if (s.ending >= 0 && s.flow != Flow.BLOOM && !(s.flow == Flow.RAIN && s.scale > .251) && !(s.flow == Flow.RIVER && s.burst >= 0)) {
                        double tail = t - s.ending;
                        fade *= 1 - smooth((tail - 8 - seed(i, 19) * 24) / 8);
                        Vec3 out = new Vec3(Math.cos(i * 2.399963), 0, Math.sin(i * 2.399963));
                        at = position(s, i, s.flow == Flow.RAIN && s.scale <= .251 ? s.ending - 72 : s.ending);
                        if (growing && s.flow != Flow.BLOOM) at = s.bud.lerp(at, opening * opening * (3 - 2 * opening));
                        if (s.flow == Flow.BURST && "Scatter-flock".equals(s.dissolveCause)) {
                            // Hand the silhouette to the collision-simulated flock, without a gravity tail.
                            fade *= 1 - smooth(tail / 2);
                        } else {
                            at = at.add(out.scale(tail * (.025 + size * .2)));
                            at = new Vec3(at.x, Math.max(s.centre.y + .06, at.y - tail * (.025 + size * .2)), at.z);
                        }
                        tint = colour(i % 3 == 0 ? 0xD95386 : 0xF59ABB);
                    } else at = position(s, i, local);
                    if (growing && s.flow != Flow.BLOOM && s.ending < 0) {
                        double unfurl = opening * opening * (3 - 2 * opening);
                        at = s.bud.lerp(at, unfurl);
                    }
                    if (s.flow == Flow.BLOOM && bloomEnd(s) < 0) {
                        for (Contact c : CONTACTS) if (c.storm == s && c.defensive && c.age + partial < 4) {
                            double pulse = Math.sin(Math.PI * clamp((c.age + partial) / 4));
                            Vec3 outward = direction(c.at.subtract(s.casterFeet.add(0, 1.2, 0)));
                            double facing = direction(at.subtract(s.casterFeet.add(0, 1.2, 0))).dot(outward);
                            if (facing > .2) tint = new VfxColour(1,
                                    (float) (tint.green() + (1 - tint.green()) * pulse),
                                    (float) (tint.blue() + (1 - tint.blue()) * pulse));
                        }
                    }
                    // Contact brightens loose petals without collapsing their paths into a slab.
                    if (contact != null && s.scale > .251 && s.flow == Flow.BURST) {
                        double hitAge = contact.age + partial;
                        double pulse = Math.sin(Math.PI * clamp(hitAge / 10));
                        pulse *= pulse;
                        size *= 1 + pulse * .7;
                        tint = new VfxColour(1, (float) (tint.green() + (1 - tint.green()) * pulse * .85),
                                (float) (tint.blue() + (1 - tint.blue()) * pulse * .85));
                    }
                    // The blade births every shape; a staggered peel follows the leading band.
                    if (!growing && s.flow != Flow.RIVER && s.ending < 0) at = s.bud.lerp(at, smooth((t - 2 - seed(i, 8) * 8) / 10));
                    // Keep both fighter pockets through the hit, with a feathered, irregular shore.
                    if (s.flow != Flow.BLOOM && s.flow != Flow.RIVER) {
                        at = bodyPocket(at, s.casterFeet, i);
                        for (var actor : s.neighbours) at = bodyPocket(at, actor.position(), i);
                    }
                    if (s.flow == Flow.BURST || s.flow == Flow.VORTEX) {
                        double clear = smooth((at.distanceTo(s.casterFeet.add(0, 1, 0)) - 1.5) / .7);
                        for (var actor : s.neighbours)
                            clear = Math.min(clear, smooth((at.distanceTo(actor.position().add(0, 1, 0))
                                    - (s.flow == Flow.BURST ? 2.5 : 1.5)) / .7));
                        fade *= clear;
                        if (s.flow == Flow.BURST) {
                            fade *= 1 - .72 * smooth((at.y - s.casterFeet.y - 1.7) / 1.2);
                            for (Vec3 cut : cutGap) fade *= fighterWindow(at, camera, cut.add(0, -1, 0), 1.6);
                        }
                        fade *= fighterWindow(at, camera, s.casterFeet);
                        for (var actor : s.neighbours) fade *= fighterWindow(at, camera, actor.position(), s.flow == Flow.VORTEX ? 1.5 : 1.25);
                    }
                    if (s.flow == Flow.BLOOM || s.flow == Flow.VORTEX) {
                        for (var actor : s.neighbours) fade *= fighterWindow(at, camera, actor.position(), s.flow == Flow.VORTEX ? 1.5 : 1.25);

                    }
                    if (s.flow != Flow.RIVER && s.flow != Flow.BLOOM) at = avoidStrikes(s, at, i, t);
                    if (s.flow == Flow.RAIN && s.scale > .251) {
                        double fallAt = PlumBeats.RAIN.strike() + seed(i, 4) * 28;
                        fade *= 1 - smooth((local - fallAt - 34 - seed(i, 16) * 4) / 6);
                    }
                    // Normal depth testing is retained: clear a channel around the leading cuts.
                    double gap = (s.flow == Flow.BURST ? 1.25 : .65) * s.scale;
                    for (Vec3 point : cutGap) {
                        double dx = at.x - point.x, dy = at.y - point.y, dz = at.z - point.z;
                        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        fade *= smooth((distance - gap) / .3);
                        if (fade <= 0) break;
                    }
                    PetalContact collision = s.petalContacts.get(i);
                    if (collision != null) {
                        double age = t - collision.tick();
                        float erosion = (float) (smooth(age / .7) * (1 - smooth(age / 3.6)));
                        for (int bit = 0; bit < 3 && erosion > 0; bit++) {
                            Vec3 fragment = collision.at().add((bit - 1) * age * .045,
                                    age * (.025 + bit * .012), Math.sin(i + bit) * age * .035);
                            PlumVfx.paintedPetal(petals, stack.last(), camera, fragment, .025,
                                    bit, (float) (i + age * .2), erosion * .65F, 1, .55F, .7F);
                        }
                        continue;
                    }
                    if (fade <= 0) continue;
                    if (debugBurst) {
                        double alpha = fade * PlumBeats.nearFade(at.distanceTo(camera));
                        if (alpha > 0) burstPoints += RiverStormTimeline.petals(i);
                        Vec3 offset = at.subtract(s.destination);
                        double along = offset.dot(s.forward);
                        minAlong = Math.min(minAlong, along); maxAlong = Math.max(maxAlong, along);
                        maxRadius = Math.max(maxRadius, offset.length());
                        minAlpha = Math.min(minAlpha, alpha); maxAlpha = Math.max(maxAlpha, alpha);
                    }
                    if (s.ending >= 0 && s.flow != Flow.BLOOM) size *= .55 + .45 * fade;
                    if (s.crimsonFinish()) {
                        float spin = (float) (s.flow == Flow.VORTEX ? SeaPetalMotion.spin(i, t) : i * 2.399963 + t * (i % 32 == 0 ? .025 : .13));
                        double distance = at.distanceTo(camera);
                        double depth = SeaScatterFinish.depth(distance);
                        double extent = Math.min(.3, size);
                        if (!PlumPolish.headClear(at.distanceToSqr(s.casterFeet.add(0, 1.62, 0)), extent)) continue;
                        boolean clearHeads = true;
                        for (var actor : s.neighbours)
                            if (!PlumPolish.headClear(at.distanceToSqr(actor.position().add(0, 1.62, 0)), extent)) clearHeads = false;
                        if (!clearHeads) continue;
                        if (s.flow == Flow.BURST && i % 32 != 0) {
                            boolean foreground = i % 20 == 0 && distance < 7;
                            size = foreground ? .125 + .225 * seed(i, 7) : i % 4 == 0 ? .01 + .015 * seed(i, 7) : .03 + .05 * seed(i, 7);
                        }
                        float tone = 1F;
                        if (distance > 14) fade *= .72; // Paler low-contrast air plane; trajectory stays unchanged.
                        if (i % 32 == 0) {
                            // Preserve the original camera-plane flower and opening footprint.
                            Vec3 facing = direction(camera.subtract(at));
                            Vec3 right = direction(facing.cross(Math.abs(facing.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)));
                            Vec3 up = right.cross(facing);
                            double open = i == 0 ? 1 : .15 + (i / 32 % 4) * .283;
                            double radius = flowerSize * (.12 + .8 * open);
                            for (int lobe = 0; lobe < 5; lobe++) {
                                double angle = spin + lobe * Math.PI * 2 / 5;
                                Vec3 petal = at.add(right.scale(Math.cos(angle) * radius)).add(up.scale(Math.sin(angle) * radius));
                                SeaScatterPetals.draw(buffers.getBuffer(MurimRenderTypes.rainPetals()), stack.last(), camera, petal, Math.min(.2, flowerSize * (.36 + .30 * open)),
                                        i + lobe, (float) angle, (float) fade, tone, tone, tone);
                            }
                            SeaScatterPetals.draw(buffers.getBuffer(MurimRenderTypes.rainPetals()), stack.last(), camera, at, flowerSize * .24, i + 1, 0,
                                    (float) fade, 1, 1, 1);
                        } else {
                            SeaScatterPetals.draw(buffers.getBuffer(MurimRenderTypes.rainPetals()), stack.last(), camera, at, Math.min(s.flow == Flow.VORTEX ? .2 : .35, size) * 1.15 * depth, i, spin,
                                    (float) fade, tone, tone, tone);
                        }
                        if (s.flow == Flow.VORTEX) seaFoam(buffers.getBuffer(MurimRenderTypes.rainPetals()), stack.last(), camera, s, i, at, size, t, fade);
                        continue;
                    }
                    // Four sizes, buds/cups/open five-petal flowers among loose petals.
                    if (i % 32 == 0 || s.flow == Flow.BLOOM && i % 5 == 0) {
                        // Flowers open repeatedly, then separate into five independently drifting petals.
                        if (s.flow == Flow.BLOOM && i % 2 == 0 && (s.bloomBase == null || s.bloomTip == null)) continue;
                        double scatter = s.flow == Flow.BLOOM
                                ? bloomEnd(s) < 0 ? 0 : smooth((t - bloomEnd(s) - seed(i, 14) * 6) / 10)
                                : growing ? smooth((local - birth - 14 - seed(i, 14) * 12) / 10) : 0;
                        blossom(petals, stack.last(), camera, at, flowerSize,
                                (growing ? opening : 1) * (s.flow == Flow.BLOOM ? 1 : i == 0 ? 1 : .15 + (i / 32 % 4) * .283),
                                (s.flow == Flow.VORTEX ? SeaPetalMotion.spin(i, t) : i * 2.399963 + t * .025), scatter, (float) fade, tint);
                        if (s.flow == Flow.BLOOM) emit(emission, s, new Emission(at, flowerSize * .24, 0, 0,
                                (float) fade * .5F, colour(0xFFD3E4)));
                        continue;
                    }
                    if (s.flow == Flow.BLOOM) {
                        if (i % 2 == 0 && (s.bloomBase == null || s.bloomTip == null)) continue;
                        size = (i % 12 == 0 ? .19 : i % 3 == 0 ? .11 : .065) * s.scale;
                        float spin = (float) (i * 2.399963 + t * .06);
                        PlumVfx.paintedPetal(petals, stack.last(), camera, at, size, i % 4, spin,
                                (float) fade, tint.red(), tint.green(), tint.blue());
                        if (i % 7 == 0) emit(emission, s, new Emission(at, size, i % 4, spin, (float) fade * .4F, tint));
                    } else if (s.flow == Flow.RIVER) {
                        size = (i % 12 == 0 ? .24 : i % 3 == 0 ? .11 : .055) * s.scale;
                        tint = colour(i % 12 == 0 ? 0xE8305F : i % 3 == 0 ? 0xFF8FB5 : 0xFFD1E1);
                        float spin = (float) (i * 2.399963 + t * .06);
                        PlumVfx.paintedPetal(petals, stack.last(), camera, at, size, i % 4, spin, (float) fade, tint.red(), tint.green(), tint.blue());
                        emit(emission, s, new Emission(at, size, i % 4, spin, (float) fade * .4F, tint));
                    } else if (s.flow == Flow.VORTEX) {
                        float spin = (float) SeaPetalMotion.spin(i, t);
                        PlumVfx.petal(petals, stack.last(), camera, at, size, i % 4, spin,
                                (float) fade, tint.red(), tint.green(), tint.blue());
                        if (i % 7 == 0) emit(emission, s, new Emission(at, size, i % 4, spin, (float) fade * .45F, tint));
                    } else {
                        // Three depth groups; only a few foreground petals carry saturated crimson.
                        boolean near = i % 20 == 0 && at.distanceTo(camera) < 7;
                        size = near ? .125 + .225 * seed(i, 7) : i % 4 == 0 ? .01 + .015 * seed(i, 7) : .03 + .05 * seed(i, 7);
                        tint = colour(near ? 0xC92353 : i % 4 == 0 ? 0xDCC5ED : 0xFF8FB5);
                        float spin = (float) (i * 2.399963 + t * .13);
                        PlumVfx.paintedPetal(petals, stack.last(), camera, at, size, i % 4, spin, (float) fade, tint.red(), tint.green(), tint.blue());
                        if (i % 7 == 0) emit(emission, s, new Emission(at, size, i % 4, spin, (float) fade * .45F, tint));
                    }
                }
                if (debugBurst)
                    MurimMod.LOGGER.info("CAPTURE-PLUM-RIVER burst_frame={} active={} age={} endAge={} dissolve={} burst={} points={} along=[{},{}] radius={} alpha=[{},{}] node={}",
                            t - s.burst, s.ending < 0, s.age, s.ending, s.dissolveCause, s.burst,
                            burstPoints, minAlong, maxAlong, maxRadius, minAlpha, maxAlpha, s.destination);
                if (debugBurst)
                    MurimMod.LOGGER.info("CAPTURE-PLUM-RIVER local_samples={} / {} / {}",
                            RiverStormTimeline.point(0, t - s.burst, s.scale),
                            RiverStormTimeline.point(1, t - s.burst, s.scale),
                            RiverStormTimeline.point(7, t - s.burst, s.scale));
                s.capture.end();
            }
            for (Contact c : CONTACTS) {
                if (!c.storm.has(PlumLayers.Kind.PETALS) && !c.storm.has(PlumLayers.Kind.FLOWER)) continue;
                petals = buffers.getBuffer(MurimRenderTypes.plumPetals());
                capture(c, "contactPetals");
                PlumLayerCapture.layer("PlumStormVfx#render.contactPetals");
                // river-14: forward spray beyond contact, never a spherical smoke ball.
                double t = c.age + partial;
                Vec3 side = new Vec3(-c.forward.z, 0, c.forward.x);
                for (int i = 0; i < quality((int) (56 * PlumLayers.density(c.storm.form, c.storm.stars))); i++) {
                    double q = (i + .5) / quality((int) (56 * PlumLayers.density(c.storm.form, c.storm.stars))), a = i * 2.399963;
                    double travel = Math.min(t, 18 + seed(i, 23) * 8);
                    Vec3 axis = c.defensive ? direction(c.at.subtract(c.storm.casterFeet.add(0, 1.2, 0))) : c.forward;
                    Vec3 at = c.at.add(axis.scale(travel * (.18 + q * .16)))
                            .add(side.scale(Math.cos(a) * travel * .12))
                            .add(0, Math.max(-.8, Math.sin(a) * travel * .08 - travel * travel * .003), 0);
                    if (c.storm.flow == Flow.BURST) {
                        at = bodyPocket(at, c.storm.casterFeet, i);
                        for (var actor : c.storm.neighbours) at = bodyPocket(at, actor.position(), i);
                    }
                    at = avoidStrikes(c.storm, at, i, c.storm.age + partial);
                    double clear = 1;
                    if (c.storm.flow == Flow.BURST) {
                        clear *= fighterWindow(at, camera, c.storm.casterFeet);
                        for (var actor : c.storm.neighbours) clear *= fighterWindow(at, camera, actor.position());
                    }
                    if (c.storm.crimsonFinish()) {
                        SeaScatterPetals.draw(buffers.getBuffer(MurimRenderTypes.rainPetals()), stack.last(), camera, at, .05 + q * .05, i, (float) a,
                                (float) (clear * SeaScatterFinish.envelope(t, 20 + seed(i, 24) * 20)), 1, 1, 1);
                        continue;
                    }
                    VfxColour tint = colour(i % 3 == 0 ? 0xFFD3E4 : 0xF777A4);
                    PlumVfx.petal(petals, stack.last(), camera, at, .05 + q * .05, i % 4, (float) a,
                            (float) (clear * clamp(t / 2) * (1 - smooth((t - 12 - seed(i, 24) * 20) / 8))), tint.red(), tint.green(), tint.blue());
                }
                c.capture.end();
            }
            PlumLayerCapture.clear();
            for (Storm s : STORMS) if (s.crimsonFinish()) {
                s.residue.draw(buffers.getBuffer(MurimRenderTypes.rainPetals()), stack.last(), s.age, partial);
                if (s.flow == Flow.VORTEX) seaForeground(buffers.getBuffer(MurimRenderTypes.rainPetals()), stack.last(), camera, s, s.age + partial);
            }
            buffers.endBatch(MurimRenderTypes.rainPetals());
            buffers.endBatch(MurimRenderTypes.plumPetals());
            if (!emission.isEmpty() && TechniqueGlow.want(.65F)) {
                RenderType halo = TechniqueGlow.textured(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/plum_petals.png"));
                var glow = buffers.getBuffer(halo);
                for (Emission e : emission) PlumVfx.paintedPetal(glow, stack.last(), camera, e.at(), e.size(), e.cell(), e.spin(), e.alpha(), e.tint().red(), e.tint().green(), e.tint().blue());
                buffers.endBatch(halo);
            }
            // Confirmed-hit flash uses the existing radial mote texture, not the band atlas.
            var flashes = buffers.getBuffer(MurimRenderTypes.mote());
            for (Contact c : CONTACTS) if (c.storm.flow == Flow.BLOOM && c.age + partial < 4) {
                ImpactFlash.draw(flashes, stack.last(), c.at, camera,
                        (c.age + partial) / 4, 1.5 * c.scale, WHITE, EDGE);
            }
            buffers.endBatch(MurimRenderTypes.mote());
            // Submit sword cores last so the dense populations cannot bury their silhouettes.
            bands = buffers.getBuffer(MurimRenderTypes.airBand());
            for (Storm s : STORMS) {
                capture(s, "bands");
                PlumLayerCapture.layer("PlumStormVfx#bladeLead");
                bladeLead(bands, stack.last(), camera, s, s.age + partial);
                PlumLayerCapture.layer("PlumStormVfx#cuts");
                cuts(bands, stack.last(), camera, s, s.age + partial);
                PlumLayerCapture.layer("PlumStormVfx#riverBands");
                riverBands(bands, stack.last(), camera, s, s.age + partial);
                PlumLayerCapture.layer("PlumStormVfx#rainStreaks");
                rainStreaks(bands, stack.last(), camera, s, s.age + partial);
                s.capture.end();
            }
            for (Contact c : CONTACTS) {
                capture(c, "contactBands");
                PlumLayerCapture.layer("PlumStormVfx#contactCuts");
                contactCuts(bands, stack.last(), camera, c, c.age + partial);
                c.capture.end();
            }
            buffers.endBatch(MurimRenderTypes.airBand());
            if (!STORMS.isEmpty() && TechniqueGlow.want(.65F)) {
                RenderType crest = TechniqueGlow.textured(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/air_band.png"));
                var glow = buffers.getBuffer(crest);
                for (Storm s : STORMS) {
                    if (PlumLayers.light(s.stars) == 0) continue;
                    if (s.flow == Flow.RIVER && (s.node >= 0 || s.burst >= 0)) riverBands(glow, stack.last(), camera, s, s.age + partial, PlumLayers.light(s.stars));
                    else if (s.flow != Flow.RIVER) glowCrests(glow, stack.last(), camera, s, s.age + partial);
                }
                buffers.endBatch(crest);
            }
        } finally { PlumLayerCapture.clear(); stack.popPose(); }
    }
    private static int castTick(Storm s) { return s.age + (s.flow == Flow.RAIN ? 66 : 14); }
    /** Crimson floor bounce follows the same long dissolve as the petals, with no white disk. */
    private static void finishGround(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Storm s, double t) {
        if (s.stars < 8) return;
        float alpha = (float) (smooth(t / 10) * (s.ending < 0 ? 1 : 1 - smooth((t - s.ending) / 24)));
        for (int ring = 0; ring < 5; ring++) {
            double radius = (ring + .5) * (s.stars < 10 ? .25 : s.scale);
            Vec3[] points = new Vec3[25]; double[] widths = new double[25];
            for (int k = 0; k <= 24; k++) {
                double angle = k * Math.PI * 2 / 24;
                points[k] = s.casterFeet.add(Math.cos(angle) * radius, .025, Math.sin(angle) * radius);
                widths[k] = .5 * s.scale;
            }
            PlumVfx.flatStrip(v, pose, points, widths, alpha * .045F * PlumLayers.light(s.stars) * (1 - ring * .16F), colour(0xE3264E));
        }
        if (s.stars < 10 || s.flow != Flow.VORTEX || s.ending >= 0) return;
        // Ten torn tangents per wave, each held for two ticks.
        for (int wave = 0; wave < 5; wave++) for (int k = 0; k < 10; k++) {
            double age = t - (12 + wave * 14 + k % 2);
            double life = 2;
            if (age < 0 || age >= life) continue;
            Vec3 at = position(s, wave * 71 + k * 13, t);
            Vec3 d = at.subtract(s.centre).cross(new Vec3(0, 1, 0)).normalize().scale(k == 0 ? 1.25 : .5 + k * .075);
            Vec3[] path = {at.subtract(d), at, at.add(d)};
            double[] w = {0, .025, 0};
            PlumVfx.strip(v, pose, camera, path, PlumVfx.scale(w, 2), (float) (1 - age / life) * .45F, colour(0xE3264E));
            PlumVfx.strip(v, pose, camera, path, w, (float) (1 - age / life), colour(0xFFEFF3));
        }
    }

    /** Camera-relative edge accents only; never cross the aiming centre or approach within .6. */
    private static void seaForeground(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Storm s, double t) {
        int count = SeaScatterFinish.nearCount(s.tier);
        if (count == 0) return;
        // API: read-only minecraft-src/net/minecraft/client/renderer/GameRenderer.java#getMainCamera,
        // client/Camera.java#getLookVector; Vec3.java#Vec3(Vector3f).
        Vec3 look = new Vec3(Minecraft.getInstance().gameRenderer.getMainCamera().getLookVector());
        Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 up = right.cross(look).normalize();
        if (s.ending >= 0 && t - s.ending >= 8) return;
        float alpha = (float) (smooth(t / 8) * (s.ending < 0 ? 1 : 1 - smooth((t - s.ending) / 8)));
        for (int i = 0; i < count; i++) {
            double age = Math.max(0, t - SeaPetalMotion.delay(i));
            double glide = 1 - Math.exp(-age / (6 + i));
            Vec3 at = camera.add(look.scale(1.6 + i * .1))
                    .add(right.scale((i % 2 == 0 ? -1 : 1) * (1.2 + .08 * Math.sin(age * .07 + i))))
                    .add(up.scale(-.65 + (i % 3) * .65 + .22 * glide));
            SeaScatterPetals.draw(v, pose, camera, at, SeaScatterFinish.nearSize(i), i,
                    (float) SeaPetalMotion.spin(i, age), alpha, 1, 1, 1);
        }
    }

    /** Leading swells get sparse pale faces; the painted rim keeps foam from becoming a plate. */
    private static void seaFoam(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Storm s, int i,
                                Vec3 at, double size, double t, double fade) {
        if (i % 13 != 0 || s.ending >= 0) return;
        double initial = seed(i, 3) * 14.4 - 7.2;
        double motion = SeaPetalMotion.clock(i, t, initial);
        double along = ((seed(i, 3) + motion * .012) % 1) * 14.4 - 7.2;
        double across = (seed(i, 2) * 2 - 1) * 7.2;
        double phase = along * .85 - motion * .13 + across * .22;
        double crest = Math.pow(Math.max(0, Math.sin(phase)), 12);
        SeaScatterPetals.draw(v, pose, camera, at.add(0, .025, 0), size * .35, i + 1,
                (float) SeaPetalMotion.spin(i, t), (float) (fade * crest), 1, 1, 1);
    }

    private static String captureForm(Storm s) { return s.flow == Flow.VORTEX ? "SEA" : s.flow.name(); }
    private static List<Vec3> captureTargets(Storm s) {
        return s.neighbours.stream().map(actor -> actor.position().add(0, 1, 0)).toList();
    }
    private static void capture(Storm s, String pass) {
        if (!PlumLayerCapture.enabled() || s.flow != Flow.RAIN && s.flow != Flow.VORTEX) return;
        s.capture.begin(captureForm(s), castTick(s), s.owner, s.casterFeet.y, captureTargets(s), pass);
    }
    private static void capture(Contact c, String pass) {
        if (!PlumLayerCapture.enabled()) return;
        c.capture.begin(captureForm(c.storm), castTick(c.storm), c.owner, c.storm.casterFeet.y,
                List.of(c.at), pass);
    }
    /** The gather's leading ribbon is sampled from the actual animated blade, in world space. */
    private static void bladeLead(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Storm s, double t) {
        if (!s.has(PlumLayers.Kind.TRAIL) || s.flow == Flow.BLOOM || s.flow == Flow.RIVER || t >= 22 || s.bladePath.size() < 2) return;
        Vec3[] path = new Vec3[s.bladePath.size()];
        double[] widths = new double[path.length];
        double fade = clamp((22 - t) / 6);
        for (int k = 0; k < path.length; k++) {
            double u = k / (double) (path.length - 1);
            // A small trembling bow remains visible when the raised blade is nearly stationary.
            path[k] = s.bladePath.get(0).lerp(s.bladePath.get(path.length - 1), u);
            widths[k] = Math.sin(Math.PI * u) * .035 * s.scale * fade;
        }
        PlumVfx.strip(v, pose, camera, path, widths, (float) fade, EDGE);
        PlumVfx.strip(v, pose, camera, path, PlumVfx.scale(widths, s.crimsonFinish() ? .12 : .45), (float) fade, WHITE);
    }
    private static Vec3 bodyPocket(Vec3 at, Vec3 feet, int i) {
        double dx = at.x - feet.x, dz = at.z - feet.z;
        if (at.y < feet.y - .2 || at.y > feet.y + 2.2 || dx * dx + dz * dz >= 1.4 * 1.4) return at;
        double angle = Math.atan2(dz, dx) + (i % 2 == 0 ? .1 : -.1);
        double radius = 1.6 + seed(i, 22) * 1.1;
        return new Vec3(feet.x + Math.cos(angle) * radius, at.y, feet.z + Math.sin(angle) * radius);
    }
    /** Feathered view-space opening: foreground storm petals also leave the bodies visible.
     * API: supplied Minecraft world/phys/Vec3.java#dot, distanceTo, normalize, add, subtract, scale.
     * Source: docs/03-vfx/11-what-looks-good.md (negative space), round-9 Scatter feedback.
     */
    private static double fighterWindow(Vec3 at, Vec3 camera, Vec3 feet) {
        return fighterWindow(at, camera, feet, 1.25);
    }
    private static double fighterWindow(Vec3 at, Vec3 camera, Vec3 feet, double opening) {
        Vec3 chest = feet.add(0, 1, 0);
        Vec3 ray = chest.subtract(camera);
        double length = ray.length();
        if (length < .01) return 1;
        Vec3 direction = ray.scale(1 / length);
        double depth = at.subtract(camera).dot(direction);
        if (depth <= 0 || depth > length + 1.6) return 1;
        double radius = opening * Math.min(1, depth / length);
        double offset = at.distanceTo(camera.add(direction.scale(depth)));
        return smooth((offset - radius) / .55);
    }
    private static void rainStreaks(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Storm s, double t) {
        if (s.flow != Flow.RAIN || s.scale <= .251 || s.ending >= 0) return;
        for (int i = 0; i < s.count; i++) {
            double fallAt = PlumBeats.RAIN.strike() + seed(i, 4) * 28;
            double age = t - fallAt;
            if (age <= 0 || age >= 24) continue;
            Vec3 at = position(s, i, t), behind = position(s, i, t - 1.5);
            float alpha = (float) (clamp(age / 3) * clamp((24 - age) / 4)
                    * PlumBeats.nearFade(at.distanceTo(camera)));
            PlumVfx.strip(v, pose, camera, new Vec3[]{behind, behind.lerp(at, .6), at},
                    new double[]{0, .025 * s.scale, .045 * s.scale}, alpha, WHITE);
        }
    }
    /** Ch.286–296: slip around strikes and return; qi petals roll over a live aura.
     * Neighbours are sampled on ticks, never queried from chunks in the render pass.
     * API: Entity#getBoundingBox; AABB public bounds; LivingEntity#swinging/getAttackAnim (1.21.1).
     */
    private static Vec3 avoidStrikes(Storm s, Vec3 at, int i, double t) {
        for (var actor : s.neighbours) {
            boolean aura = io.github.verycooltimo.murim.client.ClientAuraState.of(actor).present();
            if (!aura && !actor.swinging) continue;
            var box = actor.getBoundingBox();
            Vec3 centre = new Vec3((box.minX + box.maxX) * .5, (box.minY + box.maxY) * .5,
                    (box.minZ + box.maxZ) * .5);
            double radius = aura ? 1.25 : 1.65;
            Vec3 offset = at.subtract(centre);
            if (offset.lengthSqr() >= radius * radius) continue;
            Vec3 tangent = direction(new Vec3(-offset.z, .25 * Math.sin(t * .15 + i), offset.x));
            Vec3 rolled = direction(offset.add(tangent.scale(.5)));
            double response = aura ? 1 : Math.sin(Math.PI * clamp(actor.getAttackAnim((float) (t - Math.floor(t)))));
            at = at.lerp(centre.add(rolled.scale(radius)), response);
        }
        return at;
    }
    /** Five separate atlas petals form a flower, never an opaque flower card. */
    private static void blossom(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Vec3 at,
                                double size, double open, double spin, double scatter, float alpha, VfxColour tint) {
        Vec3 facing = direction(camera.subtract(at));
        Vec3 right = direction(facing.cross(Math.abs(facing.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)));
        Vec3 up = right.cross(facing);
        double radius = size * (.12 + .8 * open);
        for (int j = 0; j < 5; j++) {
            double a = spin + j * Math.PI * 2 / 5;
            Vec3 p = at.add(right.scale(Math.cos(a) * radius)).add(up.scale(Math.sin(a) * radius));
            p = p.add(Math.cos(a + spin) * scatter * size * 3,
                    Math.sin(a * 1.7) * scatter * size * 2, Math.sin(a + spin) * scatter * size * 3);
            PlumVfx.petal(v, pose, camera, p, size * (.36 + .30 * open), j % 4,
                    (float) a, alpha, tint.red(), tint.green(), tint.blue());
        }
        PlumVfx.petal(v, pose, camera, at, size * .24, 0, 0, alpha, 1, .97F, .99F);
    }
    /** Lab plrArms, adapted to real depth: unequal broad rose/violet arms around an open white eye.
     * No solid sky card, floor ring or camera-facing tunnel. They grow along their paths. */
    private static void canopy(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Storm s, double t) {
        if (s.flow != Flow.RAIN || !s.has(PlumLayers.Kind.ILLUSION)) return;
        double clock = s.ending >= 0 ? s.ending : t;
        double fade = 1 - smooth((clock - 100) / 42);
        if (s.ending >= 0) fade *= clamp((18 - t + s.ending) / 18);
        double heart = smooth((clock - 12) / 18) * fade;
        if (heart > 0) {
            Vec3[] eye = new Vec3[25];
            double[] widths = new double[eye.length];
            for (int k = 0; k < eye.length; k++) {
                double u = k / 24.0, a = u * Math.PI * 1.8 + clock * .012;
                eye[k] = s.centre.add(Math.cos(a) * .9 * s.scale, 3.3 * s.scale, Math.sin(a) * .9 * s.scale);
                widths[k] = Math.sin(Math.PI * u) * .34 * s.scale * heart;
            }
            PlumVfx.strip(v, pose, camera, eye, widths, (float) heart, EDGE);
            PlumVfx.strip(v, pose, camera, eye, PlumVfx.scale(widths, .6), (float) heart, WHITE);
        }
        for (int j = 0; j < 5; j++) {
            double grow = smooth((clock - 15 - j * 2) / 20);
            if (grow <= 0 || fade <= 0) continue;
            Vec3[] path = new Vec3[33];
            double[] widths = new double[33];
            for (int k = 0; k < path.length; k++) {
                double u = k / 32.0, along = u * grow;
                double a = j * Math.PI * 2 / 5 + along * 3.6 + clock * .012;
                double radius = 1.3 + along * (5.2 + j % 3 * .45);
                path[k] = s.centre.add(Math.cos(a) * radius * s.scale,
                        (3.4 + along * .9) * s.scale, Math.sin(a) * radius * s.scale);
                widths[k] = Math.sin(Math.PI * u) * (j < 2 ? .65 : .38) * s.scale * grow
                        * (1 - smooth((clock - 78 - (1 - u) * 42 - j * 2) / 18));
            }
            PlumVfx.strip(v, pose, camera, path, widths, (float) (fade * .32), colour(0xA565B9));
            PlumVfx.strip(v, pose, camera, path, PlumVfx.scale(widths, .65), (float) (fade * .4), EDGE);
            PlumVfx.strip(v, pose, camera, path, PlumVfx.scale(widths, .24), (float) (fade * .48), WHITE);
        }
    }
    /** Feet layers precede the roots; no reach, input or phase clock is changed.
     * API: supplied Minecraft world/phys/Vec3.java#add, scale, lerp.
     */
    private static void bloomFeet(VertexConsumer v, PoseStack.Pose pose,
                                  Vec3 camera, Storm s, double t, boolean dustPass) {
        if (s.flow != Flow.BLOOM) return;
        double end = bloomEnd(s);
        double fade = smooth(t / 3) * (end < 0 ? 1 : 1 - smooth((t - end) / 12));
        int count = Math.max(1, (int) (12 * PlumLayers.density(s.form, s.stars)));
        for (int i = 0; i < count; i++) {
            double age = (t + seed(i, 71) * 16) % 16, a = seed(i, 72) * Math.PI * 2;
            Vec3 at = s.casterFeet.add(Math.cos(a) * age * .045, .06 + age * .006,
                    Math.sin(a) * age * .045);
            float alpha = (float) (fade * Math.sin(Math.PI * age / 16) * .25);
            Vec3 tail = at.add(-Math.cos(a) * .25, 0, -Math.sin(a) * .25);
            if (dustPass) PlumVfx.puff(v, pose, camera, at, .12 * s.scale, i % 4, alpha, .65F);
            else PlumVfx.strip(v, pose, camera, new Vec3[]{tail, tail.lerp(at, .5), at},
                    new double[]{0, .012 * s.scale, 0}, alpha, WHITE);
        }
    }

    private static double bloomEnd(Storm s) {
        if (s.release < 0) return s.ending;
        return s.ending < 0 ? s.release : Math.min(s.release, s.ending);
    }
    /** Luminous round-10 bands; detach fixed-length pieces on the round-12 staged clock. */
    private static void woodPiece(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Storm s,
                                  Vec3 a, Vec3 b, double width, double t, double delay, int key) {
        double end = bloomEnd(s);
        double age = end < 0 ? 0 : Math.max(0, t - end - delay);
        float alpha = (float) (smooth(t / 2) * (1 - smooth(age / 3.6)));
        if (alpha <= 0) return;
        Vec3 drift = new Vec3(Math.sin(key * 2.4) * age * .035, -age * age * .009,
                Math.cos(key * 1.7) * age * .035);
        Vec3 mid = a.lerp(b, .5).add(drift);
        Vec3 half = b.subtract(a).scale(.5);
        Vec3 turn = new Vec3(Math.sin(age * .14 + key) * age * .012,
                Math.cos(key) * age * .018, Math.cos(age * .14 + key) * age * .012);
        Vec3[] path = {mid.subtract(half).add(turn), mid, mid.add(half).subtract(turn)};
        double[] widths = {Math.max(.08, width), Math.max(.08, width * .9), Math.max(.08, width * .72)};
        PlumVfx.materialRibbon(v, pose, camera, path, widths, alpha, colour(0xD93670), WHITE);
    }
    private static void bloomTree(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Storm s, double t) {
        if (s.flow != Flow.BLOOM || !s.has(PlumLayers.Kind.ROOTS)) return;
        double clock = bloomEnd(s) < 0 ? t : Math.min(t, bloomEnd(s));
        for (int j = 0; j < Math.min(5, s.tier); j++) {
            double a = j * 2.399963, grow = smooth(clock / 8) * s.scale;
            Vec3 foot = s.casterFeet;
            Vec3 elbow = foot.add(Math.cos(a) * .65 * grow, .08, Math.sin(a) * .65 * grow);
            Vec3 bend = foot.add(Math.cos(a + .35) * 1.25 * grow, .02, Math.sin(a + .35) * 1.25 * grow);
            Vec3 end = foot.add(Math.cos(a + .2) * 1.7 * grow, -.38, Math.sin(a + .2) * 1.7 * grow);
            woodPiece(v, pose, camera, s, foot.add(0, .12, 0), elbow, .28 * s.scale * grow, t, 26 + seed(j, 31), j);
            woodPiece(v, pose, camera, s, elbow, bend, .22 * s.scale * grow, t, 26 + seed(j, 32), j + 5);
            woodPiece(v, pose, camera, s, bend, end, .15 * s.scale * grow, t, 26 + seed(j, 33), j + 10);
        }
        // Body light supplies the trunk. The sword itself is the only branch.
    }
    /** Refs 10–12,16: gather at the hand, braid, tiny node, then a directed white wedge. */
    private static void riverBands(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Storm s, double t) {
        riverBands(v, pose, camera, s, t, 1);
    }
    private static void riverBands(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Storm s, double t, float alphaScale) {
        if (s.flow != Flow.RIVER || s.ending >= 0 && s.burst < 0 && s.node < 0) return;
        Vec3 side = direction(s.forward.cross(new Vec3(0, 1, 0)));
        Vec3 up = direction(side.cross(s.forward));
        if (s.burst >= 0) {
            if (!s.has(PlumLayers.Kind.WEDGE)) return;
            double age = t - s.burst;
            double burstScale = PlumLayers.riverBurstScale(s.stars);
            alphaScale *= (float) burstScale;
            double size = burstScale;
            // Ref 16: keep the broad white mass; tear the silhouette along unequal ray profiles.
            for (int j = 0; j < RiverRules.rays(s.tier); j++) {
                // Round 17: cool and shred through a full eighteen-tick fade into the live petal tail.
                double fade = 1 - smooth((age - 12 - seed(j, 37) * 4) / 18);
                if (fade <= 0) continue;
                double a = j * Math.PI * 2 / 8 + (seed(j, 38) - .5) * .4;
                Vec3 axis = j == 0 ? s.forward : j == 4 ? s.forward.scale(-1)
                        : direction(s.forward.scale(.35).add(side.scale(Math.cos(a))).add(up.scale(Math.sin(a))));
                double length = (j == 0 ? 18 : j == 4 ? 7 : 4 + seed(j, 30) * 6) * size * (.6 + .4 * smooth(age / 2));
                Vec3[] path = new Vec3[33];
                double[] widths = new double[33];
                double shred = smooth((age - 6) / 24);
                for (int k = 0; k < path.length; k++) {
                    double u = k / 32.0;
                    double ragged = .68 + .32 * seed(j * 33 + k, 39);
                    double split = 1 - shred * smooth((seed(j * 33 + k, 40) - .35) / .4);
                    path[k] = s.destination.add(axis.scale((u - .025) * length))
                            .add(side.scale(Math.sin(u * 17 + j) * u * .4 * size))
                            .add(0, -Math.pow(Math.max(0, age - 18), 2) * .002 * u * burstScale, 0);
                    widths[k] = Math.pow(Math.sin(Math.PI * u), .65)
                            * (j == 0 ? 6.5 : 1.2 + seed(j, 33) * 1.4) * size * ragged * split;
                }
                VfxColour cooling = new VfxColour(1, (float) (1 - shred * .46), (float) (1 - shred * .24));
                riverStrip(v, pose, camera, path, widths, ((float) fade) * alphaScale, EDGE);
                riverStrip(v, pose, camera, path, PlumVfx.scale(widths, .78), ((float) fade) * alphaScale, cooling);
            }
        } else if (s.node >= 0) {
            if (s.tier < 2) return;
            double age = t - s.node;
            double fade = smooth(age / 2);
            for (int j = 0; j < 2; j++) {
                Vec3 axis = j == 0 ? up : side;
                double length = j == 0 ? .42 : .28;
                Vec3[] star = {s.destination.subtract(axis.scale(length)), s.destination, s.destination.add(axis.scale(length))};
                riverStrip(v, pose, camera, star, new double[] {.04, (.09) * alphaScale, .04}, (float) fade, WHITE);
            }
            Vec3[] ring = new Vec3[49]; double[] widths = new double[49];
            for (int k = 0; k < ring.length; k++) {
                double a = k * Math.PI * 2 / 48;
                ring[k] = s.destination.add(side.scale(Math.cos(a) * .55)).add(up.scale(Math.sin(a) * .55));
                widths[k] = .04;
            }
            riverStrip(v, pose, camera, ring, widths, ((float) fade * .3F) * alphaScale, WHITE);
            // 6 Nov: upward sword crescent removed; retain the contact star and ring.
        } else if (t >= 40 && s.riverOrigin != null) {
            double head = RiverStormTimeline.streamProgress(0, t, RiverRules.flight(s.riverOrigin.distanceTo(s.destination)));
            double tail = RiverStormTimeline.streamProgress(0, t - 4, RiverRules.flight(s.riverOrigin.distanceTo(s.destination)));
            if (head > tail) {
                Vec3[] path = new Vec3[17]; double[] widths = new double[17];
                for (int k = 0; k < path.length; k++) {
                    double u = k / 16.0;
                    path[k] = s.riverOrigin.lerp(s.destination, tail + (head - tail) * u);
                    widths[k] = .08 + .25 * Math.sin(Math.PI * u);
                }
                float streamFade = (float) smooth((head - tail) / .15);
                riverStrip(v, pose, camera, path, widths, (.65F * streamFade) * alphaScale, EDGE);
                riverStrip(v, pose, camera, path, PlumVfx.scale(widths, .3), (.95F * streamFade) * alphaScale, WHITE);
            }
        }
    }
    // Widths are half-widths; fade alpha rather than shrinking into hairlines.
    private static void riverStrip(VertexConsumer v, PoseStack.Pose pose, Vec3 camera,
                                   Vec3[] path, double[] widths, float alpha, VfxColour colour) {
        double[] bounded = widths.clone();
        for (int i = 0; i < bounded.length; i++) bounded[i] = RiverStormTimeline.halfWidth(bounded[i]);
        PlumVfx.strip(v, pose, camera, path, bounded, alpha, colour);
    }
    private static void followRiverHand(Storm s, double t) {
        if (s.riverOrigin != null) return;
        var caster = level.getEntity(s.owner);
        Vec3 hand = caster == null ? null : BoneAnchorLayer.position(caster, BoneAnchorLayer.Bone.RIGHT_HAND);
        if (hand == null) hand = s.centre;
        if (s.riverRender == null) s.riverRender = hand;
        double dt = s.riverFrame < 0 ? 0 : Math.max(0, Math.min(1, t - s.riverFrame));
        s.riverFrame = t;
        // Damped spring integrated in small render-time steps, independent of frame rate.
        int steps = Math.max(1, (int) Math.ceil(dt / .1));
        for (int i = 0; i < steps; i++) {
            double h = dt / steps;
            s.riverVelocity = s.riverVelocity.add(hand.subtract(s.riverRender).scale(.7 * h)).scale(Math.exp(-1.5 * h));
            s.riverRender = s.riverRender.add(s.riverVelocity.scale(h));
        }
    }
    private static double cutStart(Storm s, int j) {
        return switch (s.flow) {
            case BURST -> j == 7 ? 92 : PlumBeats.SCATTER.strike() + j * 3;
            case BLOOM -> s.release >= 0 ? s.release : j * 4;
            case RAIN -> s.scale <= .251 ? 6 : PlumBeats.RAIN.strike() + j;
            case RIVER -> j == 0 ? 0 : PlumBeats.RIVER.strike();
            case VORTEX -> j == 0 ? 0 : PlumBeats.SEA.strike() + (j - 1) * 4;
        };
    }
    private static double cutLife(Storm s, int j) {
        return s.flow == Flow.BURST ? (j == 7 ? 8 : 6) : s.flow == Flow.RIVER ? 14 : 10;
    }
    private static Vec3 strokePoint(Storm s, int j, double along) {
        double length = s.scale <= .251 ? 17 : s.flow == Flow.BURST && j == 7 ? 9 : 7 + j % 3;
        if (s.flow == Flow.BLOOM && s.release >= 0) {
            Vec3 side = new Vec3(-s.forward.z, 0, s.forward.x);

            return s.centre.add(s.forward.scale(s.reach))
                    .add(side.scale((along - .5) * s.reach))
                    .add(0, (.5 - along) * 1.8 * s.scale, 0);
        }
        if (s.flow == Flow.BURST && j == 7) {
            Vec3 side = new Vec3(-s.forward.z, 0, s.forward.x);
            return s.centre.add(s.forward.scale((along - .5) * 9 * s.scale))
                    .add(side.scale((along - .5) * 1.2 * s.scale))
                    .add(0, (.5 - along) * 3 * s.scale, 0);
        }
        return cutPoint(s, j, along, length);
    }
    /** Sample each active ribbon once per frame, not once per petal. */
    private static List<Vec3> cutSamples(Storm s, double t) {
        List<Vec3> points = new ArrayList<>();
        if (s.flow == Flow.RIVER || !s.has(PlumLayers.Kind.TRAIL) && s.flow == Flow.VORTEX
                || s.flow == Flow.BURST && !s.has(PlumLayers.Kind.MASTER_CUT)
                || s.flow == Flow.BLOOM && !s.has(PlumLayers.Kind.FULL) || s.ending >= 0 && s.flow != Flow.BLOOM || s.flow == Flow.BLOOM && s.release < 0) return points;
        int count = s.flow == Flow.BURST ? 8 : s.flow == Flow.BLOOM ? 6 : s.scale <= .251 ? 1 : 5;
        for (int j = 0; j < count; j++) {
            if (s.flow == Flow.BLOOM && s.release >= 0 && j > 0 || s.flow == Flow.BURST && j < 7) continue;
            double age = t - cutStart(s, j);
            if (age < 0 || age >= cutLife(s, j)) continue;
            for (int k = 0; k <= 24; k++) points.add(strokePoint(s, j, k / 24.0 * clamp(age / 2.5)));
        }
        return points;
    }
    private static void cuts(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Storm s, double t) {
        if (s.stars < 10) return;
        if (s.flow == Flow.RIVER || !s.has(PlumLayers.Kind.TRAIL) && s.flow == Flow.VORTEX
                || s.flow == Flow.BURST && !s.has(PlumLayers.Kind.MASTER_CUT)
                || s.flow == Flow.BLOOM && !s.has(PlumLayers.Kind.FULL) || s.ending >= 0 && s.flow != Flow.BLOOM || s.flow == Flow.BLOOM && s.release < 0) return;
        if (s.flow == Flow.VORTEX) return; // Tangent fragments are emitted by seaGround; no long neon cuts.
        if (s.flow == Flow.RAIN && s.scale <= .251) t -= 72;
        int count = s.flow == Flow.BURST ? 8 : s.flow == Flow.BLOOM ? 6 : s.scale <= .251 ? 1 : 5;
        for (int j = 0; j < count; j++) {
            if (s.flow == Flow.BLOOM && s.release >= 0 && j > 0 || s.flow == Flow.BURST && j < 7) continue;
            double age = t - cutStart(s, j), life = cutLife(s, j);
            if (age < 0 || age >= life) continue;
            double grow = s.flow == Flow.VORTEX ? clamp(age / 2.5) : smooth(age / 2);
            double fade = s.flow == Flow.VORTEX ? clamp((life - age) / 4.5) : smooth((life - age) / 3.6);
            Vec3[] points = new Vec3[25];
            double[] widths = new double[25];
            for (int k = 0; k < points.length; k++) {
                double u = k / 24.0, along = u * grow;
                points[k] = strokePoint(s, j, along);
                widths[k] = Math.sin(Math.PI * u) * .035
                        * s.scale * Math.sqrt(fade);
                if (s.flow == Flow.BURST || s.flow == Flow.VORTEX) {
                    double clear = fighterWindow(points[k], camera, s.casterFeet);
                    for (var actor : s.neighbours) clear = Math.min(clear, fighterWindow(points[k], camera, actor.position(), s.flow == Flow.VORTEX ? 1.5 : 1.25));
                    widths[k] *= s.flow == Flow.VORTEX ? clear : .12 + .88 * clear;
                }
                if (s.scale <= .251) widths[k] = Math.sin(Math.PI * u) * .035 * Math.sqrt(fade);
            }
            if (s.flow == Flow.VORTEX) {
                // Restore the build's five tapered cuts; glow is an additional pass.
                PlumVfx.strip(v, pose, camera, points, widths, (float) fade, EDGE);
                PlumVfx.strip(v, pose, camera, points, PlumVfx.scale(widths, .12), (float) fade, WHITE);
            } else {
                if (s.flow == Flow.BURST) {
                    PlumVfx.strip(v, pose, camera, points, widths, (float) fade, colour(0xE3264E));
                    PlumVfx.strip(v, pose, camera, points, PlumVfx.scale(widths, .12), (float) fade, colour(0xFFEFF3));
                } else PlumVfx.materialRibbon(v, pose, camera, points, widths, (float) fade, colour(0xD93670), WHITE);
            }
        }
    }
    private static void glowCrests(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Storm s, double t) {
        if (s.stars < 10 || PlumLayers.light(s.stars) == 0 || s.flow == Flow.BURST && !s.has(PlumLayers.Kind.MASTER_CUT)
                || s.flow == Flow.BLOOM && (!s.has(PlumLayers.Kind.FULL) || s.release < 0)) return;
        int j = s.flow == Flow.BURST ? 7 : s.flow == Flow.VORTEX && t >= PlumBeats.SEA.strike() ? 1 : 0;
        double age = t - cutStart(s, j), life = cutLife(s, j);
        if (age < 0 || age >= life || s.ending >= 0) return;
        double fade = smooth((life - age) / 3.6) * smooth(age / 2);
        Vec3[] path = new Vec3[25];
        double[] widths = new double[25];
        for (int k = 0; k < path.length; k++) {
            double u = k / 24.0;
            path[k] = strokePoint(s, j, u * smooth(age / 2));
            widths[k] = .04;
        }
        PlumVfx.strip(v, pose, camera, path, widths, (float) fade * .65F * PlumLayers.light(s.stars), WHITE);
    }
    private static void contactCuts(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Contact c, double age) {
        if (c.storm.stars < 9) return;
        if (!c.storm.has(PlumLayers.Kind.TRAIL) && c.storm.flow != Flow.RIVER
                && !c.storm.has(PlumLayers.Kind.MASTER_CUT) && !c.storm.has(PlumLayers.Kind.FULL)) return;
        Vec3 side = new Vec3(-c.forward.z, 0, c.forward.x);
        // rain-22/25, river-14: all curves cross the destination, with a compact bright convergence.
        for (int j = 0; j < (c.scale <= .251 || c.storm.flow == Flow.BURST || c.storm.flow == Flow.BLOOM || c.storm.flow == Flow.RIVER ? 0 : 5); j++) {
            double life = 7, fade = clamp((life - age) / 5), grow = clamp(age / 2);
            if (age >= life) continue;
            Vec3[] points = new Vec3[25];
            double[] widths = new double[25];
            double a = j * .73 - 1.1;
            Vec3 axis = side.scale(Math.cos(a)).add(0, Math.sin(a), 0);
            for (int k = 0; k < points.length; k++) {
                double u = k / 24.0, x = (u * grow - .5) * (6 + j);
                points[k] = c.at.add(axis.scale(x));
                widths[k] = Math.sin(Math.PI * u) * .035 * c.scale * fade;
            }
            PlumVfx.strip(v, pose, camera, points, widths, (float) fade, EDGE);
            PlumVfx.strip(v, pose, camera, points, PlumVfx.scale(widths, c.storm.crimsonFinish() ? .12 : .45), (float) fade, WHITE);
        }
        if (age < 3) {
            for (int j = 0; j < 2; j++) {
                Vec3 axis = j == 0 ? side : new Vec3(0, 1, 0);
                Vec3[] points = {c.at.subtract(axis.scale(.6)), c.at, c.at.add(axis.scale(.6))};
                double fade = clamp((3 - age) / 3);
                PlumVfx.strip(v, pose, camera, points, new double[]{0, .12 * fade, 0}, (float) fade, colour(0xFF9BC4));
                PlumVfx.strip(v, pose, camera, points, new double[]{0, .06 * fade, 0}, (float) fade, colour(0xFFF7FB));
            }
        }
    }
    private PlumStormVfx() { }
}
