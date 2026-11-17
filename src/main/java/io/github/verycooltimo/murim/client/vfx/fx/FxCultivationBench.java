package io.github.verycooltimo.murim.client.vfx.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.bt.BtCtx;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

/**
 * Bench element «cultivation»: the aura of reference panel 13 as a volume. ONE ink tree grown in 3D (FxTree3D) is drawn in one batch from
 * its stored paths (FxStroke#drawTree); the violet-blue field is six curved sheets (FxCultivationScene#SHEETS, FxEssence.Strip), the
 * lights are additive vertex-colour meshes that face the camera. Draw order: the lights, the sheets behind the ink (far to near for the
 * camera), the ink, the sheets in front. The first second is empty (the body is still sitting down).
 * /murim dev vfx bench cultivation; tools/capture/bench_film.sh cultivation. The cost of every frame is logged once a second (FxPerf).
 *
 * Why the tree's halo did not show in the game (round 5): INK_TREE_HALO has glow = 0, so it never entered the glow target;
 * FxInkTreeBench never called BtGlow.prepare, so a positive glow would have found BtGlow.active() false; and the stroke is capped at 0.2
 * blocks half width (FxInkTraceScene.HALO_CAP) and fades to nothing at its edge: a thin rim, not a light. Here the light is a mesh, and
 * BtGlow is not used at all: its prepare/blur/composite passes cost several full-screen passes for layers that need none.
 */
public final class FxCultivationBench {
    private FxCultivationBench() {}

    /** The quality governor (fed with the frame time) and the particles; a dev override: -Dmurim.fx.tier=n pins a tier. */
    private static final FxQuality QUALITY = new FxQuality();
    private static final FxCultivationMotes MOTES = new FxCultivationMotes(14);
    private static double lastT = -1;
    private static int lastTier = -1;

    static {
        try { QUALITY.force(Integer.parseInt(System.getProperty("murim.fx.tier", "-1"))); } catch (NumberFormatException ignored) { }
    }

    public static void render(BtCtx c) {
        if (!FxStroke.available() || !FxFlame.available()) return;
        double t = c.labSeconds();
        FxTree3D.get();                    // built in the empty first second: no hitch when the ink starts
        FxFlame.warm();                    // so is the flame texture
        double dt = lastT < 0 || t < lastT ? 0 : t - lastT;
        lastT = t;
        QUALITY.update(FxPerf.lastFrameMs(), dt);
        FxTree3D.get().detailCut = QUALITY.detailCut();
        if (QUALITY.tier() != lastTier) { io.github.verycooltimo.murim.MurimMod.LOGGER.info("Cultivation quality tier {} (frame {} ms)", QUALITY.tier(), Math.round(QUALITY.smoothedMs() * 10) / 10.0); lastTier = QUALITY.tier(); }
        if (t >= FxCultivationScene.START) {
            MOTES.step(dt, FxCultivationScene.glow(t), QUALITY.particleScale());
            draw(c, t);
        }
    }

    private static void draw(BtCtx c, double t) {
        var body = c.body();
        Vec3 feet = new Vec3(body.axis().x, body.ground(), body.axis().z), right = body.right(), near = body.near();
        // the camera's horizontal direction in the body frame (x right, z near): the far things are drawn first
        Vec3 toCam = c.camera().subtract(feet);
        double cx = toCam.dot(right), cz = toCam.dot(near), len = Math.max(1e-6, Math.hypot(cx, cz));
        cx /= len; cz /= len;
        // screen height in pixels over 2 tan(fov / 2): the pixels per block at distance d is k / d (the coverage estimate of FxPerf)
        var mc = c.mc();
        double k = mc.getWindow().getHeight() / (2 * Math.tan(Math.toRadians(mc.options.fov().get()) / 2));

        glowMeshes(c, feet, right, near, t, cx, cz, k);
        dust(c, feet, right, near, t);
        List<FxCultivationScene.Sheet> sheets = new ArrayList<>(List.of(FxCultivationScene.SHEETS));
        final double fx = cx, fz = cz;
        sheets.sort(java.util.Comparator.comparingDouble(s -> FxCultivationScene.sheetDepth(s, fx, fz)));
        for (var s : sheets) sheet(c, s, t, feet, right, near, cx, cz, k);
        FxHalo.draw(FxTree3D.get(), t, c.camera(), feet, right, near, c.pose(), c.buffers(), 1.0);
        FxStroke.drawTree(FxLook.CULT_RIBBON_BLUE, FxTree3D.get(), FxTree3D.BLUE, .75F, t, c.camera(), feet, right, near, c.pose(), c.buffers());
        FxStroke.drawTree(FxLook.CULT_RIBBON_VIOLET, FxTree3D.get(), FxTree3D.VIOLET, .75F, t, c.camera(), feet, right, near, c.pose(), c.buffers());
        FxStroke.drawTree(FxLook.INK_TRACE, FxTree3D.get(), FxTree3D.INK, 1F, t, c.camera(), feet, right, near, c.pose(), c.buffers());
        motes(c, MOTES, feet, right, near);
    }

    /** The low stirred dust ring (the approved cel dust, FxDust, with the aura's tint). */
    private static void dust(BtCtx c, Vec3 feet, Vec3 right, Vec3 near, double t) {
        if (!FxDust.available()) return;
        var camera = c.mc().gameRenderer.getMainCamera();
        var left = camera.getLeftVector();
        Vec3 camRight = new Vec3(-left.x(), -left.y(), -left.z()), camUp = new Vec3(camera.getUpVector());
        long t0 = System.nanoTime();
        List<io.github.verycooltimo.murim.client.vfx.fx.FxDustSim.Puff> puffs = FxCultivationDust.at(t, FxCultivationScene.glow(t), QUALITY.particleScale());
        FxDust.draw(FxCultivationDust.LOOK, puffs, new FxDust.Frame(feet, right, near, camRight, camUp), c.pose(), c.buffers(), t - FxCultivationScene.START);
        FxPerf.draw();
        FxPerf.build(System.nanoTime() - t0);
    }

    /**
     * The particles: soft round lights (centre pale, edge the colour, alpha falling to nothing) and short streaks for the sparks, all additive
     * vertex-colour quads facing the camera, one batch.
     */
    public static void motes(BtCtx c, FxCultivationMotes motes, Vec3 feet, Vec3 right, Vec3 near) {
        var camera = c.mc().gameRenderer.getMainCamera();
        var left = camera.getLeftVector();
        Vec3 cr = new Vec3(-left.x(), -left.y(), -left.z()), cu = new Vec3(camera.getUpVector());
        var type = MurimRenderTypes.presetLight();
        long t0 = System.nanoTime();
        VertexConsumer out = c.buffers().getBuffer(type);
        PoseStack.Pose pose = c.pose();
        int verts = 0;
        for (var m : motes.motes) {
            double e = m.envelope();
            if (e <= .01) continue;
            Vec3 p = at(feet, right, near, new double[]{m.x, m.y, m.z});
            int r = (m.rgb >> 16) & 255, g = (m.rgb >> 8) & 255, b = m.rgb & 255;
            // the core is paler for the bright ones
            int cr2 = r + Math.round((255 - r) * m.bright * .7F), cg2 = g + Math.round((255 - g) * m.bright * .7F), cb2 = b + Math.round((255 - b) * m.bright * .7F);
            int a = Math.clamp(Math.round((m.spark ? 1F : .55F + .45F * m.bright) * e * 255), 0, 255);
            if (m.spark) {
                Vec3 v = right.scale(m.vx).add(0, m.vy, 0).add(near.scale(m.vz));
                double sx = v.dot(cr), sy = v.dot(cu), sl = Math.hypot(sx, sy);
                if (sl < 1e-6) { sx = 0; sy = 1; sl = 1; }
                // along the velocity on the screen: the streak's axis, its side
                Vec3 ax = cr.scale(sx / sl).add(cu.scale(sy / sl)), sd = cr.scale(-sy / sl).add(cu.scale(sx / sl));
                double len = Math.min(.34, .06 + .06 * sl), w = m.size * SPARK_WIDTH;
                Vec3 head = p, tail = p.subtract(ax.scale(len));
                // two quads: left edge -> centre line -> right edge, alpha 0 on the edges and at the tail
                Vec3 hl = head.add(sd.scale(w)), hr = head.subtract(sd.scale(w)), tl = tail.add(sd.scale(w * .3)), tr = tail.subtract(sd.scale(w * .3));
                put(out, pose, hl, r, g, b, 0); put(out, pose, head, cr2, cg2, cb2, a); put(out, pose, tail, r, g, b, 0); put(out, pose, tl, r, g, b, 0);
                put(out, pose, head, cr2, cg2, cb2, a); put(out, pose, hr, r, g, b, 0); put(out, pose, tr, r, g, b, 0); put(out, pose, tail, r, g, b, 0);
                verts += 8;
            } else {
                double s = m.size * MOTE_RADIUS;
                Vec3 ex = cr.scale(s), ey = cu.scale(s);
                // a round light: eight triangles round the centre (the centre carries the pale colour and the alpha, the rim nothing)
                Vec3[] ring = new Vec3[9];
                for (int q = 0; q <= 8; q++) { double an = q * Math.PI / 4 + .39; ring[q] = p.add(ex.scale(Math.cos(an))).add(ey.scale(Math.sin(an))); }
                for (int q = 0; q < 8; q++) {
                    put(out, pose, p, cr2, cg2, cb2, a); put(out, pose, ring[q], r, g, b, 0); put(out, pose, ring[q + 1], r, g, b, 0); put(out, pose, ring[q + 1], r, g, b, 0);
                }
                verts += 32;
            }
        }
        c.buffers().endBatch(type);
        FxPerf.vertices(verts);
        FxPerf.draw();
        FxPerf.build(System.nanoTime() - t0);
    }

    /** Round 5: fine points and thin short sparks (author 15.11: the particles were too thick). */
    static final double MOTE_RADIUS = 1.0, SPARK_WIDTH = .7;

    static void put(VertexConsumer out, PoseStack.Pose pose, Vec3 p, int r, int g, int b, int a) {
        out.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a).setUv(0, 0).setUv1(0, 0).setUv2(0, 0).setNormal(0, 0, 1);
    }

    /** All the light meshes of the effect in ONE additive batch: the lights in the air and on the body, the light on the ground. */
    private static void glowMeshes(BtCtx c, Vec3 feet, Vec3 right, Vec3 near, double t, double cx, double cz, double k) {
        var type = MurimRenderTypes.presetLight();
        long t0 = System.nanoTime();
        VertexConsumer out = c.buffers().getBuffer(type);
        double[] cam = {c.camera().x, c.camera().y, c.camera().z};
        int verts = 0;
        for (var l : FxCultivationScene.lights(t)) {
            if (l.alpha() <= 0) continue;
            int r = (l.rgb() >> 16) & 255, g = (l.rgb() >> 8) & 255, b = l.rgb() & 255;
            float[][] mesh = FxCultivationScene.mesh(l, cx, cz, QUALITY.grid());
            for (float[] v : mesh) {
                Vec3 p = at(feet, right, near, new double[]{v[0], v[1], v[2]});
                put(out, c.pose(), p, r, g, b, Math.clamp(Math.round(v[3] * 255), 0, 255));
            }
            verts += mesh.length;
            Vec3 centre = at(feet, right, near, new double[]{l.x() * cz + l.z() * cx, l.y(), -l.x() * cx + l.z() * cz});
            double d = Math.max(.5, centre.distanceTo(c.camera())), ppb = k / d;
            FxPerf.light(Math.PI * l.halfW() * l.halfH() * ppb * ppb);
        }
        for (var gr : FxCultivationScene.GROUNDS) {
            float[][] mesh = FxCultivationScene.ground(gr, t, QUALITY.spokes(), QUALITY.rings());
            for (float[] v : mesh) {
                Vec3 p = at(feet, right, near, new double[]{v[0], v[1], v[2]});
                put(out, c.pose(), p, (gr.rgb() >> 16) & 255, (gr.rgb() >> 8) & 255, gr.rgb() & 255, Math.clamp(Math.round(v[3] * 255), 0, 255));
            }
            verts += mesh.length;
            double d = Math.max(.5, feet.distanceTo(c.camera())), ppb = k / d, sin = Math.abs(c.camera().y - feet.y) / d;
            FxPerf.light(Math.PI * gr.radius() * gr.radius() * ppb * ppb * sin);
        }
        c.buffers().endBatch(type);
        FxPerf.vertices(verts);
        FxPerf.draw();
        FxPerf.build(System.nanoTime() - t0);
    }

    static Vec3 at(Vec3 feet, Vec3 right, Vec3 near, double[] p) {
        return feet.add(right.scale(p[0])).add(0, p[1], 0).add(near.scale(p[2]));
    }

    static void sheet(BtCtx c, FxCultivationScene.Sheet s, double t, Vec3 feet, Vec3 right, Vec3 near, double cx, double cz, double k) {
        double growth = FxCultivationScene.growth(s, t);
        if (growth <= 0) return;
        long t0 = System.nanoTime();
        double[][][] e = FxCultivationScene.edges(s);
        int n = s.segments();
        Vec3[] top = new Vec3[n + 1], bottom = new Vec3[n + 1];
        for (int j = 0; j <= n; j++) { top[j] = at(feet, right, near, e[0][j]); bottom[j] = at(feet, right, near, e[1][j]); }
        int[] order = FxCultivationScene.order(s, cx, cz);
        // coverage estimate: the area each quad covers on the screen (world points, camera at the world origin of the pose is not used here)
        double[] cam = {c.camera().x, c.camera().y, c.camera().z};
        for (int j : order) {
            FxPerf.field(FxPerf.quadPixels(v(top[j]), v(top[j + 1]), v(bottom[j + 1]), v(bottom[j]), cam, k));
        }
        FxPerf.build(System.nanoTime() - t0);
        FxFlame.draw(s.look(), new FxEssence.Strip(top, bottom, order, s.halfWidth(), s.halfHeight(), s.seed(), growth, s.alpha()), c.pose(), c.buffers(), t);
    }

    private static double[] v(Vec3 p) { return new double[]{p.x, p.y, p.z}; }
}
