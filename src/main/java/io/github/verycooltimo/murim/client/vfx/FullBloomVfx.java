package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.MurimPlayerAnimations;
import io.github.verycooltimo.murim.network.BloomPayload;
import io.github.verycooltimo.murim.network.SeaHoldPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Chapter 296: rooted sword tree, independent of the shared storm silhouette.
 * API: /tmp/mr-ref-1121/neoforge-src/.../client/event/{ClientTickEvent,RenderLevelStageEvent}.java;
 * /tmp/mr-ref-1121/minecraft-src/.../client/{Minecraft,renderer/MultiBufferSource}.java.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class FullBloomVfx {
    private static final ResourceLocation RELEASE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "twenty_four_plum_full_bloom_release");
    private static net.minecraft.client.multiplayer.ClientLevel presentationLevel;
    private static void syncLevel() {
        var current = Minecraft.getInstance().level;
        if (presentationLevel != current) {
            FRONTS.clear();
            TAILS.clear();
            presentationLevel = current;
        }
    }
    private static final Map<Integer, Front> FRONTS = new HashMap<>();
    private static final List<Front> TAILS = new ArrayList<>();
    private static List<Front> fronts() {
        List<Front> all = new ArrayList<>(FRONTS.values());
        all.addAll(TAILS);
        return all;
    }
    private static final class Front {
        BloomPayload state;
        boolean has(PlumLayers.Kind kind) { return Shape.has(state.stars(), kind); }
        int age, sincePacket, release = -1, ending = -1;
        Boolean sentHeld;
        Vec3 feet, hit;
        int hitAge = -100;
        final List<Petal> petals = new ArrayList<>();
    }
    private static final class Petal {
        Vec3 at, previous, velocity;
        int age, landed = -1;
        final int seed;
        Petal(Vec3 at, Vec3 velocity, int seed) { this.at = at; this.previous = at; this.velocity = velocity; this.seed = seed; }
    }
    /** Pure presentation contracts: execution geometry is retained; stars gate material families. */
    static final class Shape {
        static boolean has(int stars, PlumLayers.Kind kind) { return PlumLayers.has(PlumLayers.Form.BLOOM, stars, kind); }
        static int count(int stars, PlumLayers.Kind kind, int full) {
            return has(stars, kind) ? (int) Math.ceil(full * PlumLayers.density(stars, kind)) : 0;
        }
        static int roots(int tier, int stars) { return count(stars, PlumLayers.Kind.ROOTS, roots(tier)); }
        static int branches(int tier, int stars) { return has(stars, PlumLayers.Kind.BRANCH) && tier >= 3 ? 5 : 0; }
        static int clusters(int tier, int stars) { return count(stars, PlumLayers.Kind.FLOWER, clusters(tier)); }
        static int crown(int tier, int stars) { return count(stars, PlumLayers.Kind.CROWN, crown(tier)); }
        static float light(int stars) { return PlumLayers.light(stars); }
        static int looseLimit(int stars) {
            return !has(stars, PlumLayers.Kind.PETALS) ? 0 : stars < 10 ? 12 : count(stars, PlumLayers.Kind.PETALS, 240);
        }
        // HIGH/ALL per front, including settling fronts; shared light compositor excluded.
        // Conservative maxima across execution tiers, stars 0..12:
        // 0, 0, 0, 0, 0, 24, 27, 27, 27, 51, 524, 891, 1067.
        static int quadCeiling(int tier, int stars) {
            if (tier <= 0 || stars < 5) return 0;
            return roots(tier, stars) * 21 + (has(stars, PlumLayers.Kind.ROOTS) && tier >= 2 ? 28 : 0)
                    + branches(tier, stars) * 7 + (crown(tier, stars) > 0 ? 189 : 0)
                    + clusters(tier, stars) * 2 + crown(tier, stars) * 2 + looseLimit(stars) * 2
                    + 24 + (stars >= 10 ? 14 : 0) + (stars >= 6 ? 3 : 0);
        }
        static double smooth(double x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }
        static int roots(int tier) { return tier < 2 ? 0 : 3 + Math.min(5, tier); }
        static int flowers(int tier) { return tier < 3 ? 0 : 2 + (Math.min(8, tier) - 3) * 2; }
        static int crown(int tier) { return tier < 6 ? 0 : (Math.min(8, tier) - 5) * 16; }
        static double reach(int tier) { return tier <= 0 ? 0 : 3 + 4 * (Math.min(8, tier) - 1) / 7.; }
        static double diameter(double u) { return .25 + (.08 - .25) * Math.max(0, Math.min(1, u)); }
        static double crownY(int i) { return 5.2 + (i % 3) * 1.35; }
        static int nodes(int tier) { return Math.min(5, flowers(tier)); }
        static int blossomsPerNode(int tier) { return tier < 3 ? 0 : tier >= 5 ? 3 : 2; }
        static int clusters(int tier) { return nodes(tier) * blossomsPerNode(tier); }
        static double trunkY(int node) { return node == 1 ? 1.05 : node * 7.8 / 4; }
        static double trunkRear(int node) { return -1.05 - .12 * (node % 2); }
        static double trunkDiameter(double u) { return .55 - .43 * Math.max(0, Math.min(1, u)); }
        static double settleStart(int ending, int hitAge) { return Math.max(ending, hitAge + 50); }
        static double blossomFade(double elapsed) { return 1 - smooth(elapsed / 40); }
        // Each span fades over ten ticks; distal spans start first, all wood is gone at 30.
        static double woodFade(double elapsed, double distal) {
            return 1 - smooth((elapsed - 40 - (1 - distal) * 20) / 10);
        }
        // Seven quads per tube: six canopy supports + 21 twigs = 189 quads.
        // Tier 8: 168 roots + 28 trunk + 35 branch + 189 canopy wood
        // + 30 blossoms + 96 crown + 480 loose + 38 ground/contact = 1064.
        static int quadCeiling(int tier) {
            return tier <= 0 ? 0 : roots(tier) * 21 + (tier >= 2 ? 28 : 0) + (tier >= 3 ? 35 : 0)
                    + (tier >= 6 ? 189 : 0) + clusters(tier) * 2 + crown(tier) * 2 + 240 * 2 + 38;
        }
    }
    private static void end(int id) {
        Front f = FRONTS.get(id);
        if (f != null && f.ending < 0) f.ending = f.age;
    }

    public static void onTechniqueEvent(io.github.verycooltimo.murim.network.TechniqueEventPayload p) {
        if (!ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "twenty_four_plum_full_bloom").equals(p.techniqueId())) return;
        if (p.event() == io.github.verycooltimo.murim.network.TechniqueEventPayload.Event.CANCELLED
                || p.event() == io.github.verycooltimo.murim.network.TechniqueEventPayload.Event.FINISHED)
            end(p.sourceId());
    }

    public static void accept(BloomPayload p) {
        if (p.executionTier() <= 0) return;
        syncLevel();
        if (Boolean.getBoolean("murim.capture") && "1".equals(System.getenv("MURIM_CAPTURE_PLUM_CAMERA"))
                && (p.stage() != BloomPayload.HOLD || !FRONTS.containsKey(p.entityId()))) {
            MurimMod.LOGGER.info("CAPTURE-PLUM-BLOOM stage={} tier={}", p.stage(), p.executionTier());
        }
        if (p.stage() == BloomPayload.CLASH) {
            contact(p.entityId(), p.a());
            return;
        }
        if (p.stage() == BloomPayload.HIT) {
            contact(p.entityId(), p.a());
            var mc = Minecraft.getInstance();
            if (p.stars() >= 10 && mc.player != null && mc.player.position().distanceToSqr(p.a()) < 400) {
                SpeedLines.radial(.5F, .5F, .65F, 6, SpeedLines.WHITE);
                io.github.verycooltimo.murim.client.CameraShakeHandler.quake(.35F, 6);
            }
            return;
        }
        if (p.stage() == BloomPayload.END) {
            end(p.entityId());
            return;
        }
        if (p.stage() == BloomPayload.HOLD && FRONTS.containsKey(p.entityId()) && FRONTS.get(p.entityId()).ending >= 0) {
            TAILS.add(FRONTS.remove(p.entityId()));
        }
        if (!FRONTS.containsKey(p.entityId()) && p.stage() == BloomPayload.HOLD) {
            var mc = Minecraft.getInstance();
            // rain-15 title card; reuse the existing Reggae One secret-art caption renderer.
            if (Shape.has(p.stars(), PlumLayers.Kind.CAPTION) && mc.player != null && mc.player.getId() == p.entityId())
                TechniqueCaption.showSecret(net.minecraft.network.chat.Component.translatable("technique.murim.twenty_four_plum.school"),
                        net.minecraft.network.chat.Component.translatable("form.murim.twenty_four_plum_full_bloom"), 40);
        }
        Front f = FRONTS.computeIfAbsent(p.entityId(), id -> new Front());
        f.state = p;
        if (f.feet == null) {
            var entity = Minecraft.getInstance().level == null ? null : Minecraft.getInstance().level.getEntity(p.entityId());
            f.feet = entity == null ? p.a().add(0, -1, 0) : entity.position();
        }
        f.sincePacket = 0;
        if (p.stage() == BloomPayload.RELEASE) {
            f.release = f.age;
            var mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.getEntity(p.entityId()) instanceof AbstractClientPlayer player)
                MurimPlayerAnimations.play(player, RELEASE);
        }
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        syncLevel();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { FRONTS.clear(); TAILS.clear(); return; }
        FRONTS.values().removeIf(f -> f.ending >= 0 && f.age - f.ending >= 340 && f.petals.isEmpty());
        TAILS.removeIf(f -> f.age - f.ending >= 340 && f.petals.isEmpty());
        for (Front f : fronts()) {
            if (f.ending < 0 && (++f.sincePacket > 44 || f.release >= 0 && f.age - f.release >= 30)) end(f.state.entityId());
            animate(f);
        }
        for (Front f : fronts()) {
            f.age++;
            if (f.ending >= 0 || f.release >= 0 || mc.player == null || mc.player.getId() != f.state.entityId()) continue;
            boolean held = io.github.verycooltimo.murim.client.ModKeyMappings.TECHNIQUE.isDown();
            if (f.sentHeld == null || f.sentHeld != held) {
                f.sentHeld = held;
                PacketDistributor.sendToServer(new SeaHoldPayload(held));
            }
        }
    }

    private static Vec3 direction(Front f) {
        Vec3 d = new Vec3(f.state.b().x, 0, f.state.b().z).normalize();
        return d.lengthSqr() < .01 ? new Vec3(0, 0, 1) : d;
    }
    private static Vec3 branch(Front f, double u) {
        Vec3 d = direction(f), side = new Vec3(-d.z, 0, d.x);
        // One rigid angular sword; elbows never oscillate or reverse.
        int node = (int) Math.round(u * 5);
        double elbow = node == 0 || node == 5 ? 0 : node % 2 == 0 ? -.28 : .32;
        double reach = Math.min(Shape.reach(f.state.executionTier()), Math.max(.4, f.state.value()));
        return f.feet.add(d.scale(-.97 + (reach + .97) * u)).add(side.scale(elbow + .12 * (1 - u)))
                .add(0, 1.22 + .12 * (node % 2), 0);
    }
    private static double settling(Front f, double age) {
        return f.ending < 0 ? -1 : age - Shape.settleStart(f.ending, f.hitAge);
    }
    private static Vec3 trunk(Front f, double u) {
        Vec3 d = direction(f), side = new Vec3(-d.z, 0, d.x);
        // All elbows remain behind the caster; the first reaches the blade branch at y=1.05.
        int node = (int) Math.round(u * 4);
        return f.feet.add(d.scale(Shape.trunkRear(node)))
                .add(side.scale(node % 2 == 0 ? -.12 : .12)).add(0, Shape.trunkY(node), 0);
    }
    private static Vec3 crown(Front f, int i) {
        double angle = i * 2.399963, r = 1.0 + Math.sqrt(i / (double) Math.max(1, Shape.crown(f.state.executionTier()))) * 2.6;
        return f.feet.add(Math.cos(angle) * r, Shape.crownY(i), Math.sin(angle) * r);
    }
    private static void contact(int id, Vec3 at) {
        Front f = FRONTS.get(id);
        if (f == null) return;
        f.hit = at; f.hitAge = f.age;
        TechniqueLight.add(at, 0xE3264E, 5, .85F * Shape.light(f.state.stars()), 1, 1, 10);
        Vec3 d = direction(f);
        for (int i = 0; i < Shape.count(f.state.stars(), PlumLayers.Kind.PETALS, f.state.stars() < 10 ? 4 : 30); i++) {
            double a = i * 2.399963;
            spawn(f, at, d.scale(.16 + i % 5 * .04).add(Math.cos(a) * .1, .08 + (i % 7) * .025, Math.sin(a) * .1), i + f.age);
        }
    }
    // API: supplied minecraft-src/net/minecraft/client/{Options#particles,OptionInstance#get}.java.
    private static int petalLimit() {
        int count = switch (io.github.verycooltimo.murim.client.ClientConfig.VFX_QUALITY.get()) {
            case HIGH -> 240;
            case MEDIUM -> 160;
            case LOW -> 80;
        };
        return switch (Minecraft.getInstance().options.particles().get()) {
            case ALL -> count;
            case DECREASED -> count / 2;
            case MINIMAL -> count / 8;
        };
    }
    private static void spawn(Front f, Vec3 at, Vec3 velocity, int seed) {
        if (VfxBudget.hasRoom() && f.petals.size() < Math.min(petalLimit(), Shape.looseLimit(f.state.stars())) && fronts().stream().mapToInt(front -> front.petals.size()).sum() < 720) f.petals.add(new Petal(at, velocity, seed));
    }
    private static void animate(Front f) {
        if (f.feet == null) return;
        int tier = f.state.executionTier();
        if (f.age == 12 && f.state.stars() >= 10 && f.has(PlumLayers.Kind.PETALS)) {
            for (int i = 0; i < Shape.count(f.state.stars(), PlumLayers.Kind.PETALS, 220); i++) {
                double phase = i * 2.399963;
                Vec3 tip = branch(f, 1).subtract(direction(f).scale((i % 17) * .08));
                spawn(f, tip.add(Math.cos(phase) * .22, Math.sin(phase) * .18, Math.sin(phase) * .22),
                        direction(f).scale(.07 + i % 11 * .009).add(Math.cos(phase) * .04, .025 + i % 5 * .01, Math.sin(phase) * .04), i);
            }
        }
        if (settling(f, f.age) < 40) {
            if (f.has(PlumLayers.Kind.AURA) && f.age % 6 == 0) {
                TechniqueLight.add(f.feet.add(0, .15, 0), 0xE3264E, f.state.stars() < 10 ? 1.2F : 3.5F, .35F * Shape.light(f.state.stars()), 3, 3, 8);
                TechniqueLight.add(f.feet.add(direction(f).scale(.15)).add(0, 1.05, 0), 0xE3264E, f.state.stars() < 10 ? 1.2F : 2, .18F * Shape.light(f.state.stars()), 3, 3, 8);
            }
            int count = Shape.crown(tier, f.state.stars());
            if (count > 0 && f.age > 12 && f.age % (settling(f, f.age) >= 0 ? 1 : 2) == 0) {
                int i = (f.age * 7) % count;
                spawn(f, crown(f, i), new Vec3(Math.sin(i) * .045, -.035, Math.cos(i) * .035), f.age + i);
            }
            if (f.has(PlumLayers.Kind.PETALS) && f.age > 8 && f.age % (f.state.stars() < 10 ? 8 : 2) == 0 && Shape.flowers(tier) > 0) {
                int i = f.age % Shape.flowers(tier);
                spawn(f, f.state.stars() < 10 ? f.feet.add(direction(f).scale(.7)).add(0, 1.05, 0) : branch(f, (i % 5 + 1) / 5.), direction(f).scale(.08 + i % 3 * .03).add(0, .04, 0), f.age);
            }
        }
        for (Petal p : f.petals) {
            p.previous = p.at;
            p.age++;
            if (p.age <= Math.floorMod(p.seed, 7)) continue;
            if (p.landed >= 0) continue;
            // D3: independent speed halves in eight ticks, with slow terminal fall.
            p.velocity = p.velocity.scale(.917).add(0, -.003, 0);
            Vec3 next = p.at.add(p.velocity).add(Math.sin((p.age + p.seed) * .08) * .006, 0, 0);
            Vec3 floor = floor(p.at, next);
            if (floor != null) { p.at = floor.add(0, .025, 0); p.landed = p.age; }
            else p.at = next;
        }
        f.petals.removeIf(p -> p.landed >= 0 ? p.age - p.landed > 60 + Math.floorMod(p.seed, 101) : p.age > 180);
    }
    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/level/{BlockGetter#clip,LevelReader#hasChunkAt,
    // ClipContext}.java; phys/{HitResult,shapes/CollisionContext}.java; core/BlockPos#containing.
    private static Vec3 floor(Vec3 from, Vec3 to) {
        var world = Minecraft.getInstance().level;
        if (world == null || !world.hasChunkAt(net.minecraft.core.BlockPos.containing(to))) return null;
        var hit = world.clip(new net.minecraft.world.level.ClipContext(from, to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? null : hit.getLocation();
    }
    // API: read-only RenderLevelStageEvent#get{Stage,PoseStack,Camera,PartialTick};
    // Minecraft#renderBuffers, RenderBuffers#bufferSource, MultiBufferSource.BufferSource#getBuffer/endBatch,
    // Camera#getPosition, DeltaTracker#getGameTimeDeltaPartialTick, Vec3 vector operations.
    // PoseStack is supplied by the event; world positions are camera-relative at the VfxDraw boundary.
    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || FRONTS.isEmpty() && TAILS.isEmpty()) return;
        Vec3 camera = event.getCamera().getPosition();
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        // [НЕПРОВЕРЕНО: PoseStack.last is absent from the supplied source export (Blaze3D);
        // copied from RainVfx's established path; verify with compileJava on the runtime machine.]
        var pose = event.getPoseStack().last();
        double partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        VertexConsumer wood = buffers.getBuffer(MurimRenderTypes.solid());
        for (Front f : fronts()) {
            if (f.feet == null) continue;
            double age = f.age + partial;
            double settle = settling(f, age);
            int tier = f.state.executionTier();
            Vec3 d = direction(f), base = trunk(f, 0);
            // Source-sized layers remain when the larger tree rows arrive.
            // Vec3 add/subtract/scale and constructor verified in read-only 1.21.1 Vec3.java.
            // VfxDraw's light overload keeps the physical trail non-emissive.
            if (f.has(PlumLayers.Kind.TRAIL)) {
                Vec3 hand = f.feet.add(d.scale(.15)).add(0, 1.05, 0);
                Vec3 tip = hand.add(d.scale(.9));
                float sourceFade = (float) (Shape.smooth(age / 4) * Shape.woodFade(settle, 0));
                Vec3 side = new Vec3(-d.z, 0, d.x).scale(.08);
                Vec3 normal = new Vec3(0, 1, 0);
                for (Vec3 point : new Vec3[]{hand.subtract(side), tip.subtract(side), tip.add(side), hand.add(side)})
                    VfxDraw.vertex(wood, pose, point.subtract(camera), normal, 0, 0,
                            sourceFade * .35F, .16F, .17F, .43F, 0);
                if (f.has(PlumLayers.Kind.GHOST))
                    VfxDraw.segment(wood, pose, hand.add(0, -.08, 0).subtract(camera), tip.add(0, -.16, 0).subtract(camera), Vec3.ZERO,
                            .12, sourceFade * .22F, .29F, .05F, .13F);
                if (f.has(PlumLayers.Kind.AURA))
                    VfxDraw.segment(wood, pose, hand.subtract(camera), tip.subtract(camera), Vec3.ZERO,
                            .08, sourceFade * Shape.light(f.state.stars()), .89F, .15F, .30F);
            }
            for (int i = 0; i < Shape.roots(tier, f.state.stars()); i++) {
                double a = i * 2.399963, length = 2 + (i % 4) * .65;
                Vec3 prev = base.add(0, .055, 0);
                for (int j = 1; j <= 3; j++) {
                    double turn = a + (j % 2 == 0 ? .24 : -.16);
                    Vec3 next = base.add(Math.cos(turn) * length * j / 3, .055, Math.sin(turn) * length * j / 3);
                    tube(wood, pose, camera, d, prev, next, .19 - (j - 1) * .045, .19 - j * .036, (float) (Shape.smooth(age / 6) * Shape.woodFade(settle, (j - 1) / 3.)));
                    prev = next;
                }
            }
            if (tier >= 2 && f.has(PlumLayers.Kind.ROOTS)) {
                for (int i = 0; i < 4; i++) {
                    double u = i / 4., visible = Math.max(0, Math.min(1, age / 7 * 4 - i));
                    Vec3 from = trunk(f, u), to = from.add(trunk(f, (i + 1) / 4.).subtract(from).scale(visible));
                    tube(wood, pose, camera, d, from, to, Shape.trunkDiameter(u), Shape.trunkDiameter((i + 1) / 4.),
                            (float) Shape.woodFade(settle, i / 3.));
                }
                for (int i = 0; Shape.crown(tier, f.state.stars()) > 0 && i < 6; i++) {
                    Vec3 from = trunk(f, .75), to = crown(f, i * 7).add(0, -.12, 0);
                    tube(wood, pose, camera, d, from, from.add(to.subtract(from).scale(Shape.smooth((age - 5) / 7))),
                            .22, .08, (float) Shape.woodFade(settle, 1));
                    for (int twig = 0; twig < (i % 2 == 0 ? 4 : 3); twig++) {
                        double u = .4 + twig * .16;
                        Vec3 fork = from.lerp(to, u);
                        double angle = i * 2.399963 + (twig % 2 == 0 ? 1 : -1) * Math.toRadians(15 + twig * 6);
                        Vec3 tip = fork.add(Math.cos(angle) * (.6 + twig * .15), .25 + twig * .08, Math.sin(angle) * (.6 + twig * .15));
                        tube(wood, pose, camera, d, fork, fork.lerp(tip, Shape.smooth((age - 7 - twig) / 5)), .10, .08,
                                (float) Shape.woodFade(settle, 1));
                    }
                }
            }
            double growth = Shape.smooth((age - 2) / 5);
            for (int i = 0; i < Shape.branches(tier, f.state.stars()); i++) {
                double u = i / 5., visible = Math.max(0, Math.min(1, growth * 5 - i));
                if (visible <= 0) continue;
                Vec3 from = branch(f, u), to = from.add(branch(f, (i + 1) / 5.).subtract(from).scale(visible));
                double tail = Shape.woodFade(settle, i / 4.);
                tube(wood, pose, camera, d, from, to, Shape.diameter(u), Shape.diameter((i + 1) / 5.), (float) (Shape.smooth(age / 6) * tail));
            }
            if (f.state.stars() >= 10 && f.hit != null && age - f.hitAge < 2) {
                float flash = (float) (1 - (age - f.hitAge) / 2);
                if (age - f.hitAge < 2) {
                    VfxDraw.billboard(wood, pose, f.hit.subtract(camera), Vec3.ZERO, .4,
                            (float) (1 - (age - f.hitAge) / 2), 1, .94F, .95F);
                }
                for (int i = 0; i < 6; i++) {
                    double a = i * Math.PI / 3;
                    Vec3 side = new Vec3(-d.z, 0, d.x);
                    Vec3 from = f.hit.add(side.scale(Math.cos(a) * .22)).add(0, Math.sin(a) * .22, 0);
                    Vec3 to = from.add(d.scale(1 + i % 3 * .45));
                    VfxDraw.segment(wood, pose, from.subtract(camera), to.subtract(camera), Vec3.ZERO, .035, flash, .89F, .15F, .30F);
                    VfxDraw.segment(wood, pose, from.subtract(camera), to.subtract(camera), Vec3.ZERO, .009, flash, 1, .94F, .95F);
                }
            }
        }
        buffers.endBatch(MurimRenderTypes.solid());
        VertexConsumer air = buffers.getBuffer(MurimRenderTypes.rainAir());
        for (Front f : fronts()) {
            if (f.feet == null || !f.has(PlumLayers.Kind.WIND)) continue;
            double a = Shape.smooth((f.age + partial) / 8) * Shape.woodFade(settling(f, f.age + partial), 0);
            for (int i = 0; i < 24; i++) {
                double x = i * Math.PI / 12, y = (i + 1) * Math.PI / 12;
                Vec3 at = f.feet.add(0, .018, 0);
                double radius = f.state.stars() < 10 ? .8 : 2.6;
                if (f.state.stars() < 10 && i < 8 && f.has(PlumLayers.Kind.DUST)) {
                    // Independent, braking dust offsets stay in the foot plane; no high-star geometry changes.
                    double drift = 1 - Math.exp(-Math.max(0, f.age + partial - i % 7) / (8. + i % 4));
                    Vec3 dust = at.add(Math.cos(x * 3) * (.3 + .4 * drift), .025 + i % 3 * .035,
                            Math.sin(x * 3) * (.3 + .4 * drift));
                    double size = .035 + i % 3 * .012;
                    quad(air, pose, camera, dust.add(-size, 0, -size), dust.add(size, 0, -size),
                            dust.add(size, 0, size), dust.add(-size, 0, size),
                            .2203776F, .2203776F, .1918945F, .1918945F,
                            (float) a * .18F * (float) Shape.smooth((f.age + partial - i % 7) / 4), 0x4A0D22);
                    continue;
                }
                Vec3[] glow = {at.add(Math.cos(x) * .25, 0, Math.sin(x) * .25), at.add(Math.cos(x) * radius, 0, Math.sin(x) * radius),
                        at.add(Math.cos(y) * radius, 0, Math.sin(y) * radius), at.add(Math.cos(y) * .25, 0, Math.sin(y) * .25)};
                for (int corner = 0; corner < 4; corner++) {
                    float edge = corner == 1 || corner == 2 ? 0 : 1;
                    VfxDraw.vertex(air, pose, glow[corner].subtract(camera), new Vec3(0, 1, 0), .2203776F, .1918945F,
                            (float) a * .12F * edge, f.has(PlumLayers.Kind.AURA) ? .89F : .16F,
                            f.has(PlumLayers.Kind.AURA) ? .15F : .17F, f.has(PlumLayers.Kind.AURA) ? .30F : .43F);
                }
            }
        }
        buffers.endBatch(MurimRenderTypes.rainAir());
        VertexConsumer petals = buffers.getBuffer(MurimRenderTypes.rainPetals());
        for (Front f : fronts()) drawPetals(f, pose, petals, camera, f.age + partial);
        buffers.endBatch(MurimRenderTypes.rainPetals());
    }
    private static void tube(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Vec3 facing, Vec3 from, Vec3 to, double start, double end, float alpha) {
        Vec3 axis = to.subtract(from).normalize();
        Vec3 right = axis.cross(Math.abs(axis.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 up = axis.cross(right).normalize();
        for (int i = 0; i < 5; i++) {
            double a = i * Math.PI * 2 / 5, b = (i + 1) * Math.PI * 2 / 5;
            Vec3 r = right.scale(Math.cos(a)).add(up.scale(Math.sin(a))), s = right.scale(Math.cos(b)).add(up.scale(Math.sin(b)));
            quad(v, pose, camera, from.add(r.scale(start / 2)), to.add(r.scale(end / 2)), to.add(s.scale(end / 2)), from.add(s.scale(start / 2)), 0, 1, 0, 1, alpha, i % 2 == 0 ? 0x2A0812 : 0x4A0D22);
        }
        // A narrow surface crack and a separate crimson rim preserve the dark bark mass.
        Vec3 off = up.scale(start * .51);
        VfxDraw.segment(v, pose, from.add(off).subtract(camera), to.add(up.scale(end * .51)).subtract(camera), Vec3.ZERO, .012, alpha * .65F, .65F, .08F, .16F);
        Vec3 rim = facing.subtract(axis.scale(facing.x * axis.x + facing.y * axis.y + facing.z * axis.z)).normalize();
        if (rim.lengthSqr() < .01) rim = up;
        VfxDraw.segment(v, pose, from.add(rim.scale(start * .505)).subtract(camera),
                to.add(rim.scale(end * .505)).subtract(camera), Vec3.ZERO, .009,
                alpha * .8F, .89F, .15F, .30F);
    }
    private static void drawPetals(Front f, PoseStack.Pose pose, VertexConsumer v, Vec3 camera, double age) {
        if (f.feet == null) return;
        int tier = f.state.executionTier();
        double fade = Shape.blossomFade(settling(f, age));
        for (int i = 0; i < Shape.clusters(tier, f.state.stars()); i++) {
            int slot = i / Shape.blossomsPerNode(tier), petal = i % Shape.blossomsPerNode(tier);
            double u = (slot + 1.) / Shape.nodes(tier);
            double born = 2 + u * 5 + i % 4;
            double open = Shape.smooth((age - born) / (3 + i % 3));
            Vec3 d = direction(f), side = new Vec3(-d.z, 0, d.x);
            Vec3 at = branch(f, u).add(side.scale((petal - (Shape.blossomsPerNode(tier) - 1) / 2.) * .26))
                    .add(0, petal % 2 == 1 ? .30 : -.18, 0);
            double blossom = .35 + i % 4 * .10;
            if (!PlumPolish.headClear(at.distanceToSqr(f.feet.add(0, 1.62, 0)), blossom / 2)) continue;
            if (f.hit != null && !PlumPolish.headClear(at.distanceToSqr(f.hit.add(0, .5, 0)), blossom / 2)) continue;
            flower(v, pose, camera, at, blossom * open, 8 + i % 2,
                    (float) (fade * open), i * .8, false);
        }
        for (int i = 0; i < Shape.crown(tier, f.state.stars()); i++) {
            double open = Shape.smooth((age - 8 - i % 7) / 5);
            double plane = new double[]{1, .8, .65}[i % 3];
            flower(v, pose, camera, crown(f, i), (.58 + i % 3 * .15) * plane * open, 8 + i % 2, (float) (fade * open * (.65 + .15 * (i % 3))), i, false);
        }
        for (Petal p : f.petals) {
            if (p.landed >= 0 && !f.has(PlumLayers.Kind.FULL) && p.age - p.landed >= 4) continue;
            double life = 60 + Math.floorMod(p.seed, 101);
            float a = (float) (Shape.smooth(p.age / 4.) * (p.landed < 0 ? 1 - Shape.smooth((p.age - 170) / 10.) : 1 - Shape.smooth((p.age - p.landed - life + 8) / 8)));
            if (p.landed >= 0 && !f.has(PlumLayers.Kind.FULL)) a *= (float) (1 - Shape.smooth((p.age - p.landed) / 4.));
            flower(v, pose, camera, p.previous.add(p.at.subtract(p.previous).scale(age - f.age)), .13 + Math.floorMod(p.seed, 3) * .07, Math.floorMod(p.seed, 8), a,
                    p.seed + (p.landed < 0 ? age * (.015 + Math.floorMod(p.seed, 5) * .009) : 0), p.landed >= 0);
        }
    }
    /** Two hinged halves retain volume; atlas paint supplies the white core under 15% area. */
    private static void flower(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Vec3 at, double size, int cell, float alpha, double spin, boolean ground) {
        Vec3 n = ground ? new Vec3(0, 1, 0) : camera.subtract(at).normalize();
        Vec3 right = ground ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0).cross(n).normalize();
        Vec3 up = n.cross(right).normalize();
        Vec3 r = right.scale(Math.cos(spin)).add(up.scale(Math.sin(spin))).scale(size / 2);
        Vec3 u = up.scale(Math.cos(spin)).subtract(right.scale(Math.sin(spin))).scale(size / 2);
        Vec3 ridge = ground ? Vec3.ZERO : n.scale(size * .13);
        float u0 = cell % 4 / 4F, u1 = u0 + .25F, mid = (u0 + u1) / 2;
        float v0 = cell / 4 / 3F, v1 = v0 + 1 / 3F;
        quad(v, pose, camera, at.subtract(r).subtract(u), at.subtract(r).add(u), at.add(u).add(ridge), at.subtract(u).add(ridge), u0, mid, v0, v1, alpha, 0xFFFFFF);
        quad(v, pose, camera, at.subtract(u).add(ridge), at.add(u).add(ridge), at.add(r).add(u), at.add(r).subtract(u), mid, u1, v0, v1, alpha, 0xE3A0AE);
    }
    private static void quad(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                             float u0, float u1, float v0, float v1, float alpha, int color) {
        if (alpha <= .001F) return;
        Vec3 n = b.subtract(a).cross(d.subtract(a)).normalize();
        float r = (color >> 16 & 255) / 255F, g = (color >> 8 & 255) / 255F, blue = (color & 255) / 255F;
        VfxDraw.vertex(v, pose, a.subtract(camera), n, u0, v1, alpha, r, g, blue);
        VfxDraw.vertex(v, pose, b.subtract(camera), n, u0, v0, alpha, r, g, blue);
        VfxDraw.vertex(v, pose, c.subtract(camera), n, u1, v0, alpha, r, g, blue);
        VfxDraw.vertex(v, pose, d.subtract(camera), n, u1, v1, alpha, r, g, blue);
    }

    private FullBloomVfx() {}
}
