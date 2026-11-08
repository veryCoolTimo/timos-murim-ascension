package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.bt.BtGlow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Frame owner. All geometry must be flushed by its producer before AFTER_LEVEL. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class TechniqueGlow {
    private static final GlowFrame FRAME = new GlowFrame();
    private static boolean enabled = true;
    private TechniqueGlow() {}
    public static boolean want(float strength) { return BtGlow.prepare(Minecraft.getInstance(), strength); }
    public static boolean enabled() { return enabled; }
    public static void enabled(boolean value) { enabled = value; }
    public static boolean collecting() { return FRAME.collecting(); }
    public static boolean requested(float strength) { return FRAME.request(strength); }
    public static float strength() { return FRAME.strength(); }
    public static RenderType textured(ResourceLocation texture) { return BtGlow.textured(texture); }
    /** Depth-tested untextured geometry; core() intentionally retains legacy through-body semantics. */
    public static RenderType preset() { return BtGlow.preset(); }
    public static RenderType core() { return BtGlow.core(); }
    // API: /tmp/mr-ref-1121/neoforge-src/.../client/event/RenderLevelStageEvent.java:
    // AFTER_SKY starts a level frame, AFTER_LEVEL follows all AFTER_PARTICLES subscribers.
    @SubscribeEvent
    static void stage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            TechniqueBodyLight.view(event.getModelViewMatrix());
            FRAME.begin();
            BtGlow.discard();
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            if (FRAME.finish()) BtGlow.composite(Minecraft.getInstance());
        }
    }
}
