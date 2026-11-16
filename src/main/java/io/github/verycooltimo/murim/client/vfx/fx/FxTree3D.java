package io.github.verycooltimo.murim.client.vfx.fx;

import java.util.ArrayList;
import java.util.List;

/**
 * ONE aura of lines in 3D, made by wrapping the traced drawings of panels 13 and 14 (FxInkTrace) onto flaring shells round the body's axis
 * (FxShellWrap): the drawing is not regrown, it is bent round the player, and every branch swings off its shell in the radial direction, so each
 * branch is a space curve. Layers: the black ink on the inner shell (tree 13 centred behind the player, tree 14 lower and thinned in front),
 * violet ribbons on a slightly larger shell (the other trees, mirrored, the swing in the opposite phase), blue ribbons on a larger shell leaning
 * outward. A child starts exactly on its parent's wrapped point (it inherits the swing at the fork). The schedule (growth outward from the root
 * along the wrapped paths, clots, renewal by sliding off along the branch) is FxInkTraceScene's. The paths never move.
 * Client-free: tools/vfx-lab and the unit test run this code. Frame: x to the body's right, y up from the feet, z towards the camera (blocks);
 * the body's axis is x = z = 0.
 */
public final class FxTree3D {
    public static final double SPEED = 2.2, LIFE = 3.7, TAPER = .3, MAIN_SHARE = .7, RING = .45;
    /** The families of lines: the black ink, the violet ribbons, the blue ribbons. */
    public static final int INK = 0, VIOLET = 1, BLUE = 2, FAMILIES = 3;
    /** The ribbons are slimmer than the bold ink (their share of the traced width). */
    public static final double[] WIDTH = {1, .6, .6};
    /** In front, within this many degrees of the camera side, the lower drawing stays under {@link #FRONT_TOP} blocks so the figure's upper body and head stay free. */
    public static final double FRONT_HALF_DEG = 35, FRONT_TOP = .6;

    /** One layer: a traced drawing, the shell it is wrapped onto, the colour family, which branches are kept (traced half-width), the width factor, whether the front is thinned, the start delay. */
    public record Layer(String name, String src, FxShellWrap.Shell shell, int family, double minHalf, double width, boolean thinFront, double delay) {}

    public static final double R0 = FxShellWrap.R0;
    public static final Layer[] LAYERS = {
            new Layer("ink13", "inktree_13", new FxShellWrap.Shell(180, R0, FxShellWrap.K, -1, 1.0, 1.0, 1, 0), INK, .055, .8, false, 0),
            new Layer("ink14", "inktree_14", new FxShellWrap.Shell(0, R0, FxShellWrap.K, 1, 1.6, 1.0, 1, 0), INK, .012, 1.9, true, .5),
            new Layer("violet14", "inktree_14", new FxShellWrap.Shell(180, R0 + .35, FxShellWrap.K, 1, 2.6, 1.5, 1, Math.PI), VIOLET, .012, 3.4, false, 1.0),
            new Layer("violet13", "inktree_13", new FxShellWrap.Shell(0, R0 + .35, FxShellWrap.K, -1, 1.0, .6, 1, Math.PI), VIOLET, .08, 1.0, true, 1.2),
            new Layer("blue13", "inktree_13", new FxShellWrap.Shell(180, R0 + .9, .4, 1, .9, 1.0, 1.3, Math.PI / 2), BLUE, .09, 1.2, false, 1.5)};

    /** One branch in space. {@code p} holds x, y, z per point; {@code h} the half-width per point (scaled). */
    public static final class Branch {
        public final int id, parent, depth, src, family, layer;
        public final float[] p, h, along;
        public final double length, meanHalf, forkAlong, start;
        public final int forkIndex;
        /** The radial swing at each point (blocks), for the maths test. */
        public final float[] swing;
        /** The path smoothed (six passes of 1-2-1, ends fixed): the light's centre line, so a zigzag of the trace does not fold the halo. */
        public final float[] smooth;
        /** Round 4: a branch the author wanted fewer of stays in the tree (its children grow from it) but is not drawn. */
        public boolean hidden;

        Branch(int id, int parent, int depth, int src, int family, int layer, float[] p, float[] h, float[] swing, int forkIndex, double forkAlong, double start) {
            this.id = id; this.parent = parent; this.depth = depth; this.src = src; this.family = family; this.layer = layer;
            this.smooth = smoothPath(p);
            this.p = p; this.h = h; this.swing = swing; this.forkIndex = forkIndex; this.forkAlong = forkAlong; this.start = start;
            int n = h.length;
            along = new float[n];
            for (int i = 1; i < n; i++) {
                double dx = p[3 * i] - p[3 * i - 3], dy = p[3 * i + 1] - p[3 * i - 2], dz = p[3 * i + 2] - p[3 * i - 1];
                along[i] = along[i - 1] + (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            length = along[n - 1];
            double m = 0;
            for (float v : h) m += v;
            meanHalf = m / n;
        }

        public int size() { return h.length; }

        static float[] smoothPath(float[] p) {
            float[] a = p.clone(), b = p.clone();
            int n = p.length / 3;
            for (int pass = 0; pass < 6; pass++) {
                for (int i = 1; i < n - 1; i++) for (int k = 0; k < 3; k++) b[3 * i + k] = (a[3 * i - 3 + k] + 2 * a[3 * i + k] + a[3 * i + 3 + k]) / 4;
                float[] t = a; a = b; b = t;
            }
            return a;
        }
    }

    public final List<Branch> branches = new ArrayList<>();
    final double[] speed, ts, life, period, drift, spin;
    final int[] group;
    private static FxTree3D instance;
    /** Thin branches (traced mean half-width below this, blocks) are skipped: the quality governor raises it when frames get slow. */
    public volatile double detailCut;

    /** The tree of the bench (built once, never changes; built in the bench's empty first second, see FxCultivationBench). */
    public static synchronized FxTree3D get() {
        if (instance == null) instance = new FxTree3D(5);
        return instance;
    }

    public FxTree3D(long seed) {
        for (int l = 0; l < LAYERS.length; l++) wrapLayer(l, FxInkTrace.load(LAYERS[l].src()), seed);
        for (Branch b : branches) b.hidden = shouldHide(b) || (b.parent >= 0 && branches.get(b.parent).hidden && b.family != INK && top(b) < 1.4);   // a coloured child of a hidden low branch would hang in the air
        int n = branches.size();
        speed = new double[n]; ts = new double[n]; life = new double[n]; period = new double[n]; group = new int[n];
        drift = new double[n]; spin = new double[n];
        for (Branch b : branches) {
            speed[b.id] = SPEED * (.85 + .3 * FxInkTree.hash(seed * 131L + b.id * 7L));
            int pg = b.parent >= 0 ? group[b.parent] : -1;
            group[b.id] = pg >= 0 ? pg : (b.start >= RING ? b.id : -1);
            // round 6: every renewing group starts its cycle a little apart from the others, so there is no common wave
            double jit = group[b.id] == b.id ? 1.6 * FxInkTree.hash(seed * 313L + b.id * 17L + 9) : 0;
            ts[b.id] = (b.parent < 0 ? LAYERS[b.layer].delay() : ts[b.parent] + b.forkAlong / speed[b.parent]) + jit;
        }
        for (int i = 0; i < n; i++) {
            life[i] = LIFE * (.75 + .6 * FxInkTree.hash(seed * 977L + i * 13L + 4));
            // the next generation starts while the last one is still sliding off: the stream never has a gap
            period[i] = life[i] * (.62 + .3 * FxInkTree.hash(seed * 977L + i * 13L + 5)) + .5;
            drift[i] = .2 + .24 * FxInkTree.hash(seed * 551L + i * 19L + 1);                       // blocks per second, outward
            spin[i] = (FxInkTree.hash(seed * 551L + i * 19L + 2) - .5) * .24;                      // radians per second round the axis
        }
    }

    // ---------------------------------------------------------------- flow (round 6)

    /** The age (seconds since the piece began to grow), the group and the generation of the piece being visited: set by {@link #visit} just before the callback. */
    private double curAge, now;
    /** The bench second of the visit in progress (for the pulses). */
    public double now() { return now; }
    private int curGroup;
    private static final double DRIFT_CAP = 1.4;
    private static float[] flowBuf = new float[768];

    /** Parabolic sine, good to about 1 percent: the flow runs for thousands of points per frame and needs no exact sines. */
    static double fsin(double x) {
        x -= 6.283185307179586 * Math.floor(x / 6.283185307179586 + .5);
        double y = 1.2732395447351628 * x - .4052847345693511 * x * Math.abs(x);
        return .225 * (y * Math.abs(y) - y) + y;
    }

    /**
     * The path of a piece with the stream carried into it: a point that the head passed {@code a} seconds ago is carried outward (and up a little,
     * and slowly round the axis) by an amount that grows with its age. Every point has its own age, so the line is stretched along its way out, never
     * swung sideways; the same function of (age, group) at the fork makes a child leave its parent exactly. The first generation is the traced drawing;
     * each later one also grows along a nearly same, different path (a small offset that is fixed for the whole generation). A static trunk
     * (no group) is returned as it is. {@code src} is the stored path (the ink's or the light's), {@code last} the last point needed.
     */
    public float[] flow(Branch b, float[] src, int last, int gen) {
        int g = curGroup;
        if (g < 0 || gen < 0) return src;
        int cnt = Math.min(last + 1, b.size());
        if (flowBuf.length < 3 * b.size()) flowBuf = new float[3 * b.size()];
        double vd = drift[g], om = spin[g], spd = speed[b.id], gp = 6.28 * FxInkTree.hash(g * 71L + gen * 13L + 5), gq = 6.28 * FxInkTree.hash(g * 71L + gen * 13L + 6);
        double startDist = branches.get(g).start;
        for (int i = 0; i < cnt; i++) {
            double x = src[3 * i], y = src[3 * i + 1], z = src[3 * i + 2];
            double a = Math.max(0, curAge - b.along[i] / spd);
            double r = Math.max(.3, Math.hypot(x, z)), k = FxInkTree.smoothstep(.12, 1.3, y);   // the low part, along the floor, hardly moves
            double u = vd * a * k / DRIFT_CAP, out = DRIFT_CAP * u / (1 + u);          // the drift slows down: a soft ceiling, never a stray strap
            double th = om * a, c = 1 - th * th / 2, sn = th - th * th * th / 6;
            double rx = x * c - z * sn, rz = z * c + x * sn;
            x = rx + x / r * out; z = rz + z / r * out; y += out * .3;
            if (gen > 0) {
                double dist = b.start + b.along[i], w = .16 * FxInkTree.smoothstep(0, .9, dist - startDist);
                x += w * fsin(1.9 * y + gp + .8 * z);
                y += w * .6 * fsin(2.3 * x + gq);
                z += w * fsin(1.7 * y + gq + .7 * x);
            }
            flowBuf[3 * i] = (float) x; flowBuf[3 * i + 1] = (float) y; flowBuf[3 * i + 2] = (float) z;
        }
        for (int i = cnt; i < b.size(); i++) { flowBuf[3 * i] = src[3 * i]; flowBuf[3 * i + 1] = src[3 * i + 1]; flowBuf[3 * i + 2] = src[3 * i + 2]; }
        return flowBuf;
    }

    /** Thickness pulses that run outward along a branch: 1 +- 0.3, the phase falls with time so the swellings travel away from the root. Continuous across forks (tree distance). */
    public static double pulse(Branch b, int i, double t) {
        double dist = b.start + b.along[i];
        return 1 + .3 * fsin(6.283 * (dist / 1.5 - t * .45) + b.family * 1.3);
    }

    // ---------------------------------------------------------------- pruning (round 4)

    /**
     * «A little fewer strips» (author, 14.11): about a quarter of the ink's weight goes, first the thick flat black ribbons at the root,
     * the clot of the front drawing that hid the lower body, and the over-wide bars in the upper right. The branches are only hidden, never
     * removed, so every child still grows from its parent's point. The shares are checked by FxTree3DTest.
     */
    private static double top(Branch b) {
        double top = 0;
        for (int i = 0; i < b.size(); i++) top = Math.max(top, b.p[3 * i + 1]);
        return top;
    }

    static boolean shouldHide(Branch b) {
        if (b.family == VIOLET && top(b) < 1.3) {                   // round 8: the long violet strap on the floor to the left (chain of layers 2 and 3), 2 blocks out
            double reach = 0, mx = 0;
            for (int i = 0; i < b.size(); i++) { reach = Math.max(reach, Math.hypot(b.p[3 * i], b.p[3 * i + 2])); mx += b.p[3 * i]; }
            if (reach > 1.4 && mx / b.size() < -.3) return true;
        }
        if (b.family == BLUE || (b.family == VIOLET && b.meanHalf >= .1)) {                                           // blue ribbons that only lie along the floor, 1.5 to 2.3 blocks out: they read as a loose strap
            double top = 0;
            for (int i = 0; i < b.size(); i++) top = Math.max(top, b.p[3 * i + 1]);
            double reach = 0;
            for (int i = 0; i < b.size(); i++) reach = Math.max(reach, Math.hypot(b.p[3 * i], b.p[3 * i + 2]));
            return top < .55 || (top < .9 && reach > 1.7);     // round 8: the long floor strap to the left, 3 blocks out, is such a branch too
        }
        if (b.family != INK) return false;
        double my = 0, mx = 0, mz = 0;
        int m = b.size();
        for (int i = 0; i < m; i++) { mx += b.p[3 * i]; my += b.p[3 * i + 1]; mz += b.p[3 * i + 2]; }
        mx /= m; my /= m; mz /= m;
        boolean root = my < 1.15 && b.meanHalf >= .05;                    // thick flat ribbons at the root
        boolean front = b.layer == 1 && (my < 1.2 && b.meanHalf >= .03 || b.meanHalf >= .055);  // the front drawing: the clot over the lower body
        boolean bar = my > 1.4 && mx > .3 && b.meanHalf >= .06;           // over-wide bars, upper right
        boolean lowFront = mz > .1 && my < 1.1 && b.meanHalf >= .028;       // thick strips lying in front of the lower body
        boolean lowRoot = my < .7 && b.meanHalf >= .035;   // heavy flat strips along the floor round the root
        return root || front || bar || lowFront || lowRoot;
    }

    /** Share of the ink family's drawn weight (length times width) that is hidden. */
    public double hiddenShare() {
        double all = 0, hid = 0;
        for (Branch b : branches) {
            if (b.family != INK) continue;
            double w = b.length * b.meanHalf;
            all += w;
            if (b.hidden) hid += w;
        }
        return all <= 0 ? 0 : hid / all;
    }

    // ---------------------------------------------------------------- wrapping

    private void wrapLayer(int li, FxInkTrace tr, long seed) {
        Layer layer = LAYERS[li];
        int total = tr.branches.size();
        boolean[] keep = new boolean[total];
        for (int i = total - 1; i >= 0; i--) {
            FxInkTrace.Branch b = tr.branches.get(i);
            if (b.maxHalf >= layer.minHalf()) keep[i] = true;
            if (keep[i] && b.parent >= 0) keep[b.parent] = true;
        }
        int[] newId = new int[total], kept = new int[total];
        java.util.Arrays.fill(newId, -1);
        double[][] swings = new double[total][];
        for (FxInkTrace.Branch b : tr.branches) {
            if (!keep[b.id]) continue;
            int n = b.size(), parentId = -1, forkIdx = 0;
            double startSwing = 0, forkAlong = 0, start = 0;
            double[] px = null;
            if (b.parent >= 0) {
                if (newId[b.parent] < 0) continue;
                FxInkTrace.Branch pb = tr.branches.get(b.parent);
                forkIdx = nearest(pb, b.x[0], b.y[0]);
                if (forkIdx >= kept[b.parent]) continue;                 // the parent was cut before this fork
                Branch par = branches.get(newId[b.parent]);
                parentId = par.id;
                startSwing = swings[b.parent][forkIdx];
                forkAlong = par.along[forkIdx];
                start = par.start + forkAlong;
                px = new double[]{par.p[3 * forkIdx], par.p[3 * forkIdx + 1], par.p[3 * forkIdx + 2]};
            }
            FxShellWrap.Wrapped w = FxShellWrap.wrap(layer.shell(), b.x, b.y, startSwing, seed * 100003L + li * 10007L + b.id);
            double[][] pts = w.p();
            if (px != null) pts[0] = px;                                  // exactly on the parent
            int m = n;
            if (layer.thinFront()) {
                for (int i = 0; i < n; i++) {
                    double az = Math.toDegrees(Math.atan2(pts[i][0], pts[i][2]));
                    if (Math.abs(az) < FRONT_HALF_DEG && pts[i][1] > FRONT_TOP) { m = i; break; }
                }
            }
            if (m < 2) continue;
            float[] p = new float[3 * m], h = new float[m], sw = new float[m];
            for (int i = 0; i < m; i++) {
                p[3 * i] = (float) pts[i][0]; p[3 * i + 1] = (float) pts[i][1]; p[3 * i + 2] = (float) pts[i][2];
                double taper = (m < n && i >= m - 3) ? .35 + .65 * (m - 1 - i) / 3.0 : 1;
                h[i] = (float) (b.h[i] * layer.width() * taper);
                sw[i] = (float) w.swing()[i];
            }
            int id = branches.size();
            branches.add(new Branch(id, parentId, parentId < 0 ? 0 : branches.get(parentId).depth + 1, b.id, layer.family(), li, p, h, sw, forkIdx, forkAlong, start));
            newId[b.id] = id;
            kept[b.id] = m;
            swings[b.id] = w.swing();
        }
    }

    /** The traced fork index on a parent: the point nearest to the child's first point. */
    private static int nearest(FxInkTrace.Branch pb, double x, double y) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < pb.size(); i++) {
            double d = Math.hypot(pb.x[i] - x, pb.y[i] - y);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    // ---------------------------------------------------------------- schedule

    /** Receives every piece of ink that is drawn at one instant: the branch, how far it is drawn, where it has slid off, and its generation. */
    public interface Visitor {
        /** {@code from} and {@code drawn} are fractions (0..1) of the branch's length; {@code head} and {@code tail} in blocks along it (for the width taper). */
        void piece(Branch b, double from, double drawn, double head, double tail, int gen);
    }

    /** Every piece of ink at bench second {@code t}: growth, the sliding off and regrowth of renewing groups. Nothing is allocated. */
    public void visit(double t, Visitor v) {
        double lt = t - FxInkTraceScene.START;
        now = t;
        if (lt <= 0) return;
        for (Branch b : branches) {
            if (b.hidden || b.meanHalf < detailCut) continue;
            int g = group[b.id];
            if (g < 0) {
                curGroup = -1; curAge = 0;
                emit(b, (lt - ts[b.id]) * speed[b.id], -1e9, 0, v);
                continue;
            }
            double t0 = ts[g], per = period[g];
            int newest = (int) Math.floor((lt - t0) / per);
            for (int gen = Math.max(0, newest - 2); gen <= newest; gen++) {
                double age = lt - gen * per - ts[b.id];
                curGroup = g; curAge = age;
                emit(b, age * speed[b.id], (age - life[g]) * speed[b.id] - TAPER, gen, v);
            }
        }
    }

    private static void emit(Branch b, double head, double tail, int gen, Visitor v) {
        head = Math.min(head, b.length + TAPER);
        double drawn = Math.min(1, head / b.length), from = Math.clamp(tail / b.length, 0, 1);
        if (head <= 0 || from >= drawn || b.size() < 2) return;
        v.piece(b, from, drawn, head, tail, gen);
    }

    /** The ribbon's mesh half-width at point i: the traced half-width, tapered over {@link #TAPER} blocks at a growing head and a sliding tail. */
    public static double meshHalf(Branch b, int i, double head, double tail) {
        double w = 1;
        if (head < b.length + TAPER) w *= FxInkTree.smoothstep(0, TAPER, head - b.along[i]);
        if (tail > -TAPER) w *= FxInkTree.smoothstep(0, TAPER, b.along[i] - tail);
        return Math.max(b.h[i] * w * WIDTH[b.family], .0015) / MAIN_SHARE;
    }

    /** The brush seed of a piece. */
    public double seed(Branch b, int gen) { return FxInkTree.hash(5L * 977L + b.id * 31L + gen * 7L + 3); }

    /** The leaves among the branches (ends of the tree). */
    public int leaves() {
        boolean[] hasKid = new boolean[branches.size()];
        for (Branch b : branches) if (b.parent >= 0) hasKid[b.parent] = true;
        int n = 0;
        for (boolean k : hasKid) if (!k) n++;
        return n;
    }
}
