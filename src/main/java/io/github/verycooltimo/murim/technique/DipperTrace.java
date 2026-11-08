package io.github.verycooltimo.murim.technique;

import java.util.List;

/** Shared terrestrial geometry and lifetime, independent of rendering and world state.
 * Design: docs/adr/seven-stars-readable-1103.md. */
public final class DipperTrace {
    public record Point(double x, double z) {}
    public record Edge(int from, int to) {}
    public static final List<Point> POINTS = List.of(new Point(-2, -1.5), new Point(-1.4, -.8),
            new Point(-.7, -1), new Point(.4, -1.2), new Point(1.7, -.8), new Point(1.5, 1.2), new Point(.2, 1.3));
    public static final int FADE_TICKS = 30;
    /** Handle to bowl in walking order; the final stop closes the bowl back to its neck. */
    public static List<Edge> edges(int reached) {
        var edges = new java.util.ArrayList<Edge>();
        for (int i = 1; i < Math.min(7, reached); i++) edges.add(new Edge(i - 1, i));
        if (reached >= 7) edges.add(new Edge(6, 3));
        return List.copyOf(edges);
    }
    public static float alpha(double now, double seventhCut) {
        return .7F * (float) Math.clamp(1 - Math.max(0, now - seventhCut) / FADE_TICKS, 0, 1);
    }
    private DipperTrace() {}
}
