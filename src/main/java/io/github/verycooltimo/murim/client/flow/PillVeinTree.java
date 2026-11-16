package io.github.verycooltimo.murim.client.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The branching meridians of the pill scene (author 11.11 «меридианы — палки», 14.11 «красиво и на уровне»; refs pills-ar
 * «absorbing pill», heavenly «breaking»): one tree that starts at the dantian, runs up the centre line, forks to the shoulders and
 * sends ribs, hips and twigs of twigs sideways. All points are torso pixels (x across, screen right +; u up from the bottom of the
 * torso, 12 = the collar) and lie INSIDE the torso silhouette, so nothing can leave the body. The lines are jagged (small kinks at every
 * node, as the lightning-like veins of the refs), never straight sticks and never swaying: they only grow and carry clots.
 *
 * <p>Pure and client-free; the same seed always gives the same tree (PillVeinTreeTest).
 */
public final class PillVeinTree {
    /** Half width of the torso in pixels (the torso is 8 px wide) minus a margin, and the usable height range. */
    public static final double HALF = 3.55, U_MIN = 1.3, U_MAX = 11.3;
    public static final double BELLY_U = 3.2;

    /** One branch. {@code start} and {@code dur} are on the growth clock 0..1; {@code at} is the share of the parent where it forks. */
    public record Branch(int depth, int parent, double at, double start, double dur, double width, List<double[]> pts, double length) {
        /** Point at {@code s} 0..1 of the branch length. */
        public double[] at(double s) {
            if (pts.size() < 2) return pts.get(0);
            double want = Math.max(0, Math.min(1, s)) * length, run = 0;
            for (int i = 1; i < pts.size(); i++) {
                double[] a = pts.get(i - 1), b = pts.get(i);
                double seg = Math.hypot(b[0] - a[0], b[1] - a[1]);
                if (run + seg >= want || i == pts.size() - 1) {
                    double k = seg < 1e-9 ? 0 : Math.max(0, Math.min(1, (want - run) / seg));
                    return new double[] {a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k};
                }
                run += seg;
            }
            return pts.get(pts.size() - 1);
        }
    }

    private final List<Branch> branches = new ArrayList<>();
    private final Random rng;

    public PillVeinTree(long seed, int detail) {
        rng = new Random(seed);
        build(Math.max(1, Math.min(3, detail)));
        normalise();
    }

    public List<Branch> branches() { return branches; }

    public static double clampX(double x, double u) {
        double room = HALF - (u > 9.6 ? (u - 9.6) * .25 : 0);
        return Math.max(-room, Math.min(room, x));
    }

    private static double clampU(double u) { return Math.max(U_MIN, Math.min(U_MAX, u)); }

    private double jit(double a) { return (rng.nextDouble() - .5) * 2 * a; }

    /** A jagged polyline from {@code (x0,u0)} towards {@code (x1,u1)} with a kink at every node. */
    private List<double[]> jagged(double x0, double u0, double x1, double u1, double step, double kink) {
        double len = Math.hypot(x1 - x0, u1 - u0);
        int n = Math.max(2, (int) Math.round(len / step));
        double nx = -(u1 - u0) / len, nu = (x1 - x0) / len, side = rng.nextBoolean() ? 1 : -1;
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n, bow = Math.sin(Math.PI * t) * kink * .9 * side;
            double k = (i == 0 || i == n) ? 0 : jit(kink);
            double x = x0 + (x1 - x0) * t + nx * (bow + k), u = u0 + (u1 - u0) * t + nu * (bow + k);
            pts.add(new double[] {clampX(x, clampU(u)), clampU(u)});
        }
        return pts;
    }

    private static double length(List<double[]> pts) {
        double s = 0;
        for (int i = 1; i < pts.size(); i++) s += Math.hypot(pts.get(i)[0] - pts.get(i - 1)[0], pts.get(i)[1] - pts.get(i - 1)[1]);
        return s;
    }

    private int add(int depth, int parent, double at, List<double[]> pts, double width) {
        branches.add(new Branch(depth, parent, at, 0, 0, width, pts, length(pts)));
        return branches.size() - 1;
    }

    private void build(int detail) {
        int trunk = add(0, -1, 0, jagged(0, BELLY_U, 0, 11.2, 1.25, .34), .5);
        // Shoulders: from the throat, out along the collar bones.
        for (int s = -1; s <= 1; s += 2) {
            fork(trunk, .88, 1, jagged(0, 9.4, s * 3.4, 10.2, .85, .38), .34, detail);
        }
        // Ribs: long sweeping arcs out from the trunk at four heights; the low ones fall away, the high ones climb to the shoulders (refs: long
        // veins crossing the chest, never a symmetric fish bone). Each side has its own reach and slope.
        double[] at = {.2, .38, .58, .76};
        for (int i = 0; i < at.length; i++) for (int s = -1; s <= 1; s += 2) {
            double[] p = branches.get(trunk).at(at[i]);
            double reach = 2.0 + rng.nextDouble() * 1.45;
            double rise = i < 2 ? -1.2 + rng.nextDouble() * 1.7 : 1.0 + rng.nextDouble() * 1.9;
            fork(trunk, at[i], 1, jagged(p[0], p[1], s * reach, p[1] + rise, .85, .42), .3, detail);
        }
        // Hips: down and out from the belly.
        for (int s = -1; s <= 1; s += 2) {
            fork(trunk, 0, 1, jagged(0, BELLY_U, s * 3.2, 1.7, .85, .38), .3, detail);
        }
    }

    private void fork(int parent, double at, int depth, List<double[]> pts, double width, int detail) {
        int id = add(depth, parent, at, pts, width);
        if (depth >= detail + 1 || depth >= 3) return;
        Branch b = branches.get(id);
        int twigs = depth == 1 ? 1 + rng.nextInt(2) : 1;
        for (int k = 0; k < twigs; k++) {
            double s = .25 + .6 * (k + rng.nextDouble() * .6) / twigs;
            double[] p = b.at(Math.min(.9, s)), q = b.at(Math.min(1, s + .1));
            double dir = Math.atan2(q[1] - p[1], q[0] - p[0]) + (rng.nextBoolean() ? 1 : -1) * (.5 + rng.nextDouble() * .6);
            double len = (depth == 1 ? 1.1 + rng.nextDouble() * 1.2 : .6 + rng.nextDouble() * .5);
            double x1 = p[0] + Math.cos(dir) * len, u1 = p[1] + Math.sin(dir) * len;
            fork(id, s, depth + 1, jagged(p[0], p[1], clampX(x1, u1), clampU(u1), .7, .18), b.width() * .66, detail);
        }
    }

    /** Fills {@code start} and {@code dur}: a branch grows after its parent has reached the fork, at a steady speed; the whole tree takes 1.0. */
    private void normalise() {
        double[] begin = new double[branches.size()], end = new double[branches.size()];
        double speed = 16, delay = .06, max = 0;
        for (int i = 0; i < branches.size(); i++) {
            Branch b = branches.get(i);
            double s = b.parent() < 0 ? 0 : begin[b.parent()] + (end[b.parent()] - begin[b.parent()]) * b.at() + delay;
            double d = b.length() / speed;
            begin[i] = s; end[i] = s + d; max = Math.max(max, end[i]);
        }
        for (int i = 0; i < branches.size(); i++) {
            Branch b = branches.get(i);
            branches.set(i, new Branch(b.depth(), b.parent(), b.at(), begin[i] / max, (end[i] - begin[i]) / max, b.width(), b.pts(), b.length()));
        }
    }
}
