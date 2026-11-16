package io.github.verycooltimo.murim.client.vfx.fx;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.bt.BtGlow;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import java.util.List;

/**
 * Block A of the effect engine: brush strokes. A stroke is a path in the world; it is drawn as a ribbon that turns its width to the
 * camera, and fx_stroke.fsh paints the brush mark inside it (uneven thickness, ragged dry ends, colour bands, a dark contour,
 * companion strands, a drawing head, erosion). Light strokes are drawn a second time into the glow target (BtGlow: a real
 * screen-space blur, the stroke itself stays crisp).
 * API: RegisterShadersEvent#registerShader, ShaderInstance(ResourceProvider,ResourceLocation,VertexFormat), #getUniform, Uniform#set;
 * RenderType.create, RenderStateShard.ShaderStateShard(Supplier) — the same calls as TechniqueBodyLight / TechniqueLightRenderTypes.
 * Under an Iris shader pack mod core shaders are ignored (docs/03-vfx/06-shader-effects.md): {@link #available()} is false there and
 * callers keep their plain draw path.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class FxStroke {
    /**
     * One stroke: points along it (world space), the ribbon's half-width in blocks, and its state. {@code halves}, when not null, is
     * one mesh half-width per path point (a stroke traced from a picture swells and thins along itself); {@code half} then only
     * scales the brush noise (give the mean). Null keeps the one width of every earlier caller.
     */
    public record Stroke(List<Vec3> path, double half, double seed, double from, double drawn, double erode, float alpha, double[] halves) {
        /** A stroke of one width. */
        public Stroke(List<Vec3> path, double half, double seed, double from, double drawn, double erode, float alpha) {
            this(path, half, seed, from, drawn, erode, alpha, null);
        }

        /** A stroke that still starts at its first point. */
        public Stroke(List<Vec3> path, double half, double seed, double drawn, double erode, float alpha) {
            this(path, half, seed, 0, drawn, erode, alpha, null);
        }
    }

    private static final String[] UNIFORMS = {"FxTime", "FxCore", "FxBody", "FxRim", "FxInk", "FxShape", "FxFlow", "FxExtra", "FxMove", "FxSoft"};
    private static ShaderInstance program;
    private static boolean failed;

    private static final RenderType MAIN = RenderType.create(MurimMod.MODID + ":fx_stroke", DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS, 4096, RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(() -> program))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));
    private static RenderType glow;

    private FxStroke() {}

    public static boolean available() { return program != null && !failed; }

    @SubscribeEvent
    static void shaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "fx_stroke"), DefaultVertexFormat.NEW_ENTITY), loaded -> {
                for (String name : UNIFORMS) {
                    if (loaded.getUniform(name) == null) {
                        failed = true;
                        MurimMod.LOGGER.warn("Effect strokes disabled: fx_stroke has no uniform {}", name);
                        return;
                    }
                }
                failed = false;
                program = loaded;
            });
        } catch (Exception error) {
            failed = true;
            MurimMod.LOGGER.warn("Effect strokes disabled: fx_stroke did not load", error);
        }
    }

    /** Draws the strokes of one look and flushes them (the uniforms belong to this batch). */
    public static void draw(FxLook look, List<Stroke> strokes, Vec3 camera, PoseStack.Pose pose,
                            MultiBufferSource.BufferSource buffers, double seconds) {
        if (!available() || strokes.isEmpty()) return;
        uniforms(look, seconds);
        emit(strokes, camera, pose, buffers.getBuffer(MAIN), 1F);
        buffers.endBatch(MAIN);
        if (look.glow() > 0 && BtGlow.active()) {
            if (glow == null) glow = BtGlow.shaderTwin("fx_stroke", () -> program);
            // the glow target is blurred and added twice (BtGlow#composite): a full-strength twin turns the stroke into a white bar
            emit(strokes, camera, pose, buffers.getBuffer(glow), look.glow());
            buffers.endBatch(glow);
        }
    }

    private static void uniforms(FxLook l, double seconds) {
        double time = l.stepFps() > 0 ? Math.floor(seconds * l.stepFps()) / l.stepFps() : seconds;
        program.getUniform("FxTime").set((float) (time % 3600.0));
        set("FxCore", l.core(), l.coreStop());
        set("FxBody", l.body(), l.bodyStop());
        set("FxRim", l.rim(), l.rimStop());
        set("FxInk", l.ink(), l.inkAlpha());
        program.getUniform("FxShape").set(l.ragged(), l.dry(), l.strands(), l.wobble());
        program.getUniform("FxFlow").set(l.flow(), l.tail(), l.head(), l.mainShare());
        program.getUniform("FxMove").set(l.clots(), l.clotSpeed(), l.clotSpacing(), l.shimmer());
        program.getUniform("FxSoft").set(l.halo(), l.strandGap(), l.slide(), l.thorn());
        program.getUniform("FxExtra").set(l.contourBreak(), l.belly(), l.softBands(), l.boil());
    }

    private static void set(String name, int rgb, float a) {
        program.getUniform(name).set(((rgb >> 16) & 255) / 255F, ((rgb >> 8) & 255) / 255F, (rgb & 255) / 255F, a);
    }

    private static void emit(List<Stroke> strokes, Vec3 camera, PoseStack.Pose pose, VertexConsumer out, float gain) {
        for (Stroke s : strokes) {
            List<Vec3> p = s.path();
            int n = p.size();
            if (n < 2 || s.alpha() <= 0 || s.drawn() <= 0 || s.from() >= Math.min(s.drawn(), 1)) continue;
            double[] along = FxStrokeMath.lengths(p);
            double total = along[n - 1];
            if (total < 1e-6) continue;
            int seed = (int) Math.round(Math.clamp(s.seed(), 0, 1) * 10000), erode = (int) Math.round(Math.clamp(s.erode(), 0, 1) * 10000);
            int head = (int) Math.round(Math.clamp(s.drawn(), 0, 1.5) * 10000);
            int length = (int) Math.round(Math.clamp(total / s.half(), 0, 320) * 100);
            int alpha = Math.clamp(Math.round(s.alpha() * gain * 255), 0, 255);
            float tail = (float) Math.clamp(s.from(), 0, 1);
            Vec3 prevSide = null;
            Vec3[] left = new Vec3[n], right = new Vec3[n];
            for (int i = 0; i < n; i++) {
                Vec3 side = FxStrokeMath.side(p, i, camera, prevSide);
                prevSide = side;
                double w = s.halves() != null ? s.halves()[i] : s.half();
                left[i] = p.get(i).subtract(side.scale(w));
                right[i] = p.get(i).add(side.scale(w));
            }
            for (int i = 0; i < n - 1; i++) {
                float u0 = (float) (along[i] / total), u1 = (float) (along[i + 1] / total);
                if (u0 > s.drawn()) break;
                if (u1 < tail) continue;
                vertex(out, pose, left[i], u0, 0, alpha, seed, erode, head, length, tail);
                vertex(out, pose, right[i], u0, 1, alpha, seed, erode, head, length, tail);
                vertex(out, pose, right[i + 1], u1, 1, alpha, seed, erode, head, length, tail);
                vertex(out, pose, left[i + 1], u1, 0, alpha, seed, erode, head, length, tail);
            }
        }
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, Vec3 at, float u, float v, int alpha,
                               int seed, int erode, int head, int length, float tail) {
        out.addVertex(pose, (float) at.x, (float) at.y, (float) at.z).setColor(255, 255, 255, alpha).setUv(u, v)
                // the normal is free in this format: its x carries the stroke's tail, untransformed
                .setUv1(seed, erode).setUv2(head, length).setNormal(tail, 0, 0);
    }

    // ---------------------------------------------------------------- a whole 3D tree in one batch (FxTree3D)

    /** Scratch of the tree emitter: one side vector and one half-width per point (render thread only, grown when needed). */
    private static float[] sideX = new float[256], sideY = new float[256], sideZ = new float[256], halfW = new float[256];

    /**
     * Draws every branch of a 3D tree in ONE batch. Nothing is rebuilt: the branch paths are stored once (FxTree3D); a frame only works
     * out how far each branch is drawn and writes that part's quads straight from the stored float arrays (no objects are allocated).
     * The ribbon still turns its width to the camera (side = tangent x view, kept continuous along the branch). The tree is in the
     * body's frame; {@code feet}, {@code right}, {@code near} carry it into the world, {@code camera} is in the world.
     */
    public static void drawTree(FxLook look, FxTree3D tree, int family, float alpha, double t, Vec3 camera, Vec3 feet, Vec3 right, Vec3 near, PoseStack.Pose pose,
                                MultiBufferSource.BufferSource buffers) {
        if (!available()) return;
        long t0 = System.nanoTime();
        uniforms(look, t);
        TreeEmitter e = new TreeEmitter(tree, family, Math.clamp(Math.round(alpha * 255), 0, 255), buffers.getBuffer(MAIN), pose, camera, feet, right, near);
        tree.visit(t, e);
        buffers.endBatch(MAIN);
        FxPerf.draw();
        FxPerf.build(System.nanoTime() - t0);
    }

    /** Nothing of the aura is cut by the floor: the lines fade out softly over the lowest quarter block instead of meeting the ground. */
    static double floorFade(double y) {
        double k = Math.clamp((y - .03) / .27, 0, 1);
        return k * k * (3 - 2 * k);
    }

    private static final class TreeEmitter implements FxTree3D.Visitor {
        final FxTree3D tree;
        final int family, alpha;
        final VertexConsumer out;
        final PoseStack.Pose pose;
        final double cx, cy, cz;                 // the camera in the body's frame
        final double fx, fy, fz, rx, rz, nx, nz; // body frame -> world: p = feet + right*x + (0,y,0) + near*z

        TreeEmitter(FxTree3D tree, int family, int alpha, VertexConsumer out, PoseStack.Pose pose, Vec3 camera, Vec3 feet, Vec3 right, Vec3 near) {
            this.tree = tree; this.family = family; this.alpha = alpha; this.out = out; this.pose = pose;
            fx = feet.x; fy = feet.y; fz = feet.z; rx = right.x; rz = right.z; nx = near.x; nz = near.z;
            Vec3 d = camera.subtract(feet);
            cx = d.dot(right); cy = d.y; cz = d.dot(near);
        }

        @Override public void piece(FxTree3D.Branch b, double from, double drawn, double head, double tail, int gen) {
            if (b.family != family) return;
            int n = b.size();
            if (sideX.length < n) { sideX = new float[n]; sideY = new float[n]; sideZ = new float[n]; halfW = new float[n]; }
            float[] along = b.along;
            double total = b.length;
            // the last point that is needed: the segment starting at the last point not beyond the head is drawn
            int last = 0;
            while (last < n - 1 && along[last + 1] / total <= drawn) last++;
            last = Math.min(n - 1, last + 1);
            float[] p = tree.flow(b, b.p, last, gen);      // round 6: the stream carried outward
            // width direction at every needed point: across the path and across the line of sight, continuous along the branch
            double px = 0, py = 1, pz = 0;
            boolean hasPrev = false;
            for (int i = 0; i <= last; i++) {
                int a = Math.max(0, i - 1), c = Math.min(n - 1, i + 1);
                double tx = p[3 * c] - p[3 * a], ty = p[3 * c + 1] - p[3 * a + 1], tz = p[3 * c + 2] - p[3 * a + 2];
                double vx = cx - p[3 * i], vy = cy - p[3 * i + 1], vz = cz - p[3 * i + 2];
                double sx = ty * vz - tz * vy, sy = tz * vx - tx * vz, sz = tx * vy - ty * vx;
                double sl = sx * sx + sy * sy + sz * sz;
                if (sl < 1e-10 * (tx * tx + ty * ty + tz * tz) * (vx * vx + vy * vy + vz * vz) || sl < 1e-18) {
                    sx = px; sy = py; sz = pz;
                } else {
                    double k = 1 / Math.sqrt(sl);
                    sx *= k; sy *= k; sz *= k;
                    if (hasPrev && sx * px + sy * py + sz * pz < 0) { sx = -sx; sy = -sy; sz = -sz; }
                }
                px = sx; py = sy; pz = sz; hasPrev = true;
                double w = FxTree3D.meshHalf(b, i, head, tail) * FxTree3D.pulse(b, i, tree.now());
                sideX[i] = (float) (sx * w); sideY[i] = (float) (sy * w); sideZ[i] = (float) (sz * w);
            }
            int seed = (int) Math.round(Math.clamp(tree.seed(b, gen), 0, 1) * 10000);
            int headI = (int) Math.round(Math.clamp(drawn, 0, 1.5) * 10000);
            int length = (int) Math.round(Math.clamp(total / b.meanHalf, 0, 320) * 100);
            float tailF = (float) Math.clamp(from, 0, 1);
            int quads = 0;
            for (int i = 0; i < n - 1; i++) {
                float u0 = (float) (along[i] / total), u1 = (float) (along[i + 1] / total);
                if (u0 > drawn) break;
                if (u1 < from) continue;
                corner(p, i, -1, u0, 0, seed, headI, length, tailF);
                corner(p, i, 1, u0, 1, seed, headI, length, tailF);
                corner(p, i + 1, 1, u1, 1, seed, headI, length, tailF);
                corner(p, i + 1, -1, u1, 0, seed, headI, length, tailF);
                quads++;
            }
            FxPerf.stroke(quads);
        }

        private void corner(float[] p, int i, int side, float u, float v, int seed, int head, int length, float tail) {
            double x = p[3 * i] + side * sideX[i], y = p[3 * i + 1] + side * sideY[i], z = p[3 * i + 2] + side * sideZ[i];
            out.addVertex(pose, (float) (fx + rx * x + nx * z), (float) (fy + y), (float) (fz + rz * x + nz * z))
                    .setColor(255, 255, 255, (int) Math.round(alpha * floorFade(y))).setUv(u, v).setUv1(seed, 0).setUv2(head, length).setNormal(tail, 0, 0);
        }
    }
}
