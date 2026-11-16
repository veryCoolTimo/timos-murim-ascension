package io.github.verycooltimo.murim.client.vfx.fx;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.verycooltimo.murim.MurimMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * Scene control for live tuning (dev only, active only with MURIM_TUNE; see {@link FxTune}). Reads these keys of the tune file:
 * <ul>
 * <li>{@code _time}: hold the scene clock at this second.</li>
 * <li>{@code _loop}: [from, to] seconds, play in a loop. Without {@code _loop} a bench element loops over its own window.</li>
 * <li>{@code _speed}: clock speed (1 = real time, 0.5 = half, negative runs back).</li>
 * <li>{@code _shot}: a counter; when it grows, one full-resolution PNG is saved to {@code <path>.shots/<counter>.png} at the end of the first
 * frame drawn with the values of the same file. {@code _burst}: N &gt; 1 saves N consecutive frames {@code <counter>_001.png ...} instead, each
 * one 1/60 s of scene time apart (a fixed step, so the strip does not depend on the cost of the readback).</li>
 * <li>{@code _reload_shaders}: a counter; when it grows, the game's resources (core shaders included) are reloaded without restart.</li>
 * </ul>
 * A scene reads its clock through {@link #time(double)}: it gives the natural time back unless a control is set. With no control set nothing changes.
 * A scene that is a simulation and not a function of time (the pill flow game) takes {@link #simDt(double)} and {@link #simLoopDue(double)}.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class FxTuneScene {
    private static final double BURST_STEP = 1.0 / 60.0;
    /** One thread: files of a burst land in order and the ack for the last one is written after all of them. */
    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FxTune-writer");
        t.setDaemon(true);
        return t;
    });

    private static boolean hold, loop, engaged;
    private static double heldAt, loopFrom, loopTo, speed = 1;
    private static double cur = Double.NaN, lastNatural = -1, winFrom = Double.NaN, winTo = Double.NaN;
    private static boolean seenScene;
    private static int btTarget;
    private static long lastFrame;
    private static boolean firstLoad = true;
    private static int shotCounter, reloadCounter, wantedShot = -1, burstLeft, burstTotal, burstIndex;
    private static CompletableFuture<Void> reloading;
    private static final List<String> burstNames = new ArrayList<>();

    private FxTuneScene() {}

    /** The window of the running scene in seconds (a bench element); out of it the clock wraps. Call every frame from the scene's render. */
    public static void window(double from, double to) {
        if (!FxTune.ON) return;
        winFrom = from;
        winTo = to;
    }

    /** The scene clock: the natural time, or the controlled one. Negative natural time means «no scene»; it is returned as is until a scene has run. */
    public static double time(double natural) {
        if (!FxTune.ON) return natural;
        if (natural >= 0) seenScene = true;
        lastNatural = natural;
        if (!engaged || !seenScene) return natural;
        return Double.isNaN(cur) ? natural : cur;
    }

    /** The target rank of a breakthrough scene. While a control is set the last scene seen keeps playing after the server's scene is over. */
    public static int btTarget(int natural) {
        if (!FxTune.ON) return natural;
        if (natural > 0) btTarget = natural;
        return natural <= 0 && engaged && btTarget > 0 ? btTarget : natural;
    }

    public static boolean engaged() { return FxTune.ON && engaged; }

    /** For simulations: the step to integrate this frame. 0 while held, scaled by {@code _speed}; the plain step when no control is set. */
    public static double simDt(double dt) {
        if (!FxTune.ON || !engaged) return dt;
        return hold ? 0 : dt * speed;
    }

    /** For simulations: true when a loop is set and the scene clock {@code seconds} passed its end; the scene restarts itself. */
    public static boolean simLoopDue(double seconds) { return FxTune.ON && loop && seconds >= loopTo; }

    static JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("engaged", engaged);
        o.addProperty("held", hold);
        o.addProperty("loop", loop);
        o.addProperty("speed", speed);
        o.addProperty("t", Double.isNaN(cur) ? lastNatural : cur);
        return o;
    }

    @SubscribeEvent
    static void pre(RenderFrameEvent.Pre event) {
        if (!FxTune.ON) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        if (FxTune.poll()) applyControls(mc);
        if (burstLeft > 0) dt = BURST_STEP;
        advance(dt);
        if (reloading != null && reloading.isDone()) {
            reloading = null;
            MurimMod.LOGGER.info("FxTune: resources reloaded");
        }
    }

    private static void applyControls(Minecraft mc) {
        FxTuneStore s = FxTune.store();
        double[] range = s.controlArray("_loop");
        loop = range != null && range.length == 2 && range[1] > range[0];
        if (loop) { loopFrom = range[0]; loopTo = range[1]; }
        double heldValue = s.control("_time", Double.NaN);
        hold = !Double.isNaN(heldValue);
        if (hold) heldAt = heldValue;
        speed = s.control("_speed", 1);
        engaged = hold || loop || speed != 1;
        if (!engaged) cur = Double.NaN;
        int shot = (int) s.control("_shot", 0), reload = (int) s.control("_reload_shaders", 0);
        if (firstLoad) {
            // the counters of a file that was there before the client started are history, not requests
            shotCounter = shot;
            reloadCounter = reload;
            firstLoad = false;
        } else {
            if (reload > reloadCounter) {
                reloadCounter = reload;
                reloading = mc.reloadResourcePacks();
                MurimMod.LOGGER.info("FxTune: reloading resources (counter {})", reload);
            }
            if (shot > shotCounter) {
                shotCounter = shot;
                wantedShot = shot;
                burstTotal = Math.max(1, (int) Math.min(240, s.control("_burst", 0)));
                burstLeft = burstTotal;
                burstIndex = 0;
                burstNames.clear();
            }
        }
        FxTune.writeAck();
    }

    private static void advance(double dt) {
        if (!engaged) return;
        if (hold) {
            cur = heldAt;
            return;
        }
        if (Double.isNaN(cur)) {
            if (lastNatural >= 0) cur = lastNatural;
            else return;
        } else {
            cur += dt * speed;
        }
        double a, b;
        if (loop) { a = loopFrom; b = loopTo; }
        else if (!Double.isNaN(winFrom) && winTo > winFrom) { a = winFrom; b = winTo; }
        else { if (cur < 0) cur = 0; return; }
        if (cur < a || cur >= b) {
            double span = b - a;
            double x = cur - a;
            cur = a + (x - Math.floor(x / span) * span);
        }
    }

    @SubscribeEvent
    static void post(RenderFrameEvent.Post event) {
        if (!FxTune.ON || burstLeft <= 0) return;
        // wait for a shader reload in flight: a shot must show the new shaders
        if (reloading != null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        int counter = wantedShot, index = ++burstIndex;
        boolean last = --burstLeft == 0;
        String name = burstTotal > 1 ? String.format("%d_%03d.png", counter, index) : counter + ".png";
        NativeImage image;
        try {
            image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        } catch (RuntimeException e) {
            MurimMod.LOGGER.warn("FxTune: screenshot failed: {}", e.toString());
            burstLeft = 0;
            return;
        }
        burstNames.add(name);
        List<String> names = new ArrayList<>(burstNames);
        Path dir = Path.of(FxTune.shotsDir()), file = dir.resolve(name);
        WRITER.execute(() -> {
            try {
                Files.createDirectories(dir);
                image.writeToFile(file);
            } catch (IOException e) {
                MurimMod.LOGGER.warn("FxTune: cannot save {}: {}", file, e.toString());
            } finally {
                image.close();
            }
            // the ack names the file only when it is on disk; for a burst, when the last one is
            if (last) FxTune.shotSaved(counter, name, names);
        });
    }
}
