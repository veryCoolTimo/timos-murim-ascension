package io.github.verycooltimo.murim.client.flow;

import java.util.function.DoubleUnaryOperator;

/**
 * The stage clock of the finale stand elements. Retained code only reads it; the bench stand (excluded dev code, BenchRuntime) sets it.
 * Inactive by default: in the game the finale takes its time from the game itself.
 */
public final class FlowStageClock {
    private static String element = "";
    private static DoubleUnaryOperator clock;
    private static double rendered = Double.NaN;

    private FlowStageClock() {}

    public static boolean active() { return clock != null; }
    public static String element() { return clock == null ? "" : element; }
    /** Time of the framebuffer, not the following client tick (NaN when nothing is staged). */
    public static double renderedSeconds() { return rendered; }
    /** Stand clock at a partial tick of the NEXT frame (NaN when nothing is staged). */
    public static double secondsAt(float partial) { return clock == null ? Double.NaN : clock.applyAsDouble(partial); }

    /** Called by the bench stand when an element starts. */
    public static void start(String name, DoubleUnaryOperator secondsAtPartial) {
        element = name == null ? "" : name;
        clock = secondsAtPartial;
        rendered = Double.NaN;
    }
    /** Called by the bench stand once per rendered frame. */
    public static void rendered(double seconds) { rendered = seconds; }
    /** Called by the bench stand when it stops. */
    public static void stop() { clock = null; element = ""; rendered = Double.NaN; }
}
