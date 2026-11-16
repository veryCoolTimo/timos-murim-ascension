package io.github.verycooltimo.murim.client.vfx.fx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The traced ink tree (FxInkTrace) in time, as cultivation: it grows out of the root branch by branch (a child starts when the head
 * of its parent reaches the fork), then it lives. Clots run outward along the branches (the look), a slow turbulence displaces a
 * point more the farther it is from the root along the tree (the root stays put; the phase depends on that distance and the place,
 * so a child leaves its parent exactly where the parent is), and the tree renews itself: every part beyond a ring round the root
 * is a renewal group; its ink slides off along its own paths (the tail follows the head by LIFE seconds at the same speed, so a
 * child's tail leaves when its parent's tail reaches the fork) while the same group grows again from its fork, each group on its
 * own period, so never all at once and nothing pops. A {@link Cast} varies the tree: which of the two traced trees, mirrored or not,
 * scaled, shifted in time, and other speeds. Client-free; tools/vfx-lab runs this code.
 */
public final class FxInkTraceScene {
    public static final double BENCH_SECONDS = 15, START = 1.0;
    public static final String INK = "INK_TRACE", SHEET = "INK_TRACE_SHEET", HALO = "INK_TREE_HALO";
    /** The share of a stroke's mesh the brush paints (FxLook.INK_TRACE mainShare): mesh half-width = traced half-width / this. */
    public static final double MAIN_SHARE = .7;
    private static final double SPEED = 2.2, LIFE = 5.0, RING = 1.0, HALO_MIN = .02, TAPER = .3, DEFAULT_SWAY = .13, HALO_WIDTH = 2.0, HALO_BASE = .07, HALO_CAP = .2;

    /** One stroke to draw: points (x, y, z) in the tree frame, one mesh half-width per point, and the stroke state. */
    public record Piece(String look, double[][] points, double[] halves, double half, double seed, double from, double drawn, double alpha) {}

    /** A variation of the tree: tree 0 = panel 13, 1 = panel 14; mirrored, scaled, shifted in time; the seed changes speeds and periods. */
    public record Cast(int tree, boolean mirror, double scale, double shift, int seed, double sway, int maxDepth) {
        /** A cast with the default sway (the turbulence's amplitude in blocks) and every branch. */
        public Cast(int tree, boolean mirror, double scale, double shift, int seed) { this(tree, mirror, scale, shift, seed, DEFAULT_SWAY, 99); }

        public static final Cast IDENTITY = new Cast(0, false, 1, 0, 0);
        /** Panel 14 as traced. */
        public static final Cast SHEET = new Cast(1, false, 1, 0, 0);

        /** The n-th cast: n = 0 is the tree as traced; the others differ in every respect that can differ. */
        public static Cast of(int n) {
            if (n == 0) return IDENTITY;
            return new Cast(n % 3 == 2 ? 1 : 0, FxInkTree.hash(n * 5L + 1) > .5, .88 + .27 * FxInkTree.hash(n * 5L + 2), .6 * FxInkTree.hash(n * 5L + 3), n);
        }
    }

    private record Plan(FxInkTrace tree, double[] speed, double[] ts, int[] group, double[] life, double[] period) {}

    private static final Map<Integer, FxInkTrace> TREES = new HashMap<>();
    private static final Map<Long, Plan> PLANS = new HashMap<>();

    private FxInkTraceScene() {}

    static synchronized FxInkTrace tree(int which) {
        return TREES.computeIfAbsent(which, k -> FxInkTrace.load(k == 0 ? "inktree_13" : "inktree_14"));
    }

    private static synchronized Plan plan(Cast c) {
        return PLANS.computeIfAbsent(c.tree() * 100000L + c.seed(), k -> {
            FxInkTrace tr = tree(c.tree());
            double[] speed = tr.speeds(SPEED, c.seed()), ts = tr.startTimes(speed);
            int[] group = tr.groups(RING);
            double[] life = new double[tr.branches.size()], period = new double[life.length];
            for (int i = 0; i < life.length; i++) {
                life[i] = LIFE * (.8 + .5 * FxInkTree.hash(c.seed() * 977L + i * 13L + 4));
                period[i] = life[i] * (.85 + .3 * FxInkTree.hash(c.seed() * 977L + i * 13L + 5)) + .6;
            }
            return new Plan(tr, speed, ts, group, life, period);
        });
    }

    /** The whole tree as traced: every branch fully drawn, no turbulence, no renewal (the lab compares this with the layer). */
    public static List<Piece> rest(Cast c, boolean halo) {
        Plan p = plan(c);
        List<Piece> out = new ArrayList<>();
        for (FxInkTrace.Branch b : p.tree.branches) if (b.depth <= c.maxDepth()) emit(out, p, c, b, 1e9, -1e9, 0, false, halo, 0);
        return out;
    }

    /** Every stroke of the tree at {@code t} bench seconds. */
    public static List<Piece> pieces(double t, Cast c, boolean halo) {
        Plan p = plan(c);
        double lt = t - START - c.shift();
        List<Piece> out = new ArrayList<>();
        if (lt <= 0) return out;
        for (FxInkTrace.Branch b : p.tree.branches) {
            if (b.depth > c.maxDepth()) continue;
            int g = p.group[b.id];
            if (g < 0) {
                double age = lt - p.ts[b.id];
                emit(out, p, c, b, age * p.speed[b.id], -1e9, t, true, halo, 0);
                continue;
            }
            double t0 = p.ts[g], period = p.period[g];
            int newest = (int) Math.floor((lt - t0) / period);
            for (int gen = Math.max(0, newest - 2); gen <= newest; gen++) {
                double age = lt - gen * period - p.ts[b.id];
                double head = age * p.speed[b.id], tail = (age - p.life[g]) * p.speed[b.id] - TAPER;
                emit(out, p, c, b, head, tail, t, true, halo, gen);
            }
        }
        return out;
    }

    /**
     * One branch. {@code head} and {@code tail} are where the drawn part begins and ends, in blocks along the branch (head beyond the
     * end = fully grown; tail below zero = nothing has slid off). A growing head and a sliding tail are tapered over {@link #TAPER}
     * blocks in the geometry itself, so a fully drawn branch keeps the traced width right up to its forks.
     */
    private static void emit(List<Piece> out, Plan p, Cast c, FxInkTrace.Branch b, double head, double tail, double t, boolean live, boolean halo, int gen) {
        double len = b.length;
        head = Math.min(head, len + TAPER);
        double drawn = Math.min(1, head / len), from = Math.clamp(tail / len, 0, 1);
        if (head <= 0 || from >= drawn || b.size() < 2) return;
        int n = b.size();
        double[][] pts = new double[n][];
        double[] halves = new double[n];
        double mean = 0, sign = c.mirror() ? -1 : 1;
        for (int i = 0; i < n; i++) {
            double x = b.x[i], y = b.y[i];
            if (live) {
                double[] d = bend(x, y, b.start + b.along[i], c.seed(), t, c.sway());
                x = d[0]; y = d[1];
            }
            pts[i] = new double[]{sign * x * c.scale(), y * c.scale(), 0};
            double w = 1;
            if (head < len + TAPER) w *= FxInkTree.smoothstep(0, TAPER, head - b.along[i]);
            if (tail > -TAPER) w *= FxInkTree.smoothstep(0, TAPER, b.along[i] - tail);
            halves[i] = Math.max(b.h[i] * w, .0015) * c.scale() / MAIN_SHARE;
            mean += halves[i] / n;
        }
        double seed = FxInkTree.hash(c.seed() * 977L + b.id * 31L + gen * 7L + 3);
        out.add(new Piece(c.tree() == 0 ? INK : SHEET, pts, halves, mean, seed, from, drawn, 1));
        if (halo && b.maxHalf >= HALO_MIN) {
            // the light is a smoother, sparser path (every third point) and never wider than HALO_CAP: a wide ribbon on a tight bend folds into rays
            int m = (n + 2) / 3 + 1;
            double[][] hp = new double[m][];
            double[] hh = new double[m];
            for (int k = 0; k < m; k++) {
                int i = Math.min(n - 1, k * 3);
                hp[k] = pts[i];
                hh[k] = Math.min(halves[i] * MAIN_SHARE * HALO_WIDTH + HALO_BASE, HALO_CAP) / .95;
            }
            out.add(new Piece(HALO, hp, hh, HALO_CAP, seed, from, drawn, Math.exp(-b.start / 7)));
        }
    }

    static double[] bend(double x, double y, double distance, int seed, double t) { return bend(x, y, distance, seed, t, DEFAULT_SWAY); }

    /** Slow outward wave of bending; zero at the root, growing with the distance along the tree. */
    static double[] bend(double x, double y, double distance, int seed, double t, double sway) {
        double amp = sway * FxInkTree.smoothstep(.4, 3.5, distance);
        double w = distance * .85 - t * 1.4 + seed * 2.3 + x * .6;
        return new double[]{x + amp * (Math.sin(w) + .45 * Math.sin(w * 2.3 + 1.7)), y + amp * .55 * Math.cos(w * 1.3 + .6)};
    }
}
