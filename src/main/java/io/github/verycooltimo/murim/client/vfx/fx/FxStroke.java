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
    /** One stroke: points along it (world space), the ribbon's half-width in blocks, and its state. */
    public record Stroke(List<Vec3> path, double half, double seed, double from, double drawn, double erode, float alpha) {
        /** A stroke that still starts at its first point. */
        public Stroke(List<Vec3> path, double half, double seed, double drawn, double erode, float alpha) {
            this(path, half, seed, 0, drawn, erode, alpha);
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
        program.getUniform("FxSoft").set(l.halo(), 0F, 0F, 0F);
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
                left[i] = p.get(i).subtract(side.scale(s.half()));
                right[i] = p.get(i).add(side.scale(s.half()));
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
}
