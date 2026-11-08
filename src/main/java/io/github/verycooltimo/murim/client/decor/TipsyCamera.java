package io.github.verycooltimo.murim.client.decor;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientConfig;
import io.github.verycooltimo.murim.decor.IntWorkshop;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** Smooth additive sway; no server camera state.
 * API: reference/neoforge-src/net/neoforged/neoforge/client/event/{ViewportEvent,RenderLevelStageEvent,ClientTickEvent}.java;
 * reference/minecraft-src/net/minecraft/client/renderer/PostChain.java (owned chain and uniforms),
 * client/Options.java (screenEffectScale), client/DeltaTracker.java (partial ticks).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class TipsyCamera {
    private TipsyCamera() { }

    // Owned PostChain: close on world exit/resource reload, and resize with the framebuffer.
    private static net.minecraft.client.renderer.PostChain doubleVision;
    private static Object resources;
    private static int width, height;
    private static boolean failed;
    private static float previousFade, fade;
    private static int lastLevel, pulseAge = -1, nextPulse;
    private static final java.util.Random PULSE_RANDOM = new java.util.Random();

    @SubscribeEvent
    static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.isPaused()) return;
        previousFade = fade;
        var effect = mc.player == null ? null : mc.player.getEffect(IntWorkshop.TIPSY);
        int level = effect == null ? 0 : Math.min(3, effect.getAmplifier() + 1);
        if (level > 0) lastLevel = level;
        fade = TipsyMotion.fadeStep(fade, level > 0);
        if (level == 3) {
            if (nextPulse <= 0) nextPulse = 200 + PULSE_RANDOM.nextInt(101);
            if (pulseAge >= 0 && ++pulseAge >= 24) pulseAge = -1;
            if (--nextPulse == 0) pulseAge = 0;
        } else { pulseAge = -1; nextPulse = 0; }
        if (mc.player == null) {
            previousFade = fade = 0;
            lastLevel = 0;
            resetDoubleVision();
        }
    }

    public static void resetDoubleVision() {
        if (doubleVision != null) doubleVision.close();
        doubleVision = null;
        resources = null;
        failed = false;
    }

    @SubscribeEvent
    static void doubleVision(RenderLevelStageEvent event) {
        // API: reference/minecraft-src/net/minecraft/client/renderer/GameRenderer.java#renderLevel
        // dispatches AFTER_LEVEL before the hand/HUD, even with F1. LevelRenderer#renderLevel
        // leaves the main target bound (also after the Fabulous transparency chain).
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { resetDoubleVision(); return; }
        if (mc.screen != null || mc.isPaused() || mc.getCameraEntity() != mc.player
                || !ClientConfig.DISTORTION_EFFECTS.get() || mc.options.screenEffectScale().get() <= 0) return;
        var effect = mc.player.getEffect(IntWorkshop.TIPSY);
        if (effect == null || effect.getAmplifier() < 2) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float envelope = TipsyMotion.pulse(pulseAge < 0 ? -1 : pulseAge + partial);
        if (envelope <= 0) return;
        if (resources != mc.getResourceManager()) resetDoubleVision();
        if (failed) return;
        try {
            int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
            if (doubleVision == null) {
                resources = mc.getResourceManager();
                doubleVision = new net.minecraft.client.renderer.PostChain(mc.getTextureManager(), mc.getResourceManager(),
                        mc.getMainRenderTarget(), net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "shaders/post/tipsy.json"));
                width = -1;
            }
            if (width != w || height != h) { doubleVision.resize(w, h); width = w; height = h; }
            doubleVision.setUniform("Strength", envelope * mc.options.screenEffectScale().get().floatValue());
            boolean blend = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND);
            boolean depth = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.resetTextureMatrix();
            try {
                doubleVision.process(event.getPartialTick().getGameTimeDeltaTicks());
            } finally {
                mc.getMainRenderTarget().bindWrite(true);
                if (depth) com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
                else com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
                if (blend) com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                else com.mojang.blaze3d.systems.RenderSystem.disableBlend();
            }
        } catch (Exception e) {
            resetDoubleVision();
            resources = mc.getResourceManager();
            failed = true;
            MurimMod.LOGGER.warn("Could not load tipsy double vision", e);
        }
    }

    @SubscribeEvent
    static void angles(ViewportEvent.ComputeCameraAngles event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || event.getCamera().getEntity() != mc.player || mc.isPaused()) return;
        float partial = (float) event.getPartialTick();
        float elapsed = mc.player.tickCount + partial;
        float envelope = Mth.lerp(partial, previousFade, fade);
        float shake = (float) ClientConfig.cameraShake();
        event.setRoll(event.getRoll() + TipsyMotion.roll(lastLevel, elapsed, shake, envelope));
        event.setYaw(event.getYaw() + TipsyMotion.yaw(lastLevel, elapsed, shake, envelope));
        event.setPitch(event.getPitch() + TipsyMotion.pitch(lastLevel, elapsed, shake, envelope));
    }
}
