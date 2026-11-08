package io.github.verycooltimo.murim.client.camera;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Scene-owned depth of field. API: supplied 1.21.1 PostChain/PostPass: main:depth binds
 * RenderTarget::getDepthTextureId. Both blur outputs are temporary: PostPass clears output depth.
 * GameRenderer#getProjectionMatrix uses near .05 and getDepthFar(). */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class CinematicDof {
    private static net.minecraft.client.renderer.PostChain chain;
    private static Object resources;
    private static int width, height;
    private static boolean failed, owned;
    private static float distance, range = 1, blur;
    private CinematicDof() { }
    public static float circleOfConfusion(float depth, float focus, float range, float max) {
        return Math.max(0, Math.min(1, (Math.abs(depth - focus) - range) / Math.max(range, .25F)))
                * Math.max(0, Math.min(8, max));
    }
    public static void focus(Vec3 point, Vec3 camera, float maxBlur, float focusRange, float envelope) {
        owned = true;
        distance = (float) point.distanceTo(camera);
        range = focusRange;
        blur = Math.max(0, Math.min(8, maxBlur)) * Math.max(0, Math.min(1, envelope));
    }
    public static void off() { owned = false; blur = 0; close(); }
    public static void close() {
        if (chain != null) chain.close();
        chain = null; resources = null; failed = false;
    }
    @SubscribeEvent
    static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (Minecraft.getInstance().level == null) off();
    }
    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { off(); return; }
        if (!owned || blur <= 0 || !ClientConfig.DISTORTION_EFFECTS.get()
                || mc.options.screenEffectScale().get() <= 0) return;
        if (resources != mc.getResourceManager()) close();
        if (failed) return;
        try {
            int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
            if (chain == null) {
                resources = mc.getResourceManager();
                chain = new net.minecraft.client.renderer.PostChain(mc.getTextureManager(), mc.getResourceManager(),
                        mc.getMainRenderTarget(), net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "shaders/post/dof.json"));
                width = -1;
            }
            if (width != w || height != h) { chain.resize(w, h); width = w; height = h; }
            chain.setUniform("MaxBlur", blur * mc.options.screenEffectScale().get().floatValue());
            chain.setUniform("FocusDistance", distance);
            chain.setUniform("FocusRange", range);
            chain.setUniform("Near", 0.05F);
            chain.setUniform("Far", mc.gameRenderer.getDepthFar());
            boolean blend = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND);
            boolean depth = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.resetTextureMatrix();
            try {
                chain.process(event.getPartialTick().getGameTimeDeltaTicks());
            } finally {
                mc.getMainRenderTarget().bindWrite(true);
                if (depth) com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
                else com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
                if (blend) com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                else com.mojang.blaze3d.systems.RenderSystem.disableBlend();
            }
        } catch (Exception e) {
            close();
            resources = mc.getResourceManager();
            failed = true;
            MurimMod.LOGGER.warn("Could not load cinematic depth of field", e);
        }
    }

}
