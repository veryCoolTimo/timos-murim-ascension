package io.github.verycooltimo.murim.client.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The meridians of the pill scene built from the player model's own skeleton (author 15.11 on version 3: «оно просто на груди, на референсе видно,
 * что оно уходит в руки и по телу вниз»; refs pills-ar «absorbing pill», heavenly «breaking»): lightning-like jagged veins that start at the dantian,
 * run to the shoulders, down the arms past the elbows to the hands, down the torso to the belly and hips, plus ONE beam up the body axis that stops at
 * the collar. Nothing goes above the neck and nothing goes into the legs. Points are in torso pixels {@code (a, v, z)}: across (screen right +), up, and
 * out of the chest towards the lens; the renderer maps them with the live torso frame, so the veins follow the body.
 *
 * <p>A branch starts when its parent has reached the fork, so the light spreads from the centre outward in order. Pure maths (PillBodyVeinsTest).
 */
public final class PillBodyVeins {
    /** Joint positions in torso pixels: a, v, z. */
    public record Joints(double[] dantian, double[] collar, double[] shoulderL, double[] shoulderR, double[] elbowL, double[] elbowR,
                         double[] handL, double[] handR) { }

    /** One vein. {@code start}/{@code dur} are on the growth clock 0..1; {@code at} is the share of the parent where it forks; {@code arm} marks limb veins. */
    public record Branch(int depth, int parent, double at, double start, double dur, double width, boolean arm, double[][] pts, double length) {
        public double[] at(double s) {
            if (pts.length < 2) return pts[0];
            double want = Math.max(0, Math.min(1, s)) * length, run = 0;
            for (int i = 1; i < pts.length; i++) {
                double[] a = pts[i - 1], b = pts[i];
                double seg = dist(a, b);
                if (run + seg >= want || i == pts.length - 1) {
                    double k = seg < 1e-9 ? 0 : Math.max(0, Math.min(1, (want - run) / seg));
                    return new double[] {a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k};
                }
                run += seg;
            }
            return pts[pts.length - 1];
        }
    }

    public static final double BELLY_V = 3.2, COLLAR_V = 11.1, FLOOR_V = 1.3;

    private final List<Branch> out = new ArrayList<>();
    private final Random rng;
    private final Joints j;

    private PillBodyVeins(Joints joints, long seed) {
        j = joints;
        rng = new Random(seed);
    }

    public static List<Branch> build(Joints joints, long seed, int detail) {
        PillBodyVeins b = new PillBodyVeins(joints, seed);
        b.make(Math.max(1, Math.min(3, detail)));
        return b.normalise();
    }

    /** Fallback joints of a seated body when the bones are missing: arms hang to the knees. */
    public static Joints standard() {
        return new Joints(new double[] {0, BELLY_V, 0}, new double[] {0, COLLAR_V, 0}, new double[] {-5.6, 10, 0}, new double[] {5.6, 10, 0},
                new double[] {-6.6, 5.6, 1}, new double[] {6.6, 5.6, 1}, new double[] {-5.6, 1.6, 3}, new double[] {5.6, 1.6, 3});
    }

    public static double dist(double[] a, double[] b) { return Math.sqrt(sq(a[0] - b[0]) + sq(a[1] - b[1]) + sq(a[2] - b[2])); }

    private static double sq(double x) { return x * x; }

    private double jit(double a) { return (rng.nextDouble() - .5) * 2 * a; }

    private static double[] p(double a, double v, double z) { return new double[] {a, v, z}; }

    private static double[] plus(double[] a, double da, double dv, double dz) { return p(a[0] + da, a[1] + dv, a[2] + dz); }

    /** A jagged polyline through the key points: a kink at every node, small sideways bow, never a straight stick. */
    private double[][] jagged(List<double[]> keys, double step, double kink, double depthKink) {
        List<double[]> pts = new ArrayList<>();
        pts.add(keys.get(0));
        for (int k = 1; k < keys.size(); k++) {
            double[] a = keys.get(k - 1), b = keys.get(k);
            double len = dist(a, b);
            int n = Math.max(1, (int) Math.round(len / step));
            double nx = -(b[1] - a[1]), nv = b[0] - a[0], nl = Math.hypot(nx, nv);
            if (nl < 1e-9) { nx = 1; nv = 0; nl = 1; }
            nx /= nl; nv /= nl;
            double side = rng.nextBoolean() ? 1 : -1;
            for (int i = 1; i <= n; i++) {
                double t = i / (double) n;
                double last = i == n && k == keys.size() - 1 ? 0 : 1;
                double bow = Math.sin(Math.PI * t) * kink * .8 * side, kk = jit(kink) * last;
                double v = a[1] + (b[1] - a[1]) * t + nv * (bow + kk);
                pts.add(p(a[0] + (b[0] - a[0]) * t + nx * (bow + kk), Math.max(FLOOR_V, Math.min(COLLAR_V, v)), a[2] + (b[2] - a[2]) * t + jit(depthKink) * last));
            }
        }
        return pts.toArray(new double[0][]);
    }

    private static double length(double[][] pts) {
        double s = 0;
        for (int i = 1; i < pts.length; i++) s += dist(pts[i - 1], pts[i]);
        return s;
    }

    private int add(int depth, int parent, double at, double[][] pts, double width, boolean arm) {
        out.add(new Branch(depth, parent, at, 0, 0, width, arm, pts, length(pts)));
        return out.size() - 1;
    }

    private void make(int detail) {
        // One beam up the body axis, from the dantian to the collar and no further (the chin and face stay clear).
        int trunk = add(0, -1, 0, jagged(List.of(j.dantian(), p(0, 7.2, 0), plus(j.collar(), 0, 0, 0)), 1.2, .3, .1), .55, false);
        double[][] sides = {{-1}, {1}};
        for (double[] sd : sides) {
            double s = sd[0];
            double[] sh = s < 0 ? j.shoulderL() : j.shoulderR(), el = s < 0 ? j.elbowL() : j.elbowR(), ha = s < 0 ? j.handL() : j.handR();
            // Three veins run to the hand, each on its own path: along the top of the arm, along its front and along the inner side.
            double[][] shifts = {{0, 0, 0}, {s * .8, -.4, .7}, {-s * .8, .5, .6}};
            int topArm = -1;
            for (int k = 0; k < 3; k++) {
                double[] d = shifts[k];
                List<double[]> keys = new ArrayList<>();
                keys.add(p(0, k == 0 ? 7.6 : 5.6 + k * .9, 0));
                keys.add(p(s * (2.3 + .5 * k), 8.8 + .3 * k, 0));
                keys.add(plus(sh, d[0], d[1], d[2]));
                keys.add(plus(el, d[0] * 1.2, d[1] * 1.2, d[2]));
                keys.add(plus(ha, d[0] * .8, d[1] * .8, d[2]));
                // The arm vein is a child of the trunk and forks at the chest; it starts on the trunk.
                int id = add(1, trunk, k == 0 ? .62 : .42 + .1 * k, jagged(keys, .9, .38 + .06 * k, .18), .34 - .05 * k, true);
                if (k == 0) topArm = id;
                twigs(id, detail, true);
            }
            // Fingers: a small fan at the hand, grown from the end of the arm vein that runs along the top of the arm.
            for (int f = 0; f < 3; f++) {
                double ang = (f - 1) * .6 - Math.PI / 2;
                double[] to = p(ha[0] + s * (f - 1) * .5 + Math.cos(ang) * .3, ha[1] + Math.sin(ang) * 1.2, ha[2] + .3);
                add(3, topArm, 1.0, jagged(List.of(plus(ha, 0, 0, 0), to), .6, .12, .05), .2, true);
            }
            // Ribs and belly: sweeping arcs from the trunk down and out, the lowest ones reach the hips but never the legs.
            double[] at = {.22, .42, .62, .8};
            for (int i = 0; i < at.length; i++) {
                double[] from = out.get(trunk).at(at[i]);
                double reach = 2.1 + rng.nextDouble() * 1.4, rise = i < 2 ? -1.1 + rng.nextDouble() * 1.2 : .9 + rng.nextDouble() * 1.4;
                int id = add(1, trunk, at[i], jagged(List.of(from, p(s * reach, Math.max(FLOOR_V, from[1] + rise), .1)), .8, .36, .1), .3, false);
                twigs(id, detail, false);
            }
            add(1, trunk, 0, jagged(List.of(j.dantian(), p(s * 3.1, FLOOR_V + .4, .1)), .8, .34, .1), .32, false);
        }
    }

    private void twigs(int parent, int detail, boolean arm) {
        Branch b = out.get(parent);
        int count = arm ? (b.width() > .3 ? 2 : 1) : 1;
        for (int k = 0; k < count; k++) {
            double s = .22 + .62 * (k + rng.nextDouble() * .7) / count;
            double[] a = b.at(Math.min(.92, s)), c = b.at(Math.min(1, s + .08));
            double dir = Math.atan2(c[1] - a[1], c[0] - a[0]) + (rng.nextBoolean() ? 1 : -1) * (.55 + rng.nextDouble() * .7);
            double len = arm ? 1.0 + rng.nextDouble() * 1.4 : .8 + rng.nextDouble() * .8;
            double[] to = p(a[0] + Math.cos(dir) * len, Math.max(FLOOR_V, a[1] + Math.sin(dir) * len), a[2] + jit(.2));
            add(2, parent, s, jagged(List.of(a, to), .6, .16, .05), b.width() * .62, arm);
        }
    }

    /** Fills {@code start}/{@code dur}: a child grows after its parent has reached the fork, at one steady speed; the whole tree takes 1.0. */
    private List<Branch> normalise() {
        int n = out.size();
        double[] begin = new double[n], end = new double[n];
        double speed = 14, delay = .05, max = 0;
        for (int i = 0; i < n; i++) {
            Branch b = out.get(i);
            double s = b.parent() < 0 ? 0 : begin[b.parent()] + (end[b.parent()] - begin[b.parent()]) * b.at() + delay;
            begin[i] = s; end[i] = s + b.length() / speed; max = Math.max(max, end[i]);
        }
        List<Branch> res = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Branch b = out.get(i);
            res.add(new Branch(b.depth(), b.parent(), b.at(), begin[i] / max, (end[i] - begin[i]) / max, b.width(), b.arm(), b.pts(), b.length()));
        }
        return res;
    }
}
