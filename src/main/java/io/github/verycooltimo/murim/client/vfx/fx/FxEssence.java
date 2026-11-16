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

/**
 * The flat flame field of the author's panels 13 and 14 («essence»): patches of flat violet-blue tone between drifting dark discs,
 * painted by fx_essence.fsh on a camera-facing quad. The quad only carries the field; the shapes come from moving noise, so nothing
 * here is a picture. A quiet region closes (its discs grow until they touch) instead of fading, and the quad's border closes the
 * same way, so no patch is ever cut by the mesh.
 * API: RegisterShadersEvent#registerShader, ShaderInstance(ResourceProvider,ResourceLocation,VertexFormat), #getUniform, Uniform#set;
 * RenderType.create, RenderStateShard.ShaderStateShard(Supplier) — the same calls as {@link FxStroke}. Under an Iris shader pack mod
 * core shaders are ignored: {@link #available()} is false there and callers keep their plain draw path.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class FxEssence {
    /** One quad of the field: the centre, the camera-facing axes (unit), half sizes in blocks, a seed, growth 0..1, an alpha and a place (centre and half axes in blocks, in the quad's plane) that always stays closed: the head and body of the figure. */
    public record Quad(Vec3 centre, Vec3 right, Vec3 up, double halfWidth, double halfHeight, double seed, double growth, float alpha,
                       double clearX, double clearY, double clearHalfW, double clearHalfH) {}

    /**
     * A curved sheet of the field: a strip of quads (a partial cone round the body's axis), the world points of its top edge and bottom
     * edge, one pair per column, listed in drawing order. The pattern is laid out flat over the whole strip (u across its length, v
     * down), so it runs on round the curve; {@code halfWidth} is the strip's length / 2 and {@code halfHeight} its height / 2, in blocks.
     */
    public record Strip(Vec3[] top, Vec3[] bottom, int[] order, double halfWidth, double halfHeight, double seed, double growth, float alpha) {}

    private static final String[] UNIFORMS = {"EsTime", "EsSize", "EsField", "EsFlow", "EsMask", "EsTone", "EsRim",
            "EsShape", "EsTongue", "EsWisp", "EsThorn", "EsFan", "EsLod", "EsClear", "EsC0", "EsC1", "EsC2", "EsC3", "EsC4"};
    private static ShaderInstance program;
    private static boolean failed;

    private static final RenderType MAIN = RenderType.create(MurimMod.MODID + ":fx_essence", DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS, 4096, RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(() -> program))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));
    private static RenderType glow;

    private FxEssence() {}

    public static boolean available() { return program != null && !failed; }

    @SubscribeEvent
    static void shaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "fx_essence"), DefaultVertexFormat.NEW_ENTITY), loaded -> {
                for (String name : UNIFORMS) {
                    if (loaded.getUniform(name) == null) {
                        failed = true;
                        MurimMod.LOGGER.warn("Essence field disabled: fx_essence has no uniform {}", name);
                        return;
                    }
                }
                failed = false;
                program = loaded;
            });
        } catch (Exception error) {
            failed = true;
            MurimMod.LOGGER.warn("Essence field disabled: fx_essence did not load", error);
        }
    }

    /** Draws one quad of one look and flushes it (the uniforms belong to this batch). */
    public static void draw(FxEssenceLook look, Quad quad, PoseStack.Pose pose, MultiBufferSource.BufferSource buffers, double seconds) {
        if (!available() || quad.alpha() <= 0 || quad.growth() <= 0) return;
        uniforms(look, quad, seconds);
        emit(quad, pose, buffers.getBuffer(MAIN), 1F);
        buffers.endBatch(MAIN);
        FxPerf.draw();
        if (look.glow() > 0 && BtGlow.active()) {
            if (glow == null) glow = BtGlow.shaderTwin("fx_essence", () -> program);
            // the twin adds the same field into the glow target, which is blurred: the soft violet light round the patches
            emit(quad, pose, buffers.getBuffer(glow), look.glow());
            buffers.endBatch(glow);
        }
    }

    /** Draws one sheet (all its quads in one batch, in the strip's order) of one look. {@code pixels} is the summed screen coverage estimate the caller worked out. */
    public static void draw(FxEssenceLook look, Strip strip, PoseStack.Pose pose, MultiBufferSource.BufferSource buffers, double seconds) {
        if (!available() || strip.alpha() <= 0 || strip.growth() <= 0) return;
        long t0 = System.nanoTime();
        uniforms(look, strip.halfWidth(), strip.halfHeight(), strip.seed(), strip.growth(), 0, 0, 0, 0, seconds);
        VertexConsumer out = buffers.getBuffer(MAIN);
        int columns = strip.top().length - 1, alpha = Math.clamp(Math.round(strip.alpha() * 255), 0, 255);
        for (int j : strip.order()) {
            float u0 = j / (float) columns, u1 = (j + 1) / (float) columns;
            vertex(out, pose, strip.top()[j], u0, 0, alpha);
            vertex(out, pose, strip.top()[j + 1], u1, 0, alpha);
            vertex(out, pose, strip.bottom()[j + 1], u1, 1, alpha);
            vertex(out, pose, strip.bottom()[j], u0, 1, alpha);
        }
        FxPerf.vertices(4 * strip.order().length);
        buffers.endBatch(MAIN);
        FxPerf.draw();
        FxPerf.build(System.nanoTime() - t0);
    }

    private static void uniforms(FxEssenceLook l, Quad q, double seconds) {
        uniforms(l, q.halfWidth(), q.halfHeight(), q.seed(), q.growth(), q.clearX(), q.clearY(), q.clearHalfW(), q.clearHalfH(), seconds);
    }

    private static void uniforms(FxEssenceLook l, double halfW, double halfH, double seed, double growth, double clearX, double clearY, double clearW, double clearH, double seconds) {
        program.getUniform("EsTime").set((float) seconds);
        program.getUniform("EsSize").set((float) halfW, (float) halfH, (float) seed, (float) growth);
        program.getUniform("EsField").set(l.cell(), l.radius(), l.angular(), l.stretch());
        program.getUniform("EsFlow").set(l.rise(), l.flare(), l.drift(), l.warp());
        program.getUniform("EsMask").set(l.maskScale(), l.open(), l.bottom(), l.border());
        program.getUniform("EsTone").set(l.toneScale(), l.shadow(), l.light(), l.pink());
        program.getUniform("EsRim").set(0F, l.jagFreq(), l.bubbles(), l.elong() - 1F);
        program.getUniform("EsShape").set(l.top(), l.edge(), l.spread(), l.jag());
        program.getUniform("EsTongue").set(l.tongueX1(), l.tongueReach1(), l.tongueX2(), l.tongueReach2());
        program.getUniform("EsWisp").set(l.bend(), l.lean(), l.dropLength(), l.blueSide());
        program.getUniform("EsThorn").set(l.thorn(), l.thornLength(), l.thornWidth(), l.thornCurl());
        float[] fan = l.fan();
        program.getUniform("EsFan").set(fan[0], fan[1], fan[2], fan[3]);
        program.getUniform("EsLod").set(l.lod(), 0F, 0F, 0F);
        program.getUniform("EsClear").set((float) clearX, (float) clearY, (float) clearW, (float) clearH);
        for (int i = 0; i < 5; i++) {
            int rgb = l.colors()[i];
            program.getUniform("EsC" + i).set(((rgb >> 16) & 255) / 255F, ((rgb >> 8) & 255) / 255F, (rgb & 255) / 255F, l.alphas()[i]);
        }
    }

    private static void emit(Quad q, PoseStack.Pose pose, VertexConsumer out, float gain) {
        int alpha = Math.clamp(Math.round(q.alpha() * gain * 255), 0, 255);
        Vec3 r = q.right().scale(q.halfWidth()), u = q.up().scale(q.halfHeight());
        vertex(out, pose, q.centre().subtract(r).add(u), 0, 0, alpha);
        vertex(out, pose, q.centre().add(r).add(u), 1, 0, alpha);
        vertex(out, pose, q.centre().add(r).subtract(u), 1, 1, alpha);
        vertex(out, pose, q.centre().subtract(r).subtract(u), 0, 1, alpha);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, Vec3 at, float u, float v, int alpha) {
        out.addVertex(pose, (float) at.x, (float) at.y, (float) at.z).setColor(255, 255, 255, alpha).setUv(u, v)
                .setUv1(0, 0).setUv2(0, 0).setNormal(0, 0, 1);
    }
}
