package io.github.verycooltimo.murim.client.flow;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * Dev measurement of what the pill scene costs (MURIM_PILL_PERF=1; run on the Mac with the real GPU, tools/capture/pilldantian_film_mac.sh perf):
 * the frame limit and vsync are switched off, and the frame time is averaged over the scene ({@code phase=fx}) and over the three seconds
 * after it, when the same body sits in the same view without the effect ({@code phase=empty}). The difference is the effect's own cost.
 * Lines: {@code Pill perf phase=<fx|empty> frames=<n> frame=<ms> fps=<n>}.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class PillScenePerf {
    private static final boolean ON = "1".equals(System.getenv("MURIM_PILL_PERF"));
    private static boolean unlimited, wasActive, doneFx;
    private static long last, afterStart;
    private static double sum;
    private static int frames;

    private PillScenePerf() { }

    @SubscribeEvent
    static void onFrame(RenderFrameEvent.Pre event) {
        if (!ON) return;
        Minecraft mc = Minecraft.getInstance();
        if (!unlimited) {
            unlimited = true;
            mc.options.framerateLimit().set(260);
            mc.options.enableVsync().set(false);
            MurimMod.LOGGER.info("Pill perf: frame limit off, vsync off");
        }
        long now = System.nanoTime();
        double ms = last == 0 ? 0 : (now - last) / 1e6;
        last = now;
        // The pill scene, or the seed finale of the dantian creation (the same body and view afterwards is the empty phase).
        boolean active = PillScene.active() || FlowClient.active() || io.github.verycooltimo.murim.client.ClientMeditationState.seedSceneAge() >= 0;
        if (active) {
            if (frames == 0 || !wasActive) { sum = 0; frames = 0; }
            if (ms > 0) { sum += ms; frames++; }
        } else if (wasActive && !doneFx) {
            doneFx = true;
            report("fx");
            afterStart = now;
        } else if (doneFx && afterStart != 0) {
            if (ms > 0 && now - afterStart > 500_000_000L) { sum += ms; frames++; }
            if (now - afterStart > 3_500_000_000L) { report("empty"); afterStart = 0; }
        }
        wasActive = active;
    }

    private static void report(String phase) {
        if (frames > 0) MurimMod.LOGGER.info(String.format("Pill perf phase=%s frames=%d frame=%.3f fps=%.1f", phase, frames, sum / frames, 1000.0 * frames / sum));
        sum = 0; frames = 0;
    }
}
