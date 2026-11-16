package io.github.verycooltimo.murim.client.vfx.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.bt.BtCtx;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/**
 * Bench element «cult1»: the scene of reference panel 1 round the seated player (FxCult1Scene). Draw order: the far blue noise (FxFlame), the
 * cel dust, then ONE additive batch of lights, ground light, cloth and bolts, then the motes. Everything is geometry built on the CPU from
 * stored maths, no per-pixel work except the baked flame texture; the quality governor (FxQuality, fed with the frame time) lowers bolts,
 * cloth sheets, particles, light grids by itself. /murim dev vfx bench cult1.
 */
public final class FxCult1Bench {
    private FxCult1Bench() {}

    /** The cel dust of the aura, in the panel's cold light. */
    public static final FxDust.Look DUST = new FxDust.Look(0xDCF8FF, 0x84C8E8, 0x2E5E96, 0x0A1A30, .5F, .5F, .5F, 5, -.3F, .9F, .6F, .15F, .12F);

    private static final FxQuality QUALITY = new FxQuality();
    private static final FxCultivationMotes MOTES = FxCult1Scene.newMotes();
    private static final FxCult1Scene.Mesh MESH = new FxCult1Scene.Mesh();
    private static double lastT = -1;
    private static int lastTier = -1;

    static {
        try { QUALITY.force(Integer.parseInt(System.getProperty("murim.fx.tier", "-1"))); } catch (NumberFormatException ignored) { }
    }

    public static void render(BtCtx c) {
        double t = c.labSeconds();
        double dt = lastT < 0 || t < lastT ? 0 : t - lastT;
        lastT = t;
        QUALITY.update(FxPerf.lastFrameMs(), dt);
        if (QUALITY.tier() != lastTier) { io.github.verycooltimo.murim.MurimMod.LOGGER.info("Cult1 quality tier {} (frame {} ms)", QUALITY.tier(), Math.round(QUALITY.smoothedMs() * 10) / 10.0); lastTier = QUALITY.tier(); }
        if (t < FxCult1Scene.START) return;
        MOTES.step(dt, FxCultivationScene.glow(t), QUALITY.particleScale());
        draw(c, t);
    }

    private static void draw(BtCtx c, double t) {
        var body = c.body();
        Vec3 feet = new Vec3(body.axis().x, body.ground(), body.axis().z), right = body.right(), near = body.near();
        Vec3 toCam = c.camera().subtract(feet);
        double cx = toCam.dot(right), cz = toCam.dot(near), len = Math.max(1e-6, Math.hypot(cx, cz));
        cx /= len; cz /= len;
        var mc = c.mc();
        double k = mc.getWindow().getHeight() / (2 * Math.tan(Math.toRadians(mc.options.fov().get()) / 2));
        mesh(c, feet, right, near, t, cx, cz, k);
        FxCultivationBench.motes(c, MOTES, feet, right, near);
    }

    private static void dust(BtCtx c, Vec3 feet, Vec3 right, Vec3 near, double t) {
        if (!FxDust.available()) return;
        var camera = c.mc().gameRenderer.getMainCamera();
        var left = camera.getLeftVector();
        Vec3 camRight = new Vec3(-left.x(), -left.y(), -left.z()), camUp = new Vec3(camera.getUpVector());
        long t0 = System.nanoTime();
        var puffs = FxCultivationDust.at(t, FxCultivationScene.glow(t), QUALITY.particleScale());
        FxDust.draw(DUST, puffs, new FxDust.Frame(feet, right, near, camRight, camUp), c.pose(), c.buffers(), t - FxCult1Scene.START);
        FxPerf.draw();
        FxPerf.build(System.nanoTime() - t0);
    }

    private static void mesh(BtCtx c, Vec3 feet, Vec3 right, Vec3 near, double t, double cx, double cz, double k) {
        var type = MurimRenderTypes.presetLight();
        long t0 = System.nanoTime();
        VertexConsumer out = c.buffers().getBuffer(type);
        int verts = 0;
        for (var l : FxCult1Scene.lights(t)) {
            if (l.alpha() <= 0) continue;
            int r = (l.rgb() >> 16) & 255, g = (l.rgb() >> 8) & 255, b = l.rgb() & 255;
            for (float[] v : FxCultivationScene.mesh(l, cx, cz, QUALITY.grid()))
                FxCultivationBench.put(out, c.pose(), FxCultivationBench.at(feet, right, near, new double[]{v[0], v[1], v[2]}), r, g, b, Math.clamp(Math.round(v[3] * 255), 0, 255));
            verts += QUALITY.grid() * QUALITY.grid() * 4;
            Vec3 centre = FxCultivationBench.at(feet, right, near, new double[]{l.x() * cz + l.z() * cx, l.y(), -l.x() * cx + l.z() * cz});
            double d = Math.max(.5, centre.distanceTo(c.camera())), ppb = k / d;
            FxPerf.light(Math.PI * l.halfW() * l.halfH() * ppb * ppb);
        }
        for (var gr : FxCult1Scene.GROUNDS) {
            for (float[] v : FxCult1Scene.ground(gr, t, QUALITY.spokes(), QUALITY.rings()))
                FxCultivationBench.put(out, c.pose(), FxCultivationBench.at(feet, right, near, new double[]{v[0], v[1], v[2]}), (gr.rgb() >> 16) & 255, (gr.rgb() >> 8) & 255, gr.rgb() & 255, Math.clamp(Math.round(v[3] * 255), 0, 255));
            verts += QUALITY.spokes() * QUALITY.rings() * 4;
            double d = Math.max(.5, feet.distanceTo(c.camera())), ppb = k / d, sin = Math.abs(c.camera().y - feet.y) / d;
            FxPerf.light(Math.PI * gr.radius() * gr.radius() * ppb * ppb * sin);
        }
        // cloth and bolts: one mesh, rebuilt every frame (a few thousand vertices)
        MESH.clear();
        double share = QUALITY.particleScale();
        FxCult1Scene.cloths(t, share, new double[]{cx, 0, cz}, MESH);
        FxCult1Scene.bolts(t, new double[]{cx, 0, cz}, share, MESH);
        for (int i = 0; i < MESH.n; i++) {
            int o = i * FxCult1Scene.Mesh.STRIDE;
            float[] v = MESH.v;
            FxCultivationBench.put(out, c.pose(), FxCultivationBench.at(feet, right, near, new double[]{v[o], v[o + 1], v[o + 2]}), (int) v[o + 3], (int) v[o + 4], (int) v[o + 5], Math.clamp(Math.round(v[o + 6] * 255), 0, 255));
        }
        verts += MESH.n;
        c.buffers().endBatch(type);
        FxPerf.vertices(verts);
        FxPerf.draw();
        FxPerf.build(System.nanoTime() - t0);
    }
}
