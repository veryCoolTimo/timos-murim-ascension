package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.sect.HeadIntroCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Passive film stand: no camera or input changes. API: SparFilmCapture's screenshot/flush path. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class HeadIntroFilmCapture {
    public static final String SUBJECT = "headintrofilm";
    private static int waiting, ticks, frames, after, flush;
    private static boolean seen, finishing, stopped;
    private HeadIntroFilmCapture() { }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        if (FMLEnvironment.production || !Boolean.getBoolean("murim.capture")
                || !SUBJECT.equals(System.getProperty("murim.capture.technique")) || stopped) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (finishing) {
            String[] files = new java.io.File(mc.gameDirectory, "screenshots")
                    .list((dir, name) -> name.startsWith("head_intro_") && name.endsWith(".png"));
            if ((files != null && files.length >= frames) || ++flush > 4800) {
                MurimMod.LOGGER.info("headintrofilm: {} requested frames, {} written", frames, files == null ? 0 : files.length);
                stopped = true;
                mc.stop();
            }
            return;
        }
        if (!seen) {
            if (!HeadIntroCamera.active()) {
                if (++waiting > 2400) {
                    MurimMod.LOGGER.error("headintrofilm: scene did not start");
                    finishing = true;
                }
                return;
            }
            seen = true;
            MurimMod.LOGGER.info("headintrofilm: scene started");
        }
        if (ticks++ % 2 == 0) {
            Screenshot.grab(mc.gameDirectory, String.format("head_intro_%04d.png", frames++),
                    mc.getMainRenderTarget(), message -> { });
        }
        if (!HeadIntroCamera.active() && ++after >= 40) {
            MurimMod.LOGGER.info("headintrofilm: scene ended, recorded 40 more client ticks");
            finishing = true;
        }
    }
}
