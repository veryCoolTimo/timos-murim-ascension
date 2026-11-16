package io.github.verycooltimo.murim.client.vfx.fx;

import java.util.ArrayList;
import java.util.List;

/**
 * The cultivation scene of reference panel 1 (the «закрепляющий» scene) as a volume round the seated body, built from the pieces we have:
 * the lights and the ground light of the aura (FxCultivationScene.Light / mesh / Ground), the flat-flame field far behind (FxFlame, here in
 * blue: the «blue fractal noise»), the cel dust (FxDust, a blue look), the motes (FxCultivationMotes with a cold palette: the snow-like specks),
 * and two new pieces the panel shows and we did not have:
 * <ul>
 * <li><b>bolts</b>: the lightning-like shards. Each is a jagged space curve (random zig-zag in all three axes, a spiral round the body) with its
 * own width: fat at the root on the body, a thin tail, sometimes a fork. It grows out of the body, then its tail lets go and it slides off along
 * itself. No bolt is ever moved or swayed: a new one is born with a new shape (the shape is a pure function of slot and cycle).</li>
 * <li><b>cloth</b>: translucent folded sheets, partial helices round the body with a zig-zag fold in every column and a colour per fold
 * (pale cyan, blue, teal), a ragged edge, that rise from the ground and peel off from the bottom upward; two low wide ones are the haze.</li>
 * </ul>
 * All meshes are additive vertex-colour quads: no per-pixel work. Frame: x to the body's right, y up from the feet, z towards the camera.
 * Client-free: the bench (FxCult1Bench) and tools/vfx-lab/cult1_lab.py run this same code.
 */
public final class FxCult1Scene {
    public static final double SECONDS = FxCultivationScene.SECONDS, START = FxCultivationScene.START;
    public static final int MAX_BOLTS = 11;

    private FxCult1Scene() {}

    static double smooth(double a, double b, double x) { return FxCultivationScene.smooth(a, b, x); }
    static double h(long a, long b) { return FxShellWrap.hash(a * 7919L + b * 104729L + 17); }

    /** A growable list of vertices (x, y, z in the body frame; colour 0..255; alpha 0..1), four per quad. */
    public static final class Mesh {
        public static final int STRIDE = 7;
        public float[] v = new float[STRIDE * 2048];
        public int n;
        public void clear() { n = 0; }
        public void vert(double x, double y, double z, int rgb, double a) {
            if ((n + 1) * STRIDE > v.length) v = java.util.Arrays.copyOf(v, v.length * 2);
            int o = n++ * STRIDE;
            v[o] = (float) x; v[o + 1] = (float) y; v[o + 2] = (float) z;
            v[o + 3] = (rgb >> 16) & 255; v[o + 4] = (rgb >> 8) & 255; v[o + 5] = rgb & 255; v[o + 6] = (float) Math.clamp(a, 0, 1);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- bolts

    /** One polyline of a bolt: points, half width at each, and the parameter of each point along its root (0 at the root, 1 at the tip). */
    static final class Line { double[][] p; double[] w; double[] s; }

    /** Colours of the three layers of a bolt (the panel: white core, pale cyan body, cyan glow). */
    public static final int GLOW = 0x3CB4FF, MID = 0x9CEEFF, CORE = 0xFFFFFF;

    /** The shape of bolt {@code slot} in cycle {@code cyc} (pure): the main line and up to two forks. */
    static List<Line> shape(int slot, long cyc, boolean forks) {
        long key = slot * 1000003L + cyc * 7919L;
        double az0 = 2 * Math.PI * h(key, 1), y0 = .08 + 1.1 * Math.pow(h(key, 2), 1.5);
        double r0 = .32 + .2 * h(key, 3);
        double len = .4 + .6 * Math.pow(h(key, 4), 1.3);                       // the reach outward
        double turn = (h(key, 5) < .5 ? -1 : 1) * (.4 + 1.3 * h(key, 6));         // the spiral round the body
        double rise = -.1 + 1.0 * h(key, 7) * h(key, 7);
        double w0 = .015 + .045 * Math.pow(h(key, 8), 1.6) + (h(key, 14) < .10 ? .045 : 0);                         // some fat patches, most thin
        boolean shard = h(key, 13) < .45;                                          // a shard is widest a quarter along, not at the root
        int n = 6 + (int) (4 * h(key, 9));
        Line main = new Line();
        main.p = new double[n + 1][]; main.w = new double[n + 1]; main.s = new double[n + 1];
        double prevJx = 0, prevJy = 0, prevJz = 0;
        for (int i = 0; i <= n; i++) {
            double s = i / (double) n, az = az0 + turn * s;
            double r = Math.min(1.25, r0 + len * Math.pow(s, .8));
            double y = y0 + rise * s;
            // zig-zag in all three axes: a new random direction every point, the sign alternating, growing towards the tip
            double amp = (i == 0 ? 0 : .07 + .16 * h(key, 20 + i)) * (.5 + s), sg = (i & 1) == 0 ? 1 : -1;
            double jx = (h(key, 40 + i) - .5) * 2, jy = (h(key, 60 + i) - .5) * 2 * sg, jz = (h(key, 80 + i) - .5) * 2;
            prevJx = jx; prevJy = jy; prevJz = jz;
            main.p[i] = new double[]{Math.sin(az) * r + jx * amp, Math.max(.03, y + jy * amp), Math.cos(az) * r + jz * amp};
            main.w[i] = w0 * (shard ? Math.min(1, .3 + 3 * s) : 1) * Math.pow(1 - s * .97, shard ? 1.0 : .85);
            main.s[i] = s;
        }
        List<Line> out = new ArrayList<>();
        out.add(main);
        int nf = forks ? (h(key, 11) < .55 ? 1 : 0) + (h(key, 12) < .25 ? 1 : 0) : 0;
        for (int f = 0; f < nf; f++) {
            int k = 2 + (int) ((n - 4) * h(key, 100 + f));
            if (k >= n - 1) k = n - 2;
            double[] a = main.p[k], b = main.p[k + 1];
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2], dl = Math.max(1e-6, Math.sqrt(dx * dx + dy * dy + dz * dz));
            int m = 3 + (int) (2 * h(key, 110 + f));
            double flen = .35 + .45 * h(key, 120 + f), side = h(key, 130 + f) < .5 ? -1 : 1;
            // the fork leaves the main line at an angle (a space direction: the main direction plus a random side)
            double rx = (h(key, 140 + f) - .5) * 2, ry = (h(key, 150 + f) - .5) * 2, rz = (h(key, 160 + f) - .5) * 2;
            double ex = dx / dl + side * .9 * rx, ey = dy / dl + .8 * ry, ez = dz / dl + side * .9 * rz, el = Math.sqrt(ex * ex + ey * ey + ez * ez);
            Line fl = new Line();
            fl.p = new double[m + 1][]; fl.w = new double[m + 1]; fl.s = new double[m + 1];
            double wr = main.w[k] * (.55 + .2 * h(key, 170 + f));
            double px = a[0], py = a[1], pz = a[2];
            for (int i = 0; i <= m; i++) {
                double s = i / (double) m;
                if (i > 0) {
                    double seg = flen / m, zz = (i & 1) == 0 ? 1 : -1;
                    px += (ex / el + zz * (h(key, 180 + f * 10 + i) - .5) * .9) * seg;
                    py += (ey / el + (h(key, 200 + f * 10 + i) - .5) * .9) * seg;
                    pz += (ez / el + zz * (h(key, 220 + f * 10 + i) - .5) * .9) * seg;
                }
                fl.p[i] = new double[]{px, Math.max(.03, py), pz};
                fl.w[i] = wr * Math.pow(1 - s * .95, .9);
                fl.s[i] = main.s[k] + (1 - main.s[k]) * s;       // it grows with the main line, from the fork on
            }
            out.add(fl);
        }
        return out;
    }

    /** Head and tail (0..1 along the root line) of a bolt at {@code age} (0..1 of its cycle), and its overall alpha. */
    static double[] life(double age) {
        double head = 1 - Math.pow(1 - smooth(0, .24, age), 2.2);
        double tail = smooth(.40, .88, age);
        double alpha = smooth(0, .05, age) * (1 - smooth(.86, 1, age));
        return new double[]{head, tail, alpha};
    }

    /**
     * Adds the ribbons of every bolt at bench second {@code t} to {@code m}. {@code view} = (x, y, z) of the direction to the camera in the body
     * frame (the ribbons face it); {@code share} 0..1 is the quality governor's (fewer bolts, no forks below 0.5).
     */
    public static void bolts(double t, double[] view, double share, Mesh m) {
        double g = FxCultivationScene.glow(t);
        if (g <= 0) return;
        int count = (int) Math.round(MAX_BOLTS * Math.clamp(share, .25, 1));
        for (int slot = 0; slot < count; slot++) {
            double period = 1.5 + 1.4 * h(slot, 1), x = (t - START) / period + h(slot, 2);
            long cyc = (long) Math.floor(x);
            double age = x - cyc;
            double[] lf = life(age);
            if (lf[2] <= 0.01) continue;
            for (Line l : shape(slot, cyc, share >= .5)) ribbon(l, lf[0], lf[1], lf[2] * Math.min(1, g * 1.3), view, m);
        }
    }

    /** One polyline as three layers of ribbon (glow, body, core), clipped to the parameter interval [tail, head]. */
    static void ribbon(Line l, double head, double tail, double alpha, double[] view, Mesh m) {
        int n = l.p.length;
        double[][] pts = new double[n + 2][];
        double[] ws = new double[n + 2];
        int c = 0;
        double lo = Math.max(tail, l.s[0]), hi = Math.min(head, 1);
        if (hi - lo < .02) return;
        pts[c] = at(l, lo); ws[c++] = wAt(l, lo) * Math.min(1, .15 + (0) / .15);   // the tail's cut is a thin shard
        for (int i = 0; i < n; i++) if (l.s[i] > lo + 1e-6 && l.s[i] < hi - 1e-6) { pts[c] = l.p[i]; ws[c++] = l.w[i]; }
        pts[c] = at(l, hi); ws[c++] = wAt(l, hi);
        // keep the face clear: points in front of the head thin out to a hair
        for (int i = 0; i < c; i++) ws[i] *= faceMask(pts[i], view);
        // the head is a point, the tail end thins
        for (int i = 0; i < c; i++) {
            double s = i / (double) Math.max(1, c - 1);
            ws[i] *= Math.min(1, (1 - s) * 5 + .0) * Math.min(1, .25 + s * 3);
        }
        // the head grows from the root: the point at the head is sharp, the thickness behind it is the line's own
        double[][] side = new double[c][];
        for (int i = 0; i < c; i++) {
            double[] a = pts[Math.max(0, i - 1)], b = pts[Math.min(c - 1, i + 1)];
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            // side = dir x view
            double sx = dy * view[2] - dz * view[1], sy = dz * view[0] - dx * view[2], sz = dx * view[1] - dy * view[0];
            double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1e-9) { side[i] = new double[]{1, 0, 0}; } else side[i] = new double[]{sx / sl, sy / sl, sz / sl};
        }
        layer(pts, ws, side, c, 3.0, GLOW, GLOW, .0, .12 * alpha, m);
        layer(pts, ws, side, c, 1.2, MID, MID, .3 * alpha, .62 * alpha, m);
        layer(pts, ws, side, c, .7, 0xDDF8FF, CORE, .7 * alpha, .85 * alpha, m);
    }

    /** 1 away from the face, .25 on the line of sight to the head (the head: y .95..1.55 seated, within .3 of the axis seen from the camera). */
    static double faceMask(double[] p, double[] view) {
        double front = p[0] * view[0] + p[2] * view[2];
        double lat = Math.abs(-p[0] * view[2] + p[2] * view[0]);
        if (front < .0) return 1;
        double d = Math.max(lat / .32, Math.abs(p[1] - 1.25) / .42);
        return .25 + .75 * smooth(.6, 1.2, d);
    }

    private static void layer(double[][] p, double[] w, double[][] sd, int c, double widen, int edgeRgb, int midRgb, double edgeA, double midA, Mesh m) {
        for (int i = 0; i + 1 < c; i++) {
            double w0 = w[i] * widen, w1 = w[i + 1] * widen;
            double[] a = p[i], b = p[i + 1], sa = sd[i], sb = sd[i + 1];
            double[] al = {a[0] + sa[0] * w0, a[1] + sa[1] * w0, a[2] + sa[2] * w0}, ar = {a[0] - sa[0] * w0, a[1] - sa[1] * w0, a[2] - sa[2] * w0};
            double[] bl = {b[0] + sb[0] * w1, b[1] + sb[1] * w1, b[2] + sb[2] * w1}, br = {b[0] - sb[0] * w1, b[1] - sb[1] * w1, b[2] - sb[2] * w1};
            m.vert(al[0], al[1], al[2], edgeRgb, edgeA); m.vert(a[0], a[1], a[2], midRgb, midA); m.vert(b[0], b[1], b[2], midRgb, midA); m.vert(bl[0], bl[1], bl[2], edgeRgb, edgeA);
            m.vert(a[0], a[1], a[2], midRgb, midA); m.vert(ar[0], ar[1], ar[2], edgeRgb, edgeA); m.vert(br[0], br[1], br[2], edgeRgb, edgeA); m.vert(b[0], b[1], b[2], midRgb, midA);
        }
    }

    private static double[] at(Line l, double s) {
        int i = 0;
        while (i + 2 < l.s.length && l.s[i + 1] < s) i++;
        double u = Math.clamp((s - l.s[i]) / Math.max(1e-9, l.s[i + 1] - l.s[i]), 0, 1);
        return new double[]{l.p[i][0] + (l.p[i + 1][0] - l.p[i][0]) * u, l.p[i][1] + (l.p[i + 1][1] - l.p[i][1]) * u, l.p[i][2] + (l.p[i + 1][2] - l.p[i][2]) * u};
    }

    private static double wAt(Line l, double s) {
        int i = 0;
        while (i + 2 < l.s.length && l.s[i + 1] < s) i++;
        double u = Math.clamp((s - l.s[i]) / Math.max(1e-9, l.s[i + 1] - l.s[i]), 0, 1);
        return l.w[i] + (l.w[i + 1] - l.w[i]) * u;
    }

    // ---------------------------------------------------------------------------------------------------------------- cloth

    /** One translucent folded sheet: a partial helix round the body. Angles in degrees, lengths in blocks. */
    public record Cloth(String name, double az, double span, double r0, double flare, double y0, double y1, double twist, int cols, double fold,
                        double period, double phase, double alpha, int[] colours) {}

    static final int[] COOL = {0xD8FAFF, 0x8EEBFF, 0x4FA8F0, 0x6EE0D0}, DEEP = {0x6CB8F8, 0x4A90E8, 0x3A78D8, 0x58C8F0}, HAZE = {0xB8F4F4, 0x7CDCE8, 0x58B8E0};

    public static final Cloth[] CLOTHS = {
            new Cloth("haze_a", 20, 260, 1.0, .75, 0, .8, -.15, 24, .07, 7.5, .1, .24, HAZE),
            new Cloth("haze_b", 200, 260, 1.3, .6, 0, .65, .2, 24, .08, 8.5, .6, .20, HAZE),
            new Cloth("sweep_1", 60, 95, .6, .40, .05, 2.3, .2, 18, .09, 5.0, .0, .26, COOL),
            new Cloth("sweep_2", 170, 90, .65, .45, .05, 2.0, -.4, 17, .10, 5.6, .35, .26, COOL),
            new Cloth("sweep_3", 275, 100, .6, .35, .05, 2.4, .25, 19, .09, 6.2, .7, .26, COOL),
            new Cloth("sweep_4", 335, 95, .7, .5, .05, 1.8, -.35, 15, .10, 4.6, .55, .24, COOL),
            new Cloth("far_1", 150, 160, 2.3, .25, 0, 4.4, .04, 28, .12, 9.0, .2, .20, DEEP),
            new Cloth("far_2", 250, 150, 2.6, .2, 0, 4.2, -.04, 28, .12, 10.0, .75, .18, DEEP),
            new Cloth("far_3", 30, 150, 2.5, .2, 0, 4.0, .05, 26, .12, 11.0, .45, .16, DEEP)};

    private static double tri(double x) { double f = x - Math.floor(x); return Math.abs(f * 2 - 1); }

    /** Adds the quads of every cloth sheet at {@code t}; {@code share} (quality) drops the highest-numbered sheets whole and thins the columns. */
    public static void cloths(double t, double share, double[] view, Mesh m) {
        double g = FxCultivationScene.glow(t);
        if (g <= 0) return;
        int count = (int) Math.round(CLOTHS.length * Math.clamp(share, .5, 1));
        for (int ci = 0; ci < count; ci++) {
            Cloth c = CLOTHS[ci];
            double x = (t - START) / c.period() + c.phase();
            long cyc = (long) Math.floor(x);
            double age = x - cyc;
            // the cloth rises from the ground and peels off from the bottom upward: the window [vlo, vhi] of its height that is visible
            double vhi = smooth(0, .38, age), vlo = smooth(.5, 1, age) * .98;
            if (vhi - vlo < .05) continue;
            long key = ci * 131L + cyc * 17L;
            double rot = Math.toRadians(c.az()) + (h(key, 1) - .5) * 1.0 + .22 * (t - START) * (ci % 2 == 0 ? 1 : -1);
            int cols = c.cols(), rows = 7;
            double hgt = c.y1() - c.y0();
            for (int j = 0; j < cols; j++) {
                int col = 0;      // the colour comes per vertex below (a smooth drift through the palette, folds only change the alpha)
                // a ragged window: every column has its own top and bottom
                double jt = (h(key, 60 + j) - .5) * .16, jb = (h(key, 90 + j) - .5) * .12;
                double top = Math.min(1, vhi + jt * smooth(0, .2, vhi)), bot = Math.max(0, vlo + jb * vlo);
                if (top - bot < .04) continue;
                for (int r = 0; r < rows; r++) {
                    double v0 = bot + (top - bot) * r / rows, v1 = bot + (top - bot) * (r + 1) / rows;
                    double a0 = env((v0 - bot) / (top - bot)), a1 = env((v1 - bot) / (top - bot));
                    for (int k = 0; k < 4; k++) {
                        int cj = j + (k == 1 || k == 2 ? 1 : 0);
                        double v = (k < 2) ? v0 : v1, a = (k < 2) ? a0 : a1;
                        double y = c.y0() + v * hgt, an = rot + Math.toRadians(c.span() * (cj / (double) cols - .5)) + c.twist() * v * 2;
                        double fold = 1 + c.fold() * (tri(cj * .5 + h(key, 2) * 2) * 2 - 1) + .05 * Math.sin(v * 5 + key);
                        double rad = (c.r0() + c.flare() * (y - c.y0())) * fold;
                        double face = Math.abs(Math.sin(an) * view[0] + Math.cos(an) * view[2]);      // edge-on sheets stack into bright blades: fade them
                        double u = cj / (double) cols, lat = smooth(0, .3, u) * (1 - smooth(.7, 1, u)), crease = .45 + .55 * tri(cj * .5 + h(key, 2) * 2);
                        m.vert(Math.sin(an) * rad, y, Math.cos(an) * rad, drift(c.colours(), cj * .18 + h(key, 3) * 3 + v * .6), a * c.alpha() * g * lat * crease * 1.2 * (.1 + .9 * smooth(.1, .5, face)));
                    }
                }
            }
        }
    }

    /** A colour drifting smoothly through the palette. */
    static int drift(int[] pal, double p) {
        double f = p - Math.floor(p), x = (p - f) % pal.length;
        int i = ((int) Math.floor(p) % pal.length + pal.length) % pal.length, j = (i + 1) % pal.length;
        double u = f * f * (3 - 2 * f);
        int out = 0;
        for (int sh = 16; sh >= 0; sh -= 8) out |= (int) Math.round(((pal[i] >> sh) & 255) * (1 - u) + ((pal[j] >> sh) & 255) * u) << sh;
        return out;
    }

    /** Alpha along the visible window: strong low, a soft dissolve at both ends. */
    static double env(double u) { return smooth(0, .22, u) * (1 - smooth(.55, 1, u) * .85) * (.55 + .45 * smooth(0, .5, u)); }

    // ---------------------------------------------------------------------------------------------------------------- lights

    /** Cold light, strong (author 14.11: more aggressive, on the player and on the space): the air round the body, the tint on the skin, the rims. */
    public static List<FxCultivationScene.Light> lights(double t) {
        double g = FxCultivationScene.glow(t), breath = 1 + .08 * Math.sin(t * .9), pulse = 1 + .12 * Math.sin(t * 1.7 + 1);
        List<FxCultivationScene.Light> out = new ArrayList<>();
        out.add(new FxCultivationScene.Light("top", 0, 3.9, -1.6, 2.4, 1.9, 0x7AA8F0, .22 * g * breath));     // the blur with texture at the top
        out.add(new FxCultivationScene.Light("wall", 0, 2.3, -2.5, 3.4, 2.6, 0x2A64C8, .34 * g * breath));
        out.add(new FxCultivationScene.Light("air", 0, 2.9, -.9, 1.7, 1.8, 0x3C8CFF, .38 * g * breath));
        out.add(new FxCultivationScene.Light("back", 0, 1.8, -1.3, 1.9, 2.2, 0x3C78E8, .40 * g * breath));
        out.add(new FxCultivationScene.Light("body", 0, .95, 0, 1.4, 1.4, 0x8CE0FF, .26 * g * breath));
        out.add(new FxCultivationScene.Light("front", 0, .4, .8, 1.6, .8, 0x78D8F0, .30 * g * breath));
        out.add(new FxCultivationScene.Light("tint", 0, .9, .6, .7, 1.3, 0xDCF8FF, .11 * g * pulse));
        out.add(new FxCultivationScene.Light("lap", 0, .5, .55, 1.0, .7, 0x9CEEFF, .22 * g * breath));
        out.add(new FxCultivationScene.Light("rim_l", -.55, .85, .35, .28, 1.0, 0xE8FFFF, .40 * g * pulse));
        out.add(new FxCultivationScene.Light("rim_r", .55, .85, .35, .28, 1.0, 0xBCEEFF, .40 * g * pulse));
        return out;
    }

    /** The light on the floor: a teal-white disc and a pale pool under the body. */
    /** The ground light with a FILLED middle (no dark hole under the body): quiet but never below a third of the peak. */
    public static float[][] ground(FxCultivationScene.Ground gr, double t, int spokes, int rings) {
        double g = FxCultivationScene.glow(t);
        float[][] out = new float[spokes * rings * 4][];
        int n = 0;
        for (int sp = 0; sp < spokes; sp++) for (int r = 0; r < rings; r++) {
            int[][] corner = {{sp, r}, {sp + 1, r}, {sp + 1, r + 1}, {sp, r + 1}};
            for (int[] c : corner) {
                double th = (c[0] % spokes) * 2 * Math.PI / spokes, rho = c[1] / (double) rings;
                double rad = gr.radius() * (1 + .10 * Math.sin(3 * th + .25 * t) + .07 * Math.sin(5 * th - .17 * t + 1));
                double prof = rho < gr.ring() ? .45 + .55 * smooth(0, gr.ring(), rho) : Math.pow(1 - (rho - gr.ring()) / (1 - gr.ring()), 2.3);
                out[n++] = new float[]{(float) (Math.sin(th) * rad * rho), .02F, (float) (Math.cos(th) * rad * rho), (float) (gr.peak() * g * prof)};
            }
        }
        return out;
    }

    public static final FxCultivationScene.Ground[] GROUNDS = {new FxCultivationScene.Ground("disc", 2.8, .28, .36, 0x3CD0D0), new FxCultivationScene.Ground("root", 1.6, .45, .36, 0x8CF0E8)};

    // ---------------------------------------------------------------------------------------------------------------- the rest

    /** Blue, brighter than the aura's: the cold fractal noise far behind (colours: plates/1-back). */
    public static final FxFlameLook BACK = new FxFlameLook(.25F, .3F, .3F, .55F, -.30F, -.20F, 1F, .15F,
            .0F, .30F, 0F, .22F,
            new int[]{0x1C3C78, 0x2A5CA8, 0x3A78C8, 0x5C9CD8, 0x9CC8EC}, new float[]{.30F, .34F, .34F, .32F, .3F}, new float[]{1F, .9F, .7F, .4F});

    /** The cold motes: white and cyan specks like snow in the wind (the panel's «частицы с ветром»). */
    public static final int[] MOTE_COLOURS = {0xFFFFFF, 0xD2F6FF, 0x8FE0FF, 0x5CB4FF};
    public static final double[] MOTE_WEIGHT = {.35, .3, .2, .15};
    public static final int[] SPARK_TINTS = {0xFFFFFF, 0x9FF0FF};

    public static FxCultivationMotes newMotes() {
        FxCultivationMotes m = new FxCultivationMotes(21, MOTE_COLOURS, MOTE_WEIGHT, SPARK_TINTS, 90, 16);
        m.sizeScale = .55; m.wind = .55;      // fine specks, blown sideways like snow
        return m;
    }

}
