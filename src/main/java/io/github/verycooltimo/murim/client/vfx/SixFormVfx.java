package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.CameraShakeHandler;
import io.github.verycooltimo.murim.network.SixFormPayload;
import io.github.verycooltimo.murim.technique.SixRules;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import java.util.*;
import static io.github.verycooltimo.murim.network.SixFormPayload.*;

/** Client facts renderer. Non-additive ink/white strips, existing dust atlas and model clone pattern.
 * Every object has a deadline independent of END delivery. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class SixFormVfx {
    private static final VfxColour WHITE = new VfxColour(244 / 255F, 245 / 255F, 242 / 255F),
            INK = new VfxColour(30 / 255F, 32 / 255F, 38 / 255F);
    private record Fact(SixFormPayload p, int born, int life, double ceiling) {
        Fact(SixFormPayload p, int born, int life) { this(p, born, life, p.at().y + .2); }
    }
    private record Dust(Vec3 at, int born, int cell) { }
    private static final List<Dust> waveDust = new ArrayList<>();
    private record Ghost(SixFormPayload p, int born, PartPose[] pose) { }
    private static final List<Fact> facts = new ArrayList<>();
    private static final List<Ghost> ghosts = new ArrayList<>();
    private record Giant(Fact fact, SixRules.GiantState state) { }
    private static final Map<Integer, Giant> giants = new HashMap<>();
    private static final Set<Integer> scaled = new HashSet<>();
    private static int ticks, darkBorn = -100, darkLife;
    public static void accept(SixFormPayload p) {
        Minecraft mc = Minecraft.getInstance(); if (mc.level == null) return;
        io.github.verycooltimo.murim.client.SixFormAnimations.accept(p);
        if (p.event() == END || p.event() == CANCEL) {
            if (mc.player != null && (p.casterId() == mc.player.getId()
                    || p.targetId() == mc.player.getId())) darkLife = Math.min(darkLife, ticks - darkBorn + 3);
            giants.remove(p.casterId());
            facts.removeIf(f -> f.p.casterId() == p.casterId() && f.p.event() == WAVE);
            if (p.event() == CANCEL) ghosts.removeIf(g -> g.p.casterId() == p.casterId());
            return;
        }
        if (p.event() == WAVE_END) {
            facts.removeIf(f -> f.p.casterId() == p.casterId() && f.p.event() == WAVE); return;
        }
        if (p.event() == START) return;
        if (p.event() == DARK) { darkBorn = ticks; darkLife = (int) p.params().x; return; }
        if (p.event() == GIANT && p.tier() == 4) {
            giants.put(p.casterId(), new Giant(new Fact(p, ticks, (int) p.params().y + SixRules.GIANT_SAFETY),
                    new SixRules.GiantState((float) (p.params().y - p.params().x)))); return;
        }
        if (p.event() == AFTERIMAGE && p.tier() == 4) {
            if (mc.level.getEntity(p.casterId()) instanceof AbstractClientPlayer player
                    && mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer) {
                ModelPart[] parts = parts(renderer.getModel()); PartPose[] pose = new PartPose[parts.length];
                for (int i = 0; i < pose.length; i++) pose[i] = parts[i].storePose();
                ghosts.add(new Ghost(p, ticks, pose));
            }
            return;
        }
        if (p.event() == IMPACT_FRAME) {
            if (mc.player != null && (p.casterId() == mc.player.getId() || p.targetId() == mc.player.getId()))
                io.github.verycooltimo.murim.client.vfx.bt.BtImpactFrame.triggerCombat();
            facts.add(new Fact(p, ticks, SixRules.IMPACT_FRAME_TICKS));
            return;
        }
        if (p.event() == HIT && mc.player != null) {
            if (p.casterId() == mc.player.getId() && p.tier() < 4) CameraShakeHandler.request(.25F);
            var caster = mc.level.getEntity(p.casterId());
            Vec3 centre = caster == null ? p.at() : caster.position();
            if (p.tier() == 4 && mc.player.position().distanceTo(centre) <= 8) {
                CameraShakeHandler.quake(2F / (4.5F * 1.6F), 16);
            }
        }
        if (p.tier() <= 1 || p.event() == FREEZE) return;
        if (p.event() == WAVE) facts.removeIf(f -> f.p.casterId() == p.casterId() && f.p.event() == WAVE);
        var target = mc.level.getEntity(p.targetId());
        double ceiling = target == null ? p.at().y + .2 : target.getY() + target.getBbHeight() * .70;
        int born = ticks;
        if (p.event() == HIT) for (Fact f : facts) {
            if (f.p.event() == HIT && f.p.casterId() == p.casterId() && f.p.form() == p.form()
                    && f.p.params().z == p.params().z) { born = f.born; break; }
        }
        facts.add(new Fact(p, born, p.event() == WAVE ? (int) Math.ceil(p.params().x / p.params().y) + 1 : SixRules.HIT_LIFE, ceiling));
    }
    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { facts.clear(); ghosts.clear(); giants.clear(); scaled.clear(); darkLife = 0; waveDust.clear(); return; }
        if (mc.isPaused()) return;
        ticks++; facts.removeIf(f -> ticks - f.born > f.life);
        waveDust.removeIf(d -> ticks - d.born >= 4);
        for (Fact f : facts) {
            var p = f.p;
            if (p.event() != WAVE || p.tier() != 4) continue;
            double distance = (ticks - f.born) * p.params().y;
            if (distance > p.params().x) continue;
            Vec3 centre = p.at().add(p.axis().scale(distance));
            Vec3 side = new Vec3(p.axis().z, 0, -p.axis().x);
            for (int i = 0; i < 2; i++) {
                Vec3 at = centre.add(side.scale((i == 0 ? -1 : 1) * p.params().z * .55));
                // API/pattern: QiShockwaveVfx ground clip; dust sits on actual ground.
                var ground = mc.level.clip(new ClipContext(at, at.add(0, -8, 0),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.level.getEntity(p.casterId())));
                if (ground.getType() != HitResult.Type.MISS) waveDust.add(new Dust(ground.getLocation().add(0, .08, 0), ticks, i));
            }
        }
        ghosts.removeIf(g -> ticks - g.born > 10);
        giants.values().removeIf(g -> ticks - g.fact.born >= g.fact.life);
    }
    @SubscribeEvent static void pre(RenderPlayerEvent.Pre event) {
        Giant giant = giants.get(event.getEntity().getId()); if (giant == null || event.isCanceled()) return;
        Fact f = giant.fact;
        float age = ticks - f.born + event.getPartialTick() - (float) f.p.params().x;
        float scale = giant.state.scale(age);
        if (scale <= 1) return;
        // Pre runs at the entity's feet, before LivingEntityRenderer's model transforms.
        // Scale this origin directly: no upward translation or model-centre pivot.
        event.getPoseStack().pushPose();
        event.getPoseStack().scale(scale, scale, scale); scaled.add(event.getEntity().getId());
    }
    /** LivingEntityRenderer restores its local matrix before the name tag event. Undo only our scale. */
    @SubscribeEvent static void name(RenderNameTagEvent event) {
        if (scaled.remove(event.getEntity().getId())) event.getPoseStack().popPose();
    }
    @SubscribeEvent static void post(RenderPlayerEvent.Post event) {
        if (scaled.remove(event.getEntity().getId())) event.getPoseStack().popPose();
    }
    @SubscribeEvent static void gui(RenderGuiEvent.Post event) {
        for (Fact f : facts) if (f.p.event() == IMPACT_FRAME) {
            int age = ticks - f.born;
            if (age >= SixRules.IMPACT_FRAME_TICKS) continue;
            if (io.github.verycooltimo.murim.client.vfx.bt.BtImpactFrame.renderCombat(event.getGuiGraphics(), age)) {
                Giant giant = giants.get(f.p.casterId());
                if (giant != null && giant.fact.p.form() == f.p.form()) giant.state.impactFrameStarted();
            }
        }
        float age = ticks - darkBorn + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        if (age < 0 || age >= darkLife) return;
        float alpha = SixRules.darkAlpha(age, darkLife - 3);
        var g = event.getGuiGraphics(); int w = g.guiWidth(), h = g.guiHeight();
        // Existing GuiGraphics.fill API: disjoint rings avoid compounded corner opacity.
        int radius = (Math.min(w, h) + 1) / 2;
        for (int i = 0; i < radius; i += 2) {
            int d = Math.min(2, radius - i);
            float edge = 1 - i / (float) radius;
            int colour = (Math.round(255 * alpha * (.20F + .25F * edge * edge)) << 24) | 0x10121c;
            if (w - 2 * i <= 2 * d || h - 2 * i <= 2 * d) {
                g.fill(i, i, w - i, h - i, colour); break;
            }
            int top = i + d, bottom = h - i - d;
            g.fill(i, i, w - i, top, colour);
            g.fill(i, bottom, w - i, h - i, colour);
            if (bottom > top) {
                g.fill(i, top, Math.min(i + d, w - i), bottom, colour);
                g.fill(Math.max(i + d, w - i - d), top, w - i, bottom, colour);
            }
        }
    }

    @SubscribeEvent static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance(); if (mc.level == null) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition(); PoseStack ps = event.getPoseStack();
        for (Fact f : facts) {
            if (ticks - f.born < SixRules.IMPACT_FRAME_TICKS && f.p.event() == IMPACT_FRAME
                    && mc.level.getEntity(f.p.casterId()) instanceof AbstractClientPlayer caster) {
                // Capture every participant in one pass before GUI releases the giant latch.
                Map<Integer, io.github.verycooltimo.murim.client.vfx.bt.BtBody> bodies = new LinkedHashMap<>();
                var body = io.github.verycooltimo.murim.client.vfx.bt.BtBody.of(caster, camera);
                if (body == null) continue;
                bodies.put(caster.getId(), body);
                Set<Integer> targets = new LinkedHashSet<>();
                targets.add(f.p.targetId());
                for (Fact hit : facts) if (hit.p.event() == HIT && hit.p.casterId() == f.p.casterId()
                        && hit.p.form() == f.p.form() && ticks - hit.born <= 1) targets.add(hit.p.targetId());
                for (int id : targets) {
                    var target = mc.level.getEntity(id);
                    if (target == null || id == caster.getId()) continue;
                    var targetBody = io.github.verycooltimo.murim.client.vfx.bt.BtBody.of(target, camera);
                    if (targetBody != null) bodies.put(id, targetBody);
                }
                io.github.verycooltimo.murim.client.vfx.bt.BtImpactFrame.captureCombat(
                        bodies, camera, event.getModelViewMatrix(), event.getProjectionMatrix());
            }
        }
        var buffers = mc.renderBuffers().bufferSource();
        ps.pushPose();
        try {
            ps.translate(-camera.x, -camera.y, -camera.z);
            RenderType inkType = MurimRenderTypes.swordFinisher();
            // PlumVfx double-strip colours, with fixed world-space width vectors instead of billboarding.
            VertexConsumer v = buffers.getBuffer(inkType);
            Set<String> drawn = new HashSet<>();
            for (Fact f : facts) {
                SixFormPayload p = f.p; float age = ticks - f.born + partial;
                float alpha = Mth.clamp(age, 0, 1) * Mth.clamp(f.life - age, 0, 1);
                Vec3 axis = p.axis(), right = new Vec3(-axis.z, 0, axis.x); // -FallingPetalRules.left(axis)
                if (p.event() == WAVE) {
                    double distance = age * p.params().y;
                    if (distance > p.params().x) continue;
                    Vec3 front = p.at().add(axis.scale(distance));
                    wave(v, ps.last(), front, right, axis, p, alpha);
                    if (p.tier() == 4) wave(v, ps.last(),
                            front.subtract(axis.scale(SixRules.WAVE_TRAIL_DISTANCE)), right, axis, p,
                            alpha * SixRules.WAVE_TRAIL_ALPHA);
                } else if (p.event() == HIT) {
                    int cut = (int) p.params().y;
                    SixRules.Arc[] arcs = SixRules.ARCS[p.form()];
                    SixRules.Arc arc = cut >= 0 && cut < arcs.length ? arcs[cut] : null;
                    // A cleave sends one HIT per victim: only the first contact reveals this scheduled cut.
                    String key = p.casterId() + ":" + p.form() + ":" + p.params().z;
                    int bladeLife = p.tier() == 4 ? SixRules.MASTER_BLADE_LIFE : SixRules.BLADE_LIFE;
                    float bladeAlpha = Mth.clamp(age, 0, 1) * Mth.clamp(bladeLife - age, 0, 1);
                    if (arc != null && drawn.add(key)) blade(v, ps.last(), p, arc, right, bladeAlpha);
                    marks(v, ps.last(), p, arc, right, age, f.ceiling);
                }
            }
            buffers.endBatch(inkType);
            RenderType dust = MurimRenderTypes.dustPuffs(); VertexConsumer d = buffers.getBuffer(dust);
            for (Fact f : facts) {
                var p = f.p; float age = ticks - f.born + partial;
                if (p.event() != HIT || p.tier() < 2 || !(p.form() == 1 || p.tier() == 4) || age > f.life) continue;
                Vec3 feet = p.casterAt();
                float alpha = Mth.clamp(age / 2, 0, 1) * Mth.clamp((f.life - age) / 3, 0, 1) * .5F;
                int count = p.tier() == 4 ? 8 : 2;
                for (int i = 0; i < count; i++) {
                    double angle = Math.PI * 2 * i / count;
                    PlumVfx.puff(d, ps.last(), camera, feet.add(Math.cos(angle) * .5, .12, Math.sin(angle) * .5), .18 + age * .02, i % 4, alpha, .55F);
                }
            }
            for (Dust puff : waveDust) {
                float age = ticks - puff.born + partial;
                PlumVfx.puff(d, ps.last(), camera, puff.at, .18 + age * .04, puff.cell,
                        Mth.clamp((4 - age) / 4, 0, 1) * .55F, .55F);
            }
            buffers.endBatch(dust);
        } finally { ps.popPose(); }
        for (Ghost g : ghosts) drawGhost(mc, ps, camera, g, partial);
    }
    private static Vec3 point(SixRules.Point p, Vec3 at, Vec3 right, Vec3 forward) {
        return at.add(right.scale(p.right())).add(0, p.up(), 0).add(forward.scale(p.forward()));
    }
    private static Vec3 normal(SixRules.Arc arc, Vec3 right, Vec3 forward) {
        Vec3 a = point(arc.start(), Vec3.ZERO, right, forward);
        Vec3 b = point(arc.control(), Vec3.ZERO, right, forward);
        Vec3 c = point(arc.end(), Vec3.ZERO, right, forward);
        Vec3 n = b.subtract(a).cross(c.subtract(a));
        return n.lengthSqr() < 1E-10 ? new Vec3(0, 1, 0) : n.normalize();
    }
    private static void blade(VertexConsumer v, PoseStack.Pose pose, SixFormPayload p,
                              SixRules.Arc arc, Vec3 right, float alpha) {
        Vec3 a = point(arc.start(), p.casterAt(), right, p.axis());
        Vec3 b = point(arc.control(), p.casterAt(), right, p.axis());
        Vec3 c = point(arc.end(), p.casterAt(), right, p.axis());
        Vec3[] path = new Vec3[SixRules.ARC_SEGMENTS + 1]; double[] widths = new double[path.length];
        for (int i = 0; i < path.length; i++) {
            double t = i / (double) SixRules.ARC_SEGMENTS, u = 1 - t;
            path[i] = arc.thrust() ? a.scale(u).add(c.scale(t))
                    : a.scale(u * u).add(b.scale(2 * u * t)).add(c.scale(t * t));
            // The thrust is a long straight wedge; every curved cut tapers at both ends.
            widths[i] = SixRules.BLADE_WIDTH[p.tier()] * (arc.thrust() ? (1 - t) * .55 : Math.sin(Math.PI * t));
        }
        ribbon(v, pose, path, widths, normal(arc, right, p.axis()), alpha);
    }
    private static void wave(VertexConsumer v, PoseStack.Pose pose, Vec3 at, Vec3 right,
                             Vec3 forward, SixFormPayload p, float alpha) {
        double tilt = Math.toRadians(25), halfHeight = SixRules.WAVE_HALF_HEIGHT[p.tier()];
        Vec3 up = new Vec3(0, Math.cos(tilt), 0).add(right.scale(Math.sin(tilt)));
        Vec3 across = right.scale(Math.cos(tilt)).add(0, -Math.sin(tilt), 0);
        Vec3 bow = across.scale(2 * p.params().z).add(forward.scale(.35));
        Vec3[] path = new Vec3[25]; double[] widths = new double[25];
        for (int i = 0; i < path.length; i++) {
            double t = i / 24D, sine = Math.sin(Math.PI * t);
            path[i] = at.subtract(across.scale(p.params().z)).add(up.scale(Math.cos(Math.PI * t) * halfHeight)).add(bow.scale(sine));
            widths[i] = sine * SixRules.WAVE_RIBBON_WIDTH[p.tier()];
        }
        ribbon(v, pose, path, widths, up.cross(bow).normalize(), alpha);
    }
    private static void marks(VertexConsumer v, PoseStack.Pose pose, SixFormPayload p,
                              SixRules.Arc arc, Vec3 right, float age, double ceiling) {
        Vec3 n = arc == null ? p.axis() : normal(arc, right, p.axis());
        Vec3 along = arc == null ? right : point(arc.end(), Vec3.ZERO, right, p.axis())
                .subtract(point(arc.start(), Vec3.ZERO, right, p.axis())).normalize();
        Vec3 across = n.cross(along).normalize();
        int count = p.tier() == 2 ? 3 : 5;
        float fade = SixRules.hitAlpha(age);
        for (int i = 0; i < count; i++) {
            double angle = -1.1 + 2.2 * i / (count - 1D);
            double length = p.tier() == 2 ? .20 + .05 * i : .30 + .0375 * i;
            double width = Math.min(p.tier() == 2 ? .035 : .055, Math.max(0, ceiling - p.at().y) * .8);
            Vec3 delta = along.scale(Math.cos(angle) * length).add(across.scale(Math.sin(angle) * length));
            // Shorten inside the cut plane, rather than projecting a high endpoint out of that plane.
            if (delta.y > 0) delta = delta.scale(Math.min(1, Math.max(0, ceiling - p.at().y - width) / delta.y));
            ribbon(v, pose, new Vec3[]{p.at(), p.at().add(delta)}, new double[]{width, 0}, n, fade);
        }
        if (p.tier() == 4 && age < SixRules.SLIT_LIFE) {
            double top = Math.min(p.at().y + .45, ceiling);
            // Contact is already on the target's front surface; shift towards the caster to avoid z fighting.
            Vec3 front = p.at().subtract(p.axis().scale(.035));
            ribbon(v, pose, new Vec3[]{new Vec3(front.x, top - .9, front.z), new Vec3(front.x, top, front.z)},
                    new double[]{.045, .045}, p.axis(), 1);
        }
    }
    /** PlumVfx.strip's vertex pattern and ink/white double strip, but width lives inside the cut plane.
     * NO_CULL swordFinisher renders both faces. The small prism depth keeps an exactly edge-on cut legible. */
    private static void ribbon(VertexConsumer v, PoseStack.Pose pose, Vec3[] path, double[] widths, Vec3 n, float alpha) {
        if (alpha <= 0) return;
        Vec3[] edges = new Vec3[path.length];
        for (int i = 0; i < path.length; i++) {
            Vec3 tangent = path[Math.min(i + 1, path.length - 1)].subtract(path[Math.max(0, i - 1)]);
            edges[i] = n.cross(tangent).normalize();
        }
        for (int layer = 0; layer < 2; layer++) {
            VfxColour colour = layer == 0 ? INK : WHITE;
            double scale = layer == 0 ? 1.25 : 1;
            for (int face = -1; face <= 1; face += 2) {
                for (int i = 0; i + 1 < path.length; i++) {
                    Vec3 depth0 = n.scale(widths[i] * .12 * face + (layer == 0 ? 0 : .001 * face));
                    Vec3 depth1 = n.scale(widths[i + 1] * .12 * face + (layer == 0 ? 0 : .001 * face));
                    Vec3 a = path[i].add(depth0), b = path[i + 1].add(depth1);
                    quad(v, pose, a.subtract(edges[i].scale(widths[i] * scale)),
                            b.subtract(edges[i + 1].scale(widths[i + 1] * scale)),
                            b.add(edges[i + 1].scale(widths[i + 1] * scale)),
                            a.add(edges[i].scale(widths[i] * scale)), n, alpha, colour);
                    // Close both long edges: a flat ribbon alone vanishes exactly along its plane.
                    if (face == 1) for (int side : new int[]{-1, 1}) {
                        Vec3 e0 = edges[i].scale(widths[i] * scale * side), e1 = edges[i + 1].scale(widths[i + 1] * scale * side);
                        quad(v, pose, path[i].add(e0).subtract(depth0), path[i + 1].add(e1).subtract(depth1),
                                b.add(e1), a.add(e0), edges[i], alpha, colour);
                    }
                }
            }
        }
    }
    private static void quad(VertexConsumer v, PoseStack.Pose pose, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                             Vec3 n, float alpha, VfxColour colour) {
        VfxDraw.vertex(v, pose, a, n, 0, 0, alpha, colour.red(), colour.green(), colour.blue());
        VfxDraw.vertex(v, pose, b, n, 1, 0, alpha, colour.red(), colour.green(), colour.blue());
        VfxDraw.vertex(v, pose, c, n, 1, 1, alpha, colour.red(), colour.green(), colour.blue());
        VfxDraw.vertex(v, pose, d, n, 0, 1, alpha, colour.red(), colour.green(), colour.blue());
    }
    private static ModelPart[] parts(PlayerModel<?> m) {
        return new ModelPart[]{m.head, m.hat, m.body, m.jacket, m.rightArm, m.rightSleeve, m.leftArm, m.leftSleeve,
                m.rightLeg, m.rightPants, m.leftLeg, m.leftPants};
    }
    private static void drawGhost(Minecraft mc, PoseStack ps, Vec3 camera, Ghost g, float partial) {
        if (!(mc.level.getEntity(g.p.casterId()) instanceof AbstractClientPlayer player)
                || !(mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer)) return;
        var model = renderer.getModel(); ModelPart[] parts = parts(model); PartPose[] saved = new PartPose[parts.length];
        for (int i = 0; i < parts.length; i++) { saved[i] = parts[i].storePose(); parts[i].loadPose(g.pose[i]); }
        RenderType type = RenderType.entityTranslucent(player.getSkin().texture());
        var buffers = mc.renderBuffers().bufferSource(); float age = ticks - g.born + partial;
        float alpha = Mth.clamp(age / 2, 0, 1) * Mth.clamp((10 - age) / 10, 0, 1) * .45F;
        ps.pushPose();
        try {
            Vec3 at = g.p.at().subtract(camera); ps.translate(at.x, at.y, at.z);
            ps.mulPose(Axis.YP.rotationDegrees(180 - (float) g.p.params().x));
            ps.scale(-.9375F, -.9375F, .9375F); ps.translate(0, -1.501F, 0);
            model.renderToBuffer(ps, buffers.getBuffer(type), 0x00F000F0, OverlayTexture.NO_OVERLAY, ((int) (alpha * 255) << 24) | 0x1E2026);
            buffers.endBatch(type);
        } finally {
            ps.popPose(); for (int i = 0; i < parts.length; i++) parts[i].loadPose(saved[i]);
        }
    }
    private SixFormVfx() { }
}
