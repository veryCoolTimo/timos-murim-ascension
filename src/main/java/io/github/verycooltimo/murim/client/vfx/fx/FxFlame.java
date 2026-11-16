package io.github.verycooltimo.murim.client.vfx.fx;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.verycooltimo.murim.MurimMod;
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
 * The cheap flat flame field: sheets (strips of quads, as {@link FxEssence.Strip}) painted by fx_flame.fsh, a shader of three lookups into a
 * baked tiling texture ({@link FxFlameTexture}, uploaded once as a DynamicTexture) and no loops. API: DynamicTexture(int,int,boolean),
 * #getPixels, NativeImage#setPixelRGBA, #upload, TextureManager#register, RenderStateShard.TextureStateShard (binds unit 0, the shader's
 * Sampler0) — the same calls as BtFireField's profile texture.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class FxFlame {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "dynamic/flame_field");
    private static final String[] UNIFORMS = {"FlTime", "FlSize", "FlMap", "FlShape", "FlTone", "FlFan", "FlC0", "FlC1", "FlC2", "FlC3", "FlC4"};
    private static ShaderInstance program;
    private static boolean failed, uploaded;

    private static final RenderType MAIN = RenderType.create(MurimMod.MODID + ":fx_flame", DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS, 4096, RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(() -> program))
                    .setTextureState(new RenderStateShard.TextureStateShard(TEXTURE, true, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    private FxFlame() {}

    public static boolean available() { return program != null && !failed; }

    @SubscribeEvent
    static void shaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "fx_flame"), DefaultVertexFormat.NEW_ENTITY), loaded -> {
                for (String name : UNIFORMS) {
                    if (loaded.getUniform(name) == null) {
                        failed = true;
                        MurimMod.LOGGER.warn("Flame field disabled: fx_flame has no uniform {}", name);
                        return;
                    }
                }
                failed = false;
                program = loaded;
            });
        } catch (Exception error) {
            failed = true;
            MurimMod.LOGGER.warn("Flame field disabled: fx_flame did not load", error);
        }
    }

    /** Uploads the baked texture once (on the render thread, before the first draw). */
    private static void upload() {
        if (uploaded) return;
        DynamicTexture texture = new DynamicTexture(FxFlameTexture.SIZE, FxFlameTexture.SIZE, false);
        NativeImage image = texture.getPixels();
        int[] px = FxFlameTexture.pixels();
        for (int y = 0; y < FxFlameTexture.SIZE; y++) for (int x = 0; x < FxFlameTexture.SIZE; x++) image.setPixelRGBA(x, y, px[y * FxFlameTexture.SIZE + x]);
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
        uploaded = true;
    }

    /** Builds the texture ahead of the first draw (the bench's empty first second). */
    public static void warm() { if (available()) upload(); }

    /** Draws one sheet (all its quads in one batch, in the strip's order). */
    public static void draw(FxFlameLook l, FxEssence.Strip strip, PoseStack.Pose pose, MultiBufferSource.BufferSource buffers, double seconds) {
        if (!available() || strip.alpha() <= 0 || strip.growth() <= 0) return;
        upload();
        long t0 = System.nanoTime();
        program.getUniform("FlTime").set((float) seconds);
        program.getUniform("FlSize").set((float) strip.halfWidth(), (float) strip.halfHeight(), (float) strip.seed(), (float) strip.growth());
        program.getUniform("FlMap").set(l.tiles(), l.rise(), l.warp(), l.squash());
        program.getUniform("FlShape").set(l.low(), l.high(), l.soft(), l.bite());
        program.getUniform("FlTone").set(l.dark(), l.light(), l.pink(), l.blue());
        program.getUniform("FlFan").set(l.fan()[0], l.fan()[1], l.fan()[2], l.fan()[3]);
        for (int i = 0; i < 5; i++) {
            int rgb = l.colors()[i];
            program.getUniform("FlC" + i).set(((rgb >> 16) & 255) / 255F, ((rgb >> 8) & 255) / 255F, (rgb & 255) / 255F, l.alphas()[i]);
        }
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

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, Vec3 at, float u, float v, int alpha) {
        out.addVertex(pose, (float) at.x, (float) at.y, (float) at.z).setColor(255, 255, 255, alpha).setUv(u, v)
                .setUv1(0, 0).setUv2(0, 0).setNormal(0, 0, 1);
    }
}
