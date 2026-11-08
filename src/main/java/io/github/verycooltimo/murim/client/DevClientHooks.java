package io.github.verycooltimo.murim.client;

/** Client-only implementation, reached only by client callers of DevHooks. */
public final class DevClientHooks {
    public static boolean benchActive() { return io.github.verycooltimo.murim.client.vfx.preset.BenchRuntime.active(); }
    public static void resetCapture() { DevCaptureHandler.reset(); }
    public static void closeFilmScreen() { FilmScreens.close(); }
    public static boolean galleryActive() { return HudGalleryCapture.active(); }
    public static void initialize() {
        if (Boolean.getBoolean("murim.capture") && "bladeexamfilm".equals(System.getProperty("murim.capture.technique")))
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(new BladeExamFilmCapture());
        // API: NeoForge.java#EVENT_BUS; bus/api/IEventBus#register (existing client setup registration).
        if (io.github.verycooltimo.murim.dev.SmokeWalkRoute.enabled())
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(new io.github.verycooltimo.murim.client.sect.SmokeJourneyCapture());
    }
    private DevClientHooks() {}
    public static void stopBench() { io.github.verycooltimo.murim.client.vfx.preset.BenchRuntime.stop(); }
    public static double benchSeconds() { return io.github.verycooltimo.murim.client.vfx.preset.BenchRuntime.renderedSeconds(); }
    public static String benchElement() { return io.github.verycooltimo.murim.client.vfx.preset.BenchRuntime.element(); }
    public static int startBench(String element, double from, double to, boolean loop) { return io.github.verycooltimo.murim.client.vfx.preset.BenchRuntime.start(element, from, to, loop); }

}
