package io.github.verycooltimo.murim.client.flow;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.fx.FxDust;
import io.github.verycooltimo.murim.client.vfx.fx.FxDustSim;
import io.github.verycooltimo.murim.client.vfx.fx.FxTune;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

/**
 * THE FIRE of the high pill game: big layered sheets of flame 2-3 body heights tall behind and beside the seated figure (refs pill-sequence 3, 3.5, 4;
 * «cultivating it»), with small dark flakes rising in them (the author's «black drops»). Each sheet is a curved strip of quads that fx_fire.fsh paints:
 * a soft body in the pill tone, a darker deep-tone band along every torn edge, dark patches, hot streaks low down. Everything is a function of the clock
 * (no integrated state), so a stand can replay it. Colour comes from the pill ({@code Params.pill}), never a fixed hue. All numbers are
 * {@code FxTune.f("flow.fire.<key>", default)}.
 *
 * <p>Entry point: {@link #draw(FlowSpaceFx.Params, PoseStack.Pose, Vec3, MultiBufferSource.BufferSource, int)} once a frame in the scene's pass, after the
 * vortex floor light; the director hands its {@code Params} (clock, pressure01, pill, centre, floorY, fade). In {@code FlowSpaceFx.draw} it replaces
 * {@code drawSheets}. Sheets that stand between the camera and the figure are kept low so nothing covers the face.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
final class FlowFireSheets {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "dynamic/fire_noise");
    private static final String[] UNIFORMS = {"FiTime", "FiSize", "FiMap", "FiShape", "FiHeat", "FiC0", "FiC1", "FiC2"};
    private static ShaderInstance program;
    private static boolean failed, uploaded;

    private static final RenderType MAIN = RenderType.create(MurimMod.MODID + ":fx_fire", DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS, 4096, RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(() -> program))
                    .setTextureState(new RenderStateShard.TextureStateShard(TEXTURE, true, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    /** Near-black chips with a faint deep-red rim (so they read on a dark scene). */
    private static final FxDust.Look CHIP = new FxDust.Look(0x5A1A1E, 0x240B0E, 0x0B0405, 0x050203, 1F, .25F, .6F, 5, -.5F, .85F, .2F, .3F, .3F);

    /** One sheet: azimuth from straight behind the figure (degrees, positive to the screen right), arc span, radius, height factor, lean, drift speed, seed. */
    private record Sheet(double phi, double span, double radius, double height, double lean, double drift, double seed) { }

    private static final Sheet[] SHEETS = {
        new Sheet(-8, 70, 1.55, 1.0, .35, 1.2, 1.3),
        new Sheet(32, 62, 1.9, 1.15, .30, -1.0, 4.1),
        new Sheet(-48, 60, 1.7, .95, .40, .9, 7.7),
        new Sheet(78, 56, 1.6, .85, .25, -1.4, 2.9),
        new Sheet(-86, 52, 1.5, .8, .45, 1.1, 9.3),
        new Sheet(112, 50, 1.45, .6, .2, .8, 5.5),
        new Sheet(-118, 48, 1.4, .55, .3, -.7, 11.1),
        new Sheet(150, 60, 1.25, .2, .2, .6, 13.7),
        new Sheet(-160, 56, 1.3, .17, .25, -.5, 15.9),
    };

    private FlowFireSheets() { }

    @SubscribeEvent
    static void shaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "fx_fire"), DefaultVertexFormat.NEW_ENTITY), loaded -> {
                for (String name : UNIFORMS) {
                    if (loaded.getUniform(name) == null) {
                        failed = true;
                        MurimMod.LOGGER.warn("Fire sheets disabled: fx_fire has no uniform {}", name);
                        return;
                    }
                }
                failed = false;
                program = loaded;
            });
        } catch (Exception error) {
            failed = true;
            MurimMod.LOGGER.warn("Fire sheets disabled: fx_fire did not load", error);
        }
    }

    static boolean available() { return program != null && !failed; }

    // ------------------------------------------------------------------ the baked noise (tiles): R low, G mid, B sharp, A patches

    private static final int SIZE = 256;

    private static double hash(int x, int y, int seed) {
        long h = x * 374761393L + y * 668265263L + seed * 2147483647L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    private static int wrap(int i, int n) { return ((i % n) + n) % n; }

    private static double noise(double u, double v, int freq, int seed) {
        double gx = u * freq, gy = v * freq;
        int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy);
        double fx = gx - x0, fy = gy - y0;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        double a = hash(wrap(x0, freq), wrap(y0, freq), seed), b = hash(wrap(x0 + 1, freq), wrap(y0, freq), seed);
        double c = hash(wrap(x0, freq), wrap(y0 + 1, freq), seed), d = hash(wrap(x0 + 1, freq), wrap(y0 + 1, freq), seed);
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy;
    }

    private static double fbm(double u, double v, int freq, int seed) {
        return .55 * noise(u, v, freq, seed) + .3 * noise(u, v, freq * 2, seed + 1) + .15 * noise(u, v, freq * 4, seed + 2);
    }

    private static void upload() {
        if (uploaded) return;
        DynamicTexture texture = new DynamicTexture(SIZE, SIZE, false);
        NativeImage image = texture.getPixels();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double u = x / (double) SIZE, v = y / (double) SIZE;
                double r = fbm(u, v, 3, 11), g = fbm(u, v, 6, 23), bb = fbm(u, v, 12, 37), aa = fbm(u, v, 4, 51);
                int[] ch = {clamp(r), clamp(g), clamp(bb), clamp(aa)};
                image.setPixelRGBA(x, y, ch[3] << 24 | ch[2] << 16 | ch[1] << 8 | ch[0]);
            }
        }
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
        uploaded = true;
    }

    private static int clamp(double v) { return Math.max(0, Math.min(255, (int) Math.round(v * 255))); }

    // ------------------------------------------------------------------ colour from the pill

    private static float[] mix(float[] a, float[] b, double k) {
        return new float[] {(float) (a[0] + (b[0] - a[0]) * k), (float) (a[1] + (b[1] - a[1]) * k), (float) (a[2] + (b[2] - a[2]) * k)};
    }

    private static double smooth(double e0, double e1, double x) { return FlowSpaceMath.smooth(e0, e1, x); }

    private static double hash1(double x) { double v = Math.sin(x * 127.1 + 311.7) * 43758.5453; return v - Math.floor(v); }

    // ------------------------------------------------------------------ draw

    static void draw(FlowSpaceFx.Params p, PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers, int tier) {
        if (!available() || p == null) return;
        int gr = FlowSpaceFx.grade();
        double env = gr >= 0 ? smooth(.3, 2.4, p.clock() - FlowLook.INTRO_LEAD) * p.fade() : (p.ending() < 0 ? smooth(.3, 2.4, p.clock() - FlowLook.INTRO_LEAD) : p.fade());
        if (env < .01) return;
        upload();
        double p01 = p.pressure01();
        float[] pillTone = {p.pill()[0], p.pill()[1], p.pill()[2]};
        float[] c = FlowSpaceFx.lampColour((Math.round(pillTone[0] * 255) << 16) | (Math.round(pillTone[1] * 255) << 8) | Math.round(pillTone[2] * 255), p01);
        float deepK = FxTune.f("flow.fire.deep", .28F), bodyK = FxTune.f("flow.fire.body", .66F);
        float[] deep = {c[0] * c[0] * deepK, c[1] * c[1] * deepK * .8F, c[2] * c[2] * deepK * .8F};
        float[] body = {c[0] * bodyK, c[1] * bodyK * .9F, c[2] * bodyK * .9F};
        float[] hot = mix(c, new float[] {1, 1, 1}, FxTune.f("flow.fire.hot_white", .3F));
        double hMul = (gr >= 0 ? FlowLook.CALM_FIRE_H[gr] : FxTune.d("flow.fire.height", 3.0)) * (.72 + .5 * p01);
        double rs = gr >= 0 ? FlowLook.CALM_FIRE_R[gr] : 1;
        double cx = cam.x - p.centre().x, cz = cam.z - p.centre().z, cl = Math.max(1e-6, Math.hypot(cx, cz));
        double ax = -cx / cl, az = -cz / cl;               // from the camera through the figure (horizontal)
        double rx = -az, rz = ax;                          // to the screen right
        double clock = p.clock();
        int cols = tier >= 2 ? 16 : 26;
        int n = Math.min(SHEETS.length, gr >= 0 ? FlowLook.CALM_FIRE_SHEETS[gr] : FxTune.i("flow.fire.sheets", 9));
        List<double[]> order = new ArrayList<>();          // {index, distance to the camera}
        for (int k = 0; k < n; k++) {
            Sheet s = sc(k, rs);
            double phi = Math.toRadians(s.phi() + s.drift() * Math.sin(clock * .11 + s.seed()) * 6);
            double mx = p.centre().x + (ax * Math.cos(phi) + rx * Math.sin(phi)) * s.radius();
            double mz = p.centre().z + (az * Math.cos(phi) + rz * Math.sin(phi)) * s.radius();
            order.add(new double[] {k, Math.hypot(mx - cam.x, mz - cam.z)});
        }
        order.sort((a, b) -> Double.compare(b[1], a[1]));
        for (double[] o : order) {
            Sheet s = sc((int) o[0], rs);
            double phi0 = s.phi() + s.drift() * Math.sin(clock * .11 + s.seed()) * 6;
            // in front of the face (towards the camera) the sheet stays low
            double towardCam = Math.max(0, -Math.cos(Math.toRadians(phi0)));
            double h = s.height() * hMul * (1 - FxTune.d("flow.fire.front_low", .65) * smooth(.15, .7, towardCam));
            double grow = env * smooth(0, 1.6, clock - .15 * o[0]);
            if (grow < .01) continue;
            drawSheet(p, pose, cam, buffers, s, phi0, h * (.35 + .65 * grow), grow, cols, body, deep, hot, ax, az, rx, rz, p01, clock);
        }
        if (gr < 0) drawFlakes(p, pose, cam, buffers, env, ax, az, rx, rz, clock);
    }

    private static Sheet sc(int k, double rs) {
        Sheet s = SHEETS[k];
        return rs == 1 ? s : new Sheet(s.phi(), s.span(), s.radius() * rs, s.height(), s.lean(), s.drift(), s.seed());
    }

    private static void drawSheet(FlowSpaceFx.Params p, PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers, Sheet s, double phiDeg, double h,
                                  double grow, int cols, float[] body, float[] deep, float[] hot, double ax, double az, double rx, double rz, double p01, double clock) {
        double span = Math.toRadians(s.span());
        double phiC = Math.toRadians(phiDeg);
        Vec3[] top = new Vec3[cols + 1], bottom = new Vec3[cols + 1];
        double floorY = p.floorY() + .02;
        // A curved panel, mostly facing the camera: its line runs along a blend of the camera's right axis (75 %) and the tangent of the ring (25 %), so a sheet
        // beside the figure is never seen edge-on (a thin strip); the ends curl in towards the figure.
        double rad = s.radius() * FxTune.d("flow.fire.radius_mul", .75);
        double cxm = p.centre().x + (ax * Math.cos(phiC) + rx * Math.sin(phiC)) * rad, czm = p.centre().z + (az * Math.cos(phiC) + rz * Math.sin(phiC)) * rad;
        double tx = -(ax * Math.sin(phiC)) + rx * Math.cos(phiC), tz = -(az * Math.sin(phiC)) + rz * Math.cos(phiC);
        double face = FxTune.d("flow.fire.face", .75);
        double lx = rx * face + tx * (1 - face), lz = rz * face + tz * (1 - face), ll = Math.max(1e-6, Math.hypot(lx, lz));
        lx /= ll; lz /= ll;
        double inx = p.centre().x - cxm, inz = p.centre().z - czm, il = Math.max(1e-6, Math.hypot(inx, inz));
        inx /= il; inz /= il;
        double half = rad * span * .5 * FxTune.d("flow.fire.width_mul", 1.15), curl = FxTune.d("flow.fire.curl", .45);
        for (int j = 0; j <= cols; j++) {
            double x = j / (double) cols, sx = (x - .5) * 2;
            double bx = cxm + lx * sx * half + inx * curl * sx * sx, bz = czm + lz * sx * half + inz * curl * sx * sx;
            bx += lx * .03 * Math.sin(clock * .3 + s.seed() + j * .5);
            double lean = s.lean() * h;
            double hj = h * (1 + .1 * Math.sin(x * 7 + s.seed()));
            bottom[j] = new Vec3(bx, floorY, bz);
            top[j] = new Vec3(bx + rx * lean * FxTune.d("flow.fire.lean_geo", .6), floorY + hj, bz + rz * lean * FxTune.d("flow.fire.lean_geo", .6));
        }
        int[] ord = new int[cols];
        Integer[] idx = new Integer[cols];
        for (int j = 0; j < cols; j++) idx[j] = j;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(bottom[b].distanceToSqr(cam), bottom[a].distanceToSqr(cam)));
        for (int j = 0; j < cols; j++) ord[j] = idx[j];
        double arc = 2 * half;
        program.getUniform("FiTime").set((float) clock);
        program.getUniform("FiSize").set((float) (arc * .5), (float) (h * .5), (float) s.seed(), (float) grow);
        program.getUniform("FiMap").set(FxTune.f("flow.fire.tiles_x", .38F), FxTune.f("flow.fire.tiles_y", .13F), FxTune.f("flow.fire.rise", .4F), (float) (s.lean() * FxTune.d("flow.fire.shear", 2.4)));
        program.getUniform("FiShape").set(FxTune.f("flow.fire.contrast", 2.1F), FxTune.f("flow.fire.top_thr", .26F), FxTune.f("flow.fire.soft", .32F), FxTune.f("flow.fire.band", .14F));
        program.getUniform("FiHeat").set((float) p01, FxTune.f("flow.fire.hot_amount", 1F), (float) (FxTune.f("flow.fire.dark_patch", 1.0F) * (s.height() < .4 ? 1.7 : 1)), FxTune.f("flow.fire.side", .1F));
        program.getUniform("FiC0").set(deep[0], deep[1], deep[2], FxTune.f("flow.fire.band_alpha", .9F));
        program.getUniform("FiC1").set(body[0], body[1], body[2], FxTune.f("flow.fire.body_alpha", .85F));
        program.getUniform("FiC2").set(hot[0], hot[1], hot[2], 1F);
        VertexConsumer out = buffers.getBuffer(MAIN);
        int alpha = Math.clamp(Math.round(FxTune.f("flow.fire.alpha", 1F) * 255), 0, 255);
        for (int j : ord) {
            float u0 = j / (float) cols, u1 = (j + 1) / (float) cols;
            vertex(out, pose, top[j], u0, 0, alpha);
            vertex(out, pose, top[j + 1], u1, 0, alpha);
            vertex(out, pose, bottom[j + 1], u1, 1, alpha);
            vertex(out, pose, bottom[j], u0, 1, alpha);
        }
        buffers.endBatch(MAIN);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, Vec3 at, float u, float v, int alpha) {
        out.addVertex(pose, (float) at.x, (float) at.y, (float) at.z).setColor(255, 255, 255, alpha).setUv(u, v).setUv1(0, 0).setUv2(0, 0).setNormal(0, 0, 1);
    }

    // ------------------------------------------------------------------ the dark flakes rising in the fire

    private static void drawFlakes(FlowSpaceFx.Params p, PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers, double env,
                                   double ax, double az, double rx, double rz, double clock) {
        if (!FxDust.available()) return;
        int n = FxTune.i("flow.fire.flakes", 42);
        double sMin = FxTune.d("flow.fire.flake_min", .04), sMax = FxTune.d("flow.fire.flake_max", .1), rise = FxTune.d("flow.fire.flake_rise", .5);
        double top = FxTune.d("flow.fire.flake_top", 3.2);
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        var left = camera.getLeftVector();
        Vec3 cr = new Vec3(-left.x(), -left.y(), -left.z()), cu = new Vec3(camera.getUpVector());
        FxDust.Frame fr = new FxDust.Frame(new Vec3(p.centre().x, p.floorY(), p.centre().z), new Vec3(1, 0, 0), new Vec3(0, 0, 1), cr, cu);
        List<FxDustSim.Puff> puffs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double h1 = hash1(i * 1.37), h2 = hash1(i * 2.71), h3 = hash1(i * 3.13), h4 = hash1(i * 4.19), h5 = hash1(i * 5.23);
            double life = top / (rise * (.75 + .5 * h1)), u = (clock / life + h2) % 1;
            // behind and beside the figure (never between the camera and the face): an arc of 280 degrees round the back
            double phi = Math.toRadians((h3 - .5) * 280), rad = .45 + 1.7 * h4;
            double y = .1 + u * top, drift = .25 * Math.sin(clock * .4 + i) + .5 * u;
            double dx = ax * Math.cos(phi) + rx * Math.sin(phi), dz = az * Math.cos(phi) + rz * Math.sin(phi);
            double x = dx * rad + rx * drift * .6, z = dz * rad + rz * drift * .6;
            double al = env * .95 * smooth(0, .12, u) * (1 - smooth(.8, 1, u));
            if (al < .03) continue;
            double size = sMin + (sMax - sMin) * h5 * h5;
            // long axis along the climb, leaning to the screen right
            puffs.add(new FxDustSim.Puff(x, y, z, rx * .5, 1, rz * .5, size, h2, Math.clamp((u - .6) / .4, 0, 1), h1 * 6.28, FxDustSim.DROP, 1.0 + .5 * h4, 0, al, 1, Double.NaN, 1));
        }
        FxDust.draw(CHIP, puffs, fr, pose, buffers, clock);
    }
}
