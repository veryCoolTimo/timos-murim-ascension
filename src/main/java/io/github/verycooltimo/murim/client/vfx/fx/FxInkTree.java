package io.github.verycooltimo.murim.client.vfx.fx;

import java.util.ArrayList;
import java.util.List;

/**
 * The black ink of reference panels 13 and 14 as one branching tree (the layer cut out of 13: plates/13-black-layer.png), round 4:
 * a real tree, not a fan of separate stems. Ten trunks leave one merged black root at different angles (the outer ones lean far out
 * and run along the ground before they rise, several start on the wrong side and cross behind their neighbours). Every trunk is one
 * smooth S-curved spine that FORKS two or three times on its way up; a fork is a narrow Y: the child leaves the spine tangentially
 * (it hugs it for a hand's breadth, then peels away) and is clearly thinner than the spine, and about half of the children fork
 * once more, so a trunk ends in about five tips and the tree in about fifty. After every fork the spine itself gets thinner. A limb
 * is drawn as a chain of strokes along its spine, each one running a little way into the next, so the width steps down at the forks
 * without a pinch. Three loops split a trunk into two halves that rejoin round an oval hole. The root is the trunk bases flowing
 * into each other with webbing between them, on an uneven puddle.
 * Pure geometry in a local frame (x right, y up from the floor, z towards the camera, blocks); no client classes, so it is
 * unit-tested and tools/vfx-lab runs the very same generator. {@link FxInkTreeScene} gives it time.
 */
public final class FxInkTree {
    /** The share of a stroke's mesh that the main band takes (FxLook.INK_TREE mainShare): visible half-width = mesh half * this * profile. */
    public static final double MAIN_SHARE = .34;
    /** The shader's head taper (FxLook.INK_TREE head): full width for the first 1 - HEAD of a stroke, then thinning to the tip. */
    static final double HEAD = .4;
    /** Smallest visible half-width of a hairline, blocks. */
    static final double MIN_VIS = .007;
    /** Step along a spine, blocks. */
    private static final double DS = .06;
    /** How far a child hugs its parent's spine before it peels away, blocks. */
    private static final double HUG = .15;
    /** A fork child is this much thinner than the spine at the fork; the spine continues this much thinner. */
    private static final double CHILD_W = .52, SPINE_W = .7;

    /** One stroke of the tree: a smooth path, its widest visible half-width, where it hangs on its parent. */
    public static final class Branch {
        public final int id, parent, depth, kind;
        /** parentU: where on the parent (0..1 of its length) this stroke begins. start: distance from the root of the whole tree at its first point. */
        public final double parentU, vis, start, length;
        /** Points in blocks, and the distance along the stroke at each. */
        public final double[] x, y, z, along;

        Branch(int id, int parent, double parentU, int depth, int kind, double vis, double start, double[] x, double[] y, double[] z, double[] along) {
            this.id = id; this.parent = parent; this.parentU = parentU; this.depth = depth; this.kind = kind; this.vis = vis;
            this.start = start; this.x = x; this.y = y; this.z = z; this.along = along; this.length = along[along.length - 1];
        }

        public int size() { return x.length; }

        /** Visible half-width at u (0..1) of this stroke: full for the first part, thinning to the tip (the shader's taper). */
        public double visAt(double u) {
            double w = smoothstep(0, HEAD, 1 - Math.clamp(u, 0, 1));
            return vis * Math.pow(w, .55);
        }

        int indexAt(double s) {
            int i = 0;
            while (i < along.length - 1 && along[i + 1] < s) i++;
            return i;
        }

        double lerp(double[] a, double s) {
            int i = Math.min(indexAt(s), along.length - 2);
            double span = along[i + 1] - along[i];
            double f = span < 1e-9 ? 0 : Math.clamp((s - along[i]) / span, 0, 1);
            return a[i] + (a[i + 1] - a[i]) * f;
        }

        double heading(double s) {
            int i = Math.min(indexAt(s), along.length - 2);
            return Math.atan2(y[i + 1] - y[i], x[i + 1] - x[i]);
        }
    }

    /**
     * A trunk: lean from the vertical in degrees (negative = left), where it leaves the root (x0, z), its length and its widest
     * visible half-width. Panel 13 at about 290 px to the block: the tree reaches the top (5.3 blocks) and both sides (1.65 blocks).
     */
    public record Trunk(double lean, double x0, double z, double length, double vis) {}

    /**
     * The trunks. They leave one merged root in no particular order: the far-left one runs almost flat along the ground first, and
     * trunks 1, 7 and 5 start on the other side of their lean, so they cross behind the trunks between.
     */
    public static final Trunk[] TRUNKS = {
            new Trunk(-84, -0.16, .00, 4.4, 0.056),
            new Trunk(-60, 0.22, .20, 5.0, 0.068),
            new Trunk(-40, -0.30, .08, 6.0, 0.100),
            new Trunk(-20, 0.06, .30, 5.4, 0.064),
            new Trunk(-4, -0.12, .12, 5.8, 0.092),
            new Trunk(11, 0.30, .26, 5.2, 0.060),
            new Trunk(27, -0.05, .02, 4.8, 0.056),
            new Trunk(44, -0.36, .16, 4.8, 0.095),
            new Trunk(62, 0.16, .06, 4.0, 0.054),
            new Trunk(84, 0.34, .22, 3.8, 0.050),
    };

    /** A loop: the trunk splits at distance d1 along its spine, the two halves rejoin at d2, their centre lines {@code width} apart at most. */
    record LoopDef(int trunk, double d1, double d2, double width) {}

    /** Three loops in the lower half of the tree: two left of the middle (a medium one low on the far-left arm, a big round one higher), one small near the root. */
    static final LoopDef[] LOOPS = {
            new LoopDef(1, 1.15, 2.0, .50),
            new LoopDef(3, 2.1, 3.15, .68),
            new LoopDef(4, .6, 1.2, .36),
    };

    private FxInkTree() {}

    // ---- spines --------------------------------------------------------------------------------------------------------------------

    /** A smooth path sampled every DS: position, heading and distance. */
    static final class Spine {
        final double[] x, y, z, phi, d;
        Spine(int n) { x = new double[n]; y = new double[n]; z = new double[n]; phi = new double[n]; d = new double[n]; }
        int n() { return x.length; }
        int at(double dist) { return Math.clamp((int) Math.round(dist / DS), 0, x.length - 1); }
    }

    /**
     * Heading integration: the heading relaxes towards {@code tgt} and is pushed by two slow sines (the S-curves). The S-curves fade in
     * over the first .4 block so that a child leaves tangentially. {@code hook} curls the last third of a block (the dry brush tip).
     */
    static Spine integrate(double x0, double y0, double z0, double phi0, double tgt, double relax, double length, double amp, long seed, int variant, double hook) {
        return integrate(x0, y0, z0, phi0, tgt, relax, tgt, relax, 0, length, amp, seed, variant, hook);
    }

    /** The same with a change of aim: the first {@code switchAt} blocks aim at {@code tgt} (a child peels away from its parent), then at {@code tgt2}. */
    static Spine integrate(double x0, double y0, double z0, double phi0, double tgt, double relax, double tgt2, double relax2, double switchAt, double length, double amp, long seed, int variant, double hook) {
        int n = (int) Math.ceil(length / DS) + 1;
        Spine sp = new Spine(n);
        double k1 = (1.35 + .6 * hash(seed + 1)) * (amp > .7 ? 1.5 : 1), k2 = 3.4 + hash(seed + 2), p1 = 6.283 * hash(seed + 3) + variant * .15, p2 = 6.283 * hash(seed + 4) + variant * .2;
        double x = x0, y = y0, phi = phi0;
        for (int i = 0; i < n; i++) {
            double s = i * DS;
            sp.x[i] = x; sp.y[i] = y; sp.phi[i] = phi; sp.d[i] = s;
            sp.z[i] = z0 + (noise(s * .6, seed) - .5) * .3 * Math.min(1, s / .6);
            double c = (amp * Math.sin(k1 * s + p1) + .5 * amp * Math.sin(k2 * s + p2)) * smoothstep(0, .4, s);
            double sw = smoothstep(switchAt * .6, switchAt * 1.2 + 1e-6, s);
            double turn = ((relax + (relax2 - relax) * sw) * ((tgt + (tgt2 - tgt) * sw) - phi) + c) * DS;
            if (hook != 0) turn += hook * 3.4 * DS * smoothstep(length - .34, length, s);
            phi = Math.clamp(phi + turn, .1, Math.PI - .1);
            x += Math.cos(phi) * DS; y += Math.sin(phi) * DS;
        }
        return sp;
    }

    private static double phi0(Trunk t) { return Math.clamp(Math.PI / 2 - Math.toRadians(t.lean), .12, Math.PI - .12); }

    private static double tgt(Trunk t) { return Math.PI / 2 - Math.toRadians(t.lean * .62); }

    /** The spine of a trunk, the same for every growth but for a small phase jitter (the next growth follows nearly the same way). */
    static Spine trunkSpine(int trunk, int variant) {
        Trunk t = TRUNKS[trunk];
        return integrate(t.x0, .07, t.z, phi0(t), tgt(t), .85, t.length, .6, 1000L * (trunk + 1), variant, t.lean < 0 ? 1 : -1);
    }

    // ---- limbs ---------------------------------------------------------------------------------------------------------------------

    /** One trunk with everything that grows from it. {@code variant} jitters the forks a little. */
    public static List<Branch> group(int trunk, int variant) {
        Trunk t = TRUNKS[trunk];
        List<Branch> out = new ArrayList<>();
        Spine sp = trunkSpine(trunk, variant);
        limb(out, null, 0, sp, t.length, t.vis, 0, tgt(t), 1000L * (trunk + 1), variant, trunk);
        return out;
    }

    private record Ev(double d, int type, int side, double a, double b, double c) {}

    /** The spine of a limb cut into strokes at its forks (and loops), and the children that leave at the forks. */
    private static void limb(List<Branch> out, Branch parent, double dist0, Spine sp, double length, double vis0, int depth, double aim, long seed, int variant, int trunk) {
        // loop: the spine bulges one way, a second strand the other way
        LoopDef loop = null;
        if (depth == 0) for (LoopDef l : LOOPS) if (l.trunk == trunk) loop = l;
        Spine alt = null;
        if (loop != null) {
            alt = new Spine(sp.n());
            for (int i = 0; i < sp.n(); i++) {
                double d = sp.d[i], f = (d - loop.d1) / (loop.d2 - loop.d1);
                double off = f > 0 && f < 1 ? loop.width / 2 * Math.pow(Math.sin(Math.PI * f), .55) : 0;
                double nx = -Math.sin(sp.phi[i]), ny = Math.cos(sp.phi[i]);
                alt.x[i] = sp.x[i] - nx * off; alt.y[i] = sp.y[i] - ny * off; alt.z[i] = sp.z[i]; alt.phi[i] = sp.phi[i]; alt.d[i] = d;
                sp.x[i] += nx * off; sp.y[i] += ny * off;
            }
        }
        // events along the spine, in order
        List<Ev> ev = new ArrayList<>();
        int nf = forkCount(depth, length, seed);
        double[] fr = nf == 1 ? new double[]{.5 + .12 * (hash(seed + 5) - .5)} : nf == 2 ? new double[]{.065, .34} : nf == 3 ? new double[]{.06, .2, .46} : new double[]{.09, .24, .42, .64};
        int first = hash(seed + 6) < .5 ? -1 : 1;
        double[] fds = new double[fr.length];
        for (int k = 0; k < nf; k++) fds[k] = length * (fr[k] + jitter(seed + 20 + k, variant, .05));
        if (loop != null) {
            // a trunk with a loop forks before it and after it, never inside
            nf = 3;
            fds = new double[]{.06 * length, loop.d1 * .45, loop.d2 + .6 + .1 * hash(seed + 8), loop.d2 + 1.5};
        }
        int placed = 0;
        for (int k = 0; k < fds.length && placed < nf; k++) {
            double d = fds[k];
            if (d < .28 || length - d < .5) continue;
            if (loop != null && d > loop.d1 - .55 && d < loop.d2 + .55) continue;
            if (!ev.isEmpty() && d - ev.getLast().d < .35) continue;
            ev.add(new Ev(d, 0, depth == 0 ? first * (placed % 2 == 0 ? 1 : -1) : (hash(seed + 30 + k) < .5 ? -1 : 1), 0, 0, 0));
            placed++;
        }
        if (loop != null) ev.add(new Ev(loop.d1, 1, 0, loop.d2, loop.width, 0));
        ev.sort((a, b) -> Double.compare(a.d, b.d));
        // walk them
        double w = vis0, segStart = 0;
        Branch prev = parent;
        for (int k = 0; k <= ev.size(); k++) {
            Ev e = k < ev.size() ? ev.get(k) : null;
            if (e == null) {
                // the last stroke runs to the tip
                emit(out, prev, dist0, sp, segStart, length, w, depth, seed + 90 + k);
                break;
            }
            if (e.type == 0) {
                double wNext = Math.max(MIN_VIS, w * SPINE_W);
                // the stroke runs on into the next one for as long as its own taper takes, so the width steps down without a pinch or a blunt step
                Branch seg = emit(out, prev, dist0, sp, segStart, e.d + (e.d - segStart) * HEAD / (1 - HEAD), w, depth, seed + 90 + k);
                // the child: leaves the spine a hand's breadth before the fork, tangentially, a narrow Y
                int ci = sp.at(e.d - HUG), fi = sp.at(e.d);
                double rem = length - e.d, h = hash(seed + 40 + k);
                double cl = Math.max(.55, rem * (depth == 0 ? .6 + .4 * h : .45 + .3 * h) * (1 + jitter(seed + 50 + k, variant, .12)));
                // a narrow Y: the child turns away at once (about 35 degrees), then curves back towards the way the whole limb is heading,
                // like the arm of a candelabra, so the branches of a trunk rise side by side with lens-shaped gaps between them
                double away = sp.phi[fi] + e.side * (.65 + .3 * hash(seed + 60 + k)), back = aim + e.side * .5;
                Spine child = integrate(sp.x[ci], sp.y[ci], sp.z[ci], sp.phi[ci] + e.side * .15, away, 2.4, back, .55, .7, cl, .95, seed * 31 + 17 * k + 3, variant,
                        hash(seed + 70 + k) < .5 ? Math.signum(Math.cos(back)) : 0);
                limb(out, seg, dist0 + e.d - HUG, child, cl, Math.max(MIN_VIS, w * (depth == 0 ? CHILD_W : CHILD_W * .85)), depth + 1, aim, seed * 31 + 17 * k + 3, variant, trunk);
                w = wNext; segStart = e.d; prev = seg;
            } else {
                double d1 = e.d, d2 = e.a, wStrand = Math.max(MIN_VIS, w * .5);
                Branch seg = emit(out, prev, dist0, sp, segStart, d1 + .12, w, depth, seed + 90 + k);
                Branch a = emit(out, seg, dist0, sp, d1 - .1, d2 + .3, wStrand, depth, seed + 91 + k);
                emit(out, seg, dist0, alt, d1 - .1, d2 + .3, wStrand * .92, depth, seed + 92 + k);
                w = w * .9; segStart = d2; prev = a;
            }
        }
    }

    private static int forkCount(int depth, double length, long seed) {
        double h = hash(seed + 7);
        return switch (depth) {
            case 0 -> h < .45 ? 2 : 3;
            case 1 -> length > 2.6 && h < .12 ? 2 : length > 1.2 && h < .72 ? 1 : 0;
            case 2 -> length > 1.1 && h < .2 ? 1 : 0;
            default -> 0;
        };
    }

    /** One stroke of a limb from distance d0 to d1 along the spine; its start hangs on {@code parent}. */
    private static Branch emit(List<Branch> out, Branch parent, double dist0, Spine sp, double d0, double d1, double vis, int depth, long seed) {
        int i0 = Math.max(0, (int) Math.floor(d0 / DS)), i1 = Math.min(sp.n() - 1, (int) Math.ceil(d1 / DS));
        if (i1 - i0 < 2) i0 = Math.max(0, i1 - 2);
        int n = i1 - i0 + 1;
        double[] x = new double[n], y = new double[n], z = new double[n], along = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = sp.x[i0 + i]; y[i] = sp.y[i0 + i]; z[i] = sp.z[i0 + i];
            if (i > 0) along[i] = along[i - 1] + Math.sqrt(sq(x[i] - x[i - 1]) + sq(y[i] - y[i - 1]) + sq(z[i] - z[i - 1]));
        }
        double start = dist0 + sp.d[i0];
        double pu = parent == null || parent.length < 1e-9 ? 0 : Math.clamp((start - parent.start) / parent.length, 0, 1);
        Branch b = new Branch(out.size(), parent == null ? -1 : parent.id, pu, depth, 0, Math.max(MIN_VIS, vis), start, x, y, z, along);
        out.add(b);
        return b;
    }

    private static double sq(double v) { return v * v; }

    private static double jitter(long seed, int variant, double amp) {
        return amp * (2 * hash(seed * 7 + variant * 13L) - 1);
    }

    // ---- the root mass -------------------------------------------------------------------------------------------------------------

    /**
     * The webs and the puddle of the black mass on the floor, grown once and kept (kind 1): fat arches between the neighbouring trunk
     * bases, so that the bases flow into one another like the base of fingers (small holes stay where the arches do not meet),
     * then an uneven puddle of lumps with no straight edge.
     */
    public static final List<Branch> FLOOR = floor();

    private static List<Branch> floor() {
        List<Branch> out = new ArrayList<>();
        int nt = TRUNKS.length;
        Spine[] sps = new Spine[nt];
        Integer[] order = new Integer[nt];
        for (int i = 0; i < nt; i++) { sps[i] = trunkSpine(i, 0); order[i] = i; }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(sps[a].x[sps[a].at(.5)], sps[b].x[sps[b].at(.5)]));
        // webs: four slanting arches between every two neighbouring trunks, some rising to the right, some to the left, at different
        // heights; where they cross each other and the trunks they leave small lens-shaped holes
        double[][] pairs = {{.08, .4}, {.45, .12}, {.7, 1.0}};
        for (int k = 0; k + 1 < nt; k++) {
            Spine a = sps[order[k]], b = sps[order[k + 1]];
            // only the trunks that stand close together at the root are webbed; the far arms that run along the ground are not
            if (Math.abs(a.x[a.at(.5)] - b.x[b.at(.5)]) > .55 || Math.abs(a.x[a.at(.1)] - b.x[b.at(.1)]) > .5) continue;
            for (int r = 0; r < pairs.length; r++) {
                double da = pairs[r][0] * (.8 + .4 * hash(4000 + k * 4 + r)), db = pairs[r][1] * (.8 + .4 * hash(4200 + k * 4 + r));
                web(out, a.x[a.at(da)], a.y[a.at(da)], b.x[b.at(db)], b.y[b.at(db)], .02 + .014 * hash(4400 + 4 * k + r), 4500 + 4 * k + r);
            }
        }
        // the stem: fat short strokes rise through the trunk bases at slightly different angles and heights, so the bases flow into one
        // another like the skin at the base of fingers; the gaps that stay between the stems are the small holes of the mass
        double[] sx = {-.46, -.3, -.15, .0, .14, .3, .46};
        for (int i = 0; i < sx.length; i++) {
            int n = 14;
            double[] x = new double[n], y = new double[n], z = new double[n], along = new double[n];
            double top = .5 + .4 * hash(2000 + i), lean = (sx[i] < 0 ? -1 : 1) * (.15 + .35 * hash(2100 + i));
            for (int k = 0; k < n; k++) {
                double f = k / (double) (n - 1);
                x[k] = sx[i] + lean * f * f * top + (noise(k * .2, 2300 + i) - .5) * .06;
                y[k] = .05 + (top - .05) * f;
                z[k] = .08 + .1 * hash(2300 + i);
                if (k > 0) along[k] = along[k - 1] + Math.hypot(x[k] - x[k - 1], y[k] - y[k - 1]);
            }
            out.add(new Branch(out.size(), -1, 0, 0, 1, .045 + .03 * hash(2400 + i), 0, x, y, z, along));
        }
        // the puddle: many short rounded lumps along the ground at uneven heights (so the lower edge scallops and has no straight run);
        // thick near the axis, thin and long far out
        int count = 34;
        for (int i = 0; i < count; i++) {
            double r = Math.pow(hash(900 + i * 3L), 1.3), near = 1 - r, side = hash(906 + i * 3L) < .62 ? -1 : 1;
            double x0 = side * (1.3 * r) - .1 + .1 * hash(907 + i), y0 = .04 + .15 * hash(901 + i * 3L);
            double heading = side > 0 ? .05 + .3 * hash(903 + i * 5L) : Math.PI - .05 - .3 * hash(903 + i * 5L);
            double len = .16 + .22 * near + .1 * hash(904 + i * 3L), vis = .03 + .045 * near + .012 * hash(905 + i * 3L);
            int n = (int) Math.ceil(len / .05) + 1;
            double[] x = new double[n], y = new double[n], z = new double[n], along = new double[n];
            x[0] = x0; y[0] = y0; z[0] = .1 + .15 * hash(902 + i * 3L);
            double phi = heading;
            for (int k = 1; k < n; k++) {
                phi += (noise(k * .09, 3000 + i) - .5) * .3;
                x[k] = x[k - 1] + Math.cos(phi) * .05; y[k] = Math.clamp(y[k - 1] + Math.sin(phi) * .05, .03, .34); z[k] = z[0];
                along[k] = along[k - 1] + Math.hypot(x[k] - x[k - 1], y[k] - y[k - 1]);
            }
            out.add(new Branch(out.size(), -1, 0, 0, 1, vis, 0, x, y, z, along));
        }
        return out;
    }

    /** A fat arch from (ax, ay) to (bx, by), sagging a little, as one root stroke. */
    private static void web(List<Branch> out, double ax, double ay, double bx, double by, double vis, long seed) {
        int n = 14;
        double[] x = new double[n], y = new double[n], z = new double[n], along = new double[n];
        double len = Math.hypot(bx - ax, by - ay), nx = -(by - ay) / Math.max(len, 1e-6), ny = (bx - ax) / Math.max(len, 1e-6);
        double bow = (hash(seed) - .5) * .5 * len;
        for (int k = 0; k < n; k++) {
            double f = k / (double) (n - 1), arch = Math.sin(Math.PI * f) * bow;
            x[k] = ax + (bx - ax) * f + nx * arch; y[k] = ay + (by - ay) * f + ny * arch; z[k] = .1 + .1 * hash(seed + 1);
            if (k > 0) along[k] = along[k - 1] + Math.hypot(x[k] - x[k - 1], y[k] - y[k - 1]);
        }
        out.add(new Branch(out.size(), -1, 0, 0, 1, vis, 0, x, y, z, along));
    }

    static double smoothstep(double a, double b, double v) {
        double t = Math.clamp((v - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** A hash in 0..1 of an integer. */
    static double hash(long n) {
        n *= 0x9E3779B97F4A7C15L; n ^= n >>> 29; n *= 0xBF58476D1CE4E5B9L; n ^= n >>> 32;
        return (n >>> 11) * (1.0 / (1L << 53));
    }

    /** Smooth value noise in 0..1. */
    static double noise(double v, long seed) {
        double i = Math.floor(v), f = v - i;
        f = f * f * (3 - 2 * f);
        long k = seed * 7919L + (long) i;
        return hash(k) * (1 - f) + hash(k + 1) * f;
    }
}
