package io.github.verycooltimo.murim.training;

import java.util.List;

/**
 * A timed run along checkpoints — the South Peak climb ({@code climb_1 → climb_16}) and the trail sprint (lower gate
 * → sect gate). Pure: plain coordinates in, events out ({@code RouteRunTest}).
 *
 * <ul>
 *   <li>Standing at checkpoint 0 starts the run (the trail also wants a sprint — the caller decides).</li>
 *   <li>Checkpoints count only in order.</li>
 *   <li>Climb: falling {@code fallDrop} below the last reached ledge sends progress back to the last rest ledge
 *       (task 05.10: «restart from rest ledges if you fall»); the clock runs on.</li>
 *   <li>Leaving the route ({@code abandon} blocks from the last checkpoint) or the timeout ends it without result;
 *       a violation (qi, elytra, teleport — the caller sees them) voids it.</li>
 * </ul>
 */
public final class RouteRun {

    /** A checkpoint: feet position, reach radius, rest ledge, how far below/above the feet it still counts. */
    public record Point(double x, double y, double z, double radius, boolean rest, double below, double above) {
        public Point(double x, double y, double z, double radius, boolean rest) {
            this(x, y, z, radius, rest, 1.5D, 3.0D);
        }
    }

    public enum Kind { START, REACH, FELL, FINISH, ABANDON, VOID }

    /** @param index REACH: the checkpoint reached; FELL: the rest checkpoint to go on from; FINISH: elapsed ticks */
    public record Event(Kind kind, int index) {
    }

    private final List<Point> points;
    private final double fallDrop;
    private final double abandon;
    private final int timeout;
    private boolean active;
    private int next;
    private int rest;
    private long start;

    /** @param fallDrop 0 — no falls (the trail) */
    public RouteRun(List<Point> points, double fallDrop, double abandon, int timeout) {
        this.points = List.copyOf(points);
        this.fallDrop = fallDrop;
        this.abandon = abandon;
        this.timeout = timeout;
    }

    public boolean active() {
        return active;
    }

    /** Next checkpoint to reach. */
    public int next() {
        return next;
    }

    public int size() {
        return points.size();
    }

    public long start() {
        return start;
    }

    public List<Point> points() {
        return points;
    }

    /** Near checkpoint 0 (the caller may want more, e.g. sprinting). */
    public boolean atStart(double x, double y, double z) {
        return !points.isEmpty() && reached(points.get(0), x, y, z);
    }

    /**
     * One tick.
     *
     * @param mayStart  the caller allows a start this tick (e.g. sprinting for the trail)
     * @param violation qi used, elytra, teleport — voids an active run
     */
    public Event step(double x, double y, double z, long tick, boolean mayStart, boolean violation) {
        if (points.size() < 2) {
            return null;
        }
        if (!active) {
            if (mayStart && !violation && reached(points.get(0), x, y, z)) {
                active = true;
                next = 1;
                rest = 0;
                start = tick;
                return new Event(Kind.START, 0);
            }
            return null;
        }
        if (violation) {
            active = false;
            return new Event(Kind.VOID, next);
        }
        if (tick - start > timeout) {
            active = false;
            return new Event(Kind.ABANDON, next);
        }
        Point last = points.get(next - 1);
        if (Math.hypot(x - last.x(), z - last.z()) > abandon && Math.hypot(x - points.get(next).x(), z - points.get(next).z()) > abandon) {
            active = false;
            return new Event(Kind.ABANDON, next);
        }
        if (fallDrop > 0.0D && y < last.y() - fallDrop && next - 1 > rest) {
            next = rest + 1;
            return new Event(Kind.FELL, rest);
        }
        Point p = points.get(next);
        if (reached(p, x, y, z)) {
            if (p.rest()) {
                rest = next;
            }
            next++;
            if (next >= points.size()) {
                active = false;
                return new Event(Kind.FINISH, (int) (tick - start));
            }
            return new Event(Kind.REACH, next - 1);
        }
        return null;
    }

    public void cancel() {
        active = false;
    }

    private static boolean reached(Point p, double x, double y, double z) {
        double dy = y - p.y();
        return Math.hypot(x - p.x(), z - p.z()) <= p.radius() && dy >= -p.below() && dy <= p.above();
    }
}
