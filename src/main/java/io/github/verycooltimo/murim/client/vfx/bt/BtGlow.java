package io.github.verycooltimo.murim.client.vfx.bt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * The soft glow round the Peak's light (author 31.10: «пик выглядит лучше, но не хватает глоу»). In the lab every band glows: its
 * light spills round it onto the dark. Here the ribbons and the halo are drawn a second time, additively, into a half-size target that
 * shares the frame's depth (so the body still hides what is behind it); after the scene that target is blurred twice — a tight and a
 * wide pass — and each blur is added over the frame. The ribbons themselves are untouched (approved look).
 * API: TextureTarget(w,h,useDepth,onOsx), RenderTarget#copyDepthFrom (glBlitFramebuffer, scales with GL_NEAREST), #bindWrite(true)
 * sets the viewport, #setFilterMode; RenderStateShard.OutputStateShard / TransparencyStateShard public constructors; EffectInstance
 * with the vanilla post programs minecraft:box_blur (blur.vsh: ProjMat, InSize, OutSize, BlurDir; box_blur.fsh: Radius,
 * RadiusMultiplier) and minecraft:blit (ProjMat, OutSize, ColorModulate) — reference/minecraft-src and the 1.21.1 client resources.
 */
public final class BtGlow {
    private static TextureTarget a, b;
    private static EffectInstance blur, blit;
    private static boolean pending, failed;

    private static final RenderStateShard.TransparencyStateShard ADD = new RenderStateShard.TransparencyStateShard(
            MurimMod.MODID + "_glow_add", () -> {
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                        GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
            }, () -> {
                RenderSystem.disableBlend();
                RenderSystem.defaultBlendFunc();
            });
    private static final RenderStateShard.OutputStateShard TARGET = new RenderStateShard.OutputStateShard(
            MurimMod.MODID + "_glow_target", () -> {
                if (a != null) {
                    a.bindWrite(true);
                }
            }, () -> Minecraft.getInstance().getMainRenderTarget().bindWrite(true));

    /**
     * The ribbons glow at under half the halo's strength: at full they read as neon tubes, brighter than the lab's (Mac film 01.11).
     * The position-colour shader multiplies by ColorModulator (RenderSystem#setShaderColor), so its alpha scales the added light.
     */
    private static final float RIBBON_GLOW = .45F;
    private static final RenderStateShard.TransparencyStateShard ADD_RIBBONS = new RenderStateShard.TransparencyStateShard(
            MurimMod.MODID + "_glow_add_ribbons", () -> {
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                        GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
                RenderSystem.setShaderColor(1F, 1F, 1F, RIBBON_GLOW);
            }, () -> {
                RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
                RenderSystem.disableBlend();
                RenderSystem.defaultBlendFunc();
            });

    /** The preset ribbons' twin (MurimRenderTypes#presetBody: position-colour shader, NEW_ENTITY vertices). */
    private static final RenderType PRESET = RenderType.create(MurimMod.MODID + ":bt_glow_preset", DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS, 4096, RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                    .setTransparencyState(ADD_RIBBONS)
                    .setOutputState(TARGET)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));
    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/renderer/
    // RenderType.java#CompositeStateBuilder, RenderStateShard.java#NO_DEPTH_TEST/COLOR_WRITE;
    // GameRenderer.java#getPositionColorShader. Core stays visible over the posed body.
    private static final RenderType CORE = RenderType.create(MurimMod.MODID + ":bt_glow_core",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 4096, RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                    .setTransparencyState(ADD).setOutputState(TARGET)
                    .setCullState(RenderStateShard.NO_CULL).setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE).createCompositeState(false));
    public static RenderType core() { return CORE; }

    private static final java.util.Map<ResourceLocation, RenderType> TEXTURED = new java.util.HashMap<>();

    private BtGlow() {
    }

    /** Whether this frame draws glow (BtGlow#prepare succeeded and the composite has not run yet). */
    public static boolean active() {
        return pending;
    }

    /** The glow twin of the preset ribbons' type. */
    public static RenderType preset() {
        return PRESET;
    }

    /** The glow twin of an additive textured type (MurimRenderTypes#additive: eyes shader, NEW_ENTITY vertices). */
    public static RenderType textured(ResourceLocation texture) {
        return TEXTURED.computeIfAbsent(texture, tex -> RenderType.create(MurimMod.MODID + ":bt_glow_" + tex.getPath().replace('/', '_'),
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 4096, RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_EYES_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(tex, true, false))
                        .setTransparencyState(ADD)
                        .setOutputState(TARGET)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                        .setOverlayState(RenderStateShard.NO_OVERLAY)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .createCompositeState(false)));
    }

    /**
     * Once a frame, before the scene draws: the half-size target, cleared, with the frame's depth. Returns false when the glow is off
     * for this frame (then nothing may be drawn into the twins).
     */
    public static boolean prepare(Minecraft mc, float k) {
        if (failed || !Float.isFinite(k) || k <= 0 || !io.github.verycooltimo.murim.client.vfx.TechniqueGlow.enabled()
                || !io.github.verycooltimo.murim.client.vfx.TechniqueGlow.collecting()) {
            return false;
        }
        // The pure frame arbiter is also used by GlowFrameTest: only its first request clears/copies depth.
        if (!io.github.verycooltimo.murim.client.vfx.TechniqueGlow.requested(k)) return pending;
        RenderTarget main = mc.getMainRenderTarget();
        int w = Math.max(1, main.width / 2), h = Math.max(1, main.height / 2);
        if (a == null) {
            a = new TextureTarget(w, h, true, Minecraft.ON_OSX);
            b = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            a.setFilterMode(GL11.GL_LINEAR);
            b.setFilterMode(GL11.GL_LINEAR);
        } else if (a.width != w || a.height != h) {
            a.resize(w, h, Minecraft.ON_OSX);
            b.resize(w, h, Minecraft.ON_OSX);
            a.setFilterMode(GL11.GL_LINEAR);
            b.setFilterMode(GL11.GL_LINEAR);
        }
        a.setClearColor(0, 0, 0, 0);
        a.clear(Minecraft.ON_OSX);
        a.copyDepthFrom(main);
        main.bindWrite(true);
        pending = true;
        return true;
    }

    /** Drop an abandoned frame before the next level render begins. */
    public static void discard() { pending = false; }

    /** Scene calls defer to TechniqueGlow; the final frame flush blurs and adds twice as before. */
    public static void composite(Minecraft mc) {
        // A scene's local flush is complete, but other effects can still contribute this frame.
        if (io.github.verycooltimo.murim.client.vfx.TechniqueGlow.collecting()) return;
        if (!pending) {
            return;
        }
        pending = false;
        if (blur == null) {
            try {
                blur = new EffectInstance(mc.getResourceManager(), "box_blur");
                blit = new EffectInstance(mc.getResourceManager(), "blit");
            } catch (Exception e) {
                failed = true;
                MurimMod.LOGGER.warn("Breakthrough glow shaders failed to load", e);
                return;
            }
        }
        RenderTarget main = mc.getMainRenderTarget();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        float strength = io.github.verycooltimo.murim.client.vfx.TechniqueGlow.strength();
        // a tight glow hugging each band, then a wide soft one (the lab's blurred canvases)
        pass(a, b, 1, 0, 3);
        pass(b, a, 0, 1, 3);
        add(main, .85F * strength);
        pass(a, b, 1, 0, 9);
        pass(b, a, 0, 1, 9);
        add(main, .7F * strength);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        main.bindWrite(true);
    }

    private static void pass(TextureTarget from, TextureTarget to, float dx, float dy, float radius) {
        to.bindWrite(true);
        float w = to.width, h = to.height;
        blur.safeGetUniform("ProjMat").set(new Matrix4f().setOrtho(0, w, 0, h, .1F, 1000F));
        blur.safeGetUniform("InSize").set((float) from.width, (float) from.height);
        blur.safeGetUniform("OutSize").set(w, h);
        blur.safeGetUniform("BlurDir").set(dx, dy);
        blur.safeGetUniform("Radius").set(radius);
        blur.safeGetUniform("RadiusMultiplier").set(1F);
        blur.setSampler("DiffuseSampler", from::getColorTextureId);
        blur.apply();
        // box_blur replaces (one, zero); EffectInstance may skip setting it when its blend mode was the last applied
        RenderSystem.disableBlend();
        quad(w, h);
        blur.clear();
    }

    private static void add(RenderTarget main, float k) {
        main.bindWrite(true);
        float w = main.width, h = main.height;
        blit.safeGetUniform("ProjMat").set(new Matrix4f().setOrtho(0, w, 0, h, .1F, 1000F));
        blit.safeGetUniform("OutSize").set(w, h);
        blit.safeGetUniform("ColorModulate").set(k, k, k, 1F);
        blit.setSampler("DiffuseSampler", a::getColorTextureId);
        blit.apply();
        // EffectInstance skips its blend mode when it was the last one applied; set the additive blend after it.
        RenderSystem.enableBlend();
        RenderSystem.blendEquation(org.lwjgl.opengl.GL14.GL_FUNC_ADD);
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        quad(w, h);
        blit.clear();
    }

    private static void quad(float w, float h) {
        BufferBuilder quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        quad.addVertex(0, 0, 500);
        quad.addVertex(w, 0, 500);
        quad.addVertex(w, h, 500);
        quad.addVertex(0, h, 500);
        BufferUploader.draw(quad.buildOrThrow());
    }
}
