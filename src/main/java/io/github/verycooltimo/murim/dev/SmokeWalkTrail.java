package io.github.verycooltimo.murim.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/** Pure centreline geometry for the dev walker; no navigation or activity state. */
public final class SmokeWalkTrail {
    private SmokeWalkTrail() { }

    public record Point(double x, double y, double z) { }
    public record Steering(int next, double distance, List<Point> points) { }

    /** Nearest standable feet height within six blocks of the authored feet height. */
    public static double ground(double authoredY, IntPredicate solid, IntPredicate passable) {
        int origin = (int) Math.floor(authoredY);
        for (int offset = 0; offset <= 6; offset++) {
            for (int y : offset == 0 ? new int[]{origin} : new int[]{origin - offset, origin + offset}) {
                if (Math.abs(y - authoredY) <= 6 && solid.test(y - 1)
                        && passable.test(y) && passable.test(y + 1)) return y;
            }
        }
        return authoredY; // Keep the original target if this column has no supported landing.
    }

    public static boolean reached(Point player, Point target) {
        return horizontallyReached(player, target) && Math.abs(player.y - target.y) <= 6;
    }

    public static boolean horizontallyReached(Point player, Point target) {
        return Math.hypot(player.x - target.x, player.z - target.z) <= 2.5;
    }

    public static Steering steer(List<Point> trail, Point player, int previousNext) {
        if (trail.size() < 2) throw new IllegalArgumentException("Trail needs two points");
        int segment = 0;
        double best = Double.POSITIVE_INFINITY;
        double along = 0;
        // Keep progress monotonic, but allow the current incoming segment until its end is passed.
        for (int i = Math.max(0, previousNext - 1); i < trail.size() - 1; i++) {
            Point a = trail.get(i), b = trail.get(i + 1);
            double dx = b.x - a.x, dz = b.z - a.z;
            double t = Math.max(0, Math.min(1, ((player.x - a.x) * dx + (player.z - a.z) * dz) / (dx * dx + dz * dz)));
            Point at = interpolate(a, b, t);
            double distance = Math.sqrt(square(player.x - at.x) + square(player.y - at.y) + square(player.z - at.z));
            // At a vertex prefer the outgoing segment, so the next point advances immediately.
            if (distance <= best) { best = distance; segment = i; along = t; }
        }
        while (segment < trail.size() - 2 && reached(player, trail.get(segment + 1))) {
            segment++;
            along = 0;
        }
        List<Point> points = new ArrayList<>();
        for (int i = segment; i <= Math.min(segment + 1, trail.size() - 2); i++) {
            Point a = trail.get(i), b = trail.get(i + 1);
            double length = Math.hypot(b.x - a.x, b.z - a.z);
            double start = i == segment ? along : 0;
            // Short targets pull a displaced player back to the line without cutting hairpins.
            for (double t = start + 1 / length; t < 1; t += 1 / length) points.add(interpolate(a, b, t));
            points.add(b);
        }
        return new Steering(segment + 1, best, List.copyOf(points));
    }

    /** Four-block samples retain the incoming polyline instead of aiming at a switchback above. */
    public static List<Point> samples(Point a, Point b) {
        List<Point> points = new ArrayList<>();
        double length = Math.hypot(b.x - a.x, b.z - a.z);
        points.add(a);
        for (double distance = 4; distance < length; distance += 4)
            points.add(interpolate(a, b, distance / length));
        points.add(b);
        return List.copyOf(points);
    }

    private static double square(double value) { return value * value; }
    private static Point interpolate(Point a, Point b, double t) {
        return new Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
    }
}
