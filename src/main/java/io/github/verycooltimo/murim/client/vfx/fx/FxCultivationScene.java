package io.github.verycooltimo.murim.client.vfx.fx;

import java.util.ArrayList;
import java.util.List;

/**
 * The cultivation aura of reference panel 13 as one VOLUME round the seated body: ONE ink tree grown in 3D from the traced structure
 * (FxTree3D), in front of a small number of curved sheets of the violet-blue flat flame field (partial cones round the body's axis,
 * densest low and opening upward; one large dim blue sheet far behind; two small violet patches low in front), and the violet lights.
 * Client-free: the bench (FxCultivationBench) and tools/vfx-lab/cult_lab.py run this same code.
 * Frame: x to the body's right, y up from the feet, z towards the camera (blocks). No sway: the life is flow (growth outward, clots,
 * branches sliding off at the tip and regrowing, the fields rising with their bites).
 * Cost by construction: the ink is one batch from stored paths; the field is six strips (a few quads each) whose area is fitted to
 * where the field shows (the fragment shader also drops everything outside the strip's fan), nothing is drawn into BtGlow.
 */
public final class FxCultivationScene {
    public static final double SECONDS = FxInkTraceScene.BENCH_SECONDS, START = FxInkTraceScene.START;

    /**
     * One sheet of the field: a partial cone round the body's axis. {@code azimuth} (degrees from the camera side towards the body's
     * right) is the middle of its arc, {@code span} the arc in degrees, {@code r0} the radius at {@code y0} and {@code flare} how fast
     * the radius grows per block of height; {@code front} marks the sheets that stand between the camera and the ink.
     */
    public record Sheet(String name, FxFlameLook look, double azimuth, double span, double r0, double flare, double y0, double y1, int segments,
                        double seed, double from, double len, float alpha, boolean front) {
        /** Half the strip's length in blocks (mean radius times the arc). */
        public double halfWidth() { return (r0 + flare * (y1 - y0) / 2) * Math.toRadians(span) / 2; }
        public double halfHeight() { return (y1 - y0) / 2; }
    }

    private static Sheet sheet(String name, FxFlameLook look, double az, double span, double r0, double flare, double y0, double y1, int segments,
                               double seed, double from, double len, float alpha, boolean front) {
        Sheet probe = new Sheet(name, look, az, span, r0, flare, y0, y1, segments, seed, from, len, alpha, front);
        double hw = probe.halfWidth();
        // the strip's silhouette: full width low, narrowing as it opens upward, so the corners at the top are never drawn
        FxFlameLook fanned = look.fitted(hw);
        return new Sheet(name, fanned, az, span, r0, flare, y0, y1, segments, seed, from, len, alpha, front);
    }

    /**
     * Two curved strips of the flat flame, each a partial cone of 12 quads standing on the ground: a violet one just outside the ink shell
     * covering the back 240 degrees, and a blue one far behind it. The shader dissolves the top and side edges (no straight border).
     */
    public static final Sheet[] SHEETS = {
            sheet("far_blue", FxFlameLook.BLUE, 180, 200, FxShellWrap.R0 + 1.25, .3, .35, 4.6, 12, 3, 1.3, 2.4, .85F, false),
            sheet("violet_shell", FxFlameLook.VIOLET, 180, 240, FxShellWrap.R0 + .5, FxShellWrap.K, .3, 4.4, 12, 1, 1.4, 2.0, 1F, false)};

    private FxCultivationScene() {}

    static double smooth(double a, double b, double x) {
        double k = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return k * k * (3 - 2 * k);
    }

    /** Growth of a sheet at {@code t}: in over its {@code len} seconds, closed again over the last 1.3 seconds of the bench. */
    public static double growth(Sheet s, double t) {
        return smooth(s.from(), s.from() + s.len(), t) * (1 - smooth(SECONDS - 1.3, SECONDS, t));
    }

    /** The top and bottom edge of a sheet: {@code segments + 1} points each, (x, y, z) in the body frame. */
    public static double[][][] edges(Sheet s) {
        int n = s.segments();
        double[][][] out = new double[2][n + 1][];
        for (int j = 0; j <= n; j++) {
            double a = Math.toRadians(s.azimuth() - s.span() / 2 + s.span() * j / n);
            for (int k = 0; k < 2; k++) {
                double y = k == 0 ? s.y1() : s.y0(), r = s.r0() + s.flare() * (y - s.y0());
                out[k][j] = new double[]{Math.sin(a) * r, y, Math.cos(a) * r};
            }
        }
        return out;
    }

    /** Depth of a point along the camera's horizontal direction (cx, cz) in the body frame: larger = nearer the camera. */
    public static double depth(double x, double z, double cx, double cz) { return x * cx + z * cz; }

    /** The middle of a sheet (for sorting the sheets). */
    public static double sheetDepth(Sheet s, double cx, double cz) {
        double a = Math.toRadians(s.azimuth()), r = s.r0() + s.flare() * (s.y1() - s.y0()) / 2;
        return depth(Math.sin(a) * r, Math.cos(a) * r, cx, cz);
    }

    /** The order to draw a sheet's columns: far to near for the camera. */
    public static int[] order(Sheet s, double cx, double cz) {
        double[][][] e = edges(s);
        int n = s.segments();
        Integer[] idx = new Integer[n];
        double[] d = new double[n];
        for (int j = 0; j < n; j++) {
            idx[j] = j;
            d[j] = depth((e[0][j][0] + e[0][j + 1][0]) / 2, (e[0][j][2] + e[0][j + 1][2]) / 2, cx, cz);
        }
        java.util.Arrays.sort(idx, java.util.Comparator.comparingDouble(j -> d[j]));
        int[] out = new int[n];
        for (int j = 0; j < n; j++) out[j] = idx[j];
        return out;
    }

    /**
     * One soft light: an ellipse (centre x, y, depth z; half sizes in blocks; colour 0xRRGGBB) whose alpha falls from {@code alpha} at the
     * centre to nothing at the edge, drawn additively. It is a mesh with vertex colours ({@link #mesh}), so the fall-off is a real gradient
     * whatever the stroke shader does: this is what replaces the ink tree's halo stroke, which was a 0.4-block rim.
     */
    public record Light(String name, double x, double y, double z, double halfW, double halfH, int rgb, double alpha) {}

    /** Cells per side of a light's mesh. */
    public static final int GRID = 10;

    /**
     * The quads of a light, four vertices per cell (x, y, z, alpha), in the body frame: alpha = peak * (1 - r)^2 with r the elliptical
     * distance from the centre. The light faces the camera, whose horizontal direction in the body frame is (cx, cz) (unit; x right, z near);
     * its depth z is measured towards the camera along that direction (negative = behind the body).
     */
    public static float[][] mesh(Light l, double cx, double cz) { return mesh(l, cx, cz, GRID); }

    /** The same with {@code grid} cells per side (the quality governor lowers it). */
    public static float[][] mesh(Light l, double cx, double cz, int grid) {
        float[][] out = new float[grid * grid * 4][];
        int n = 0;
        for (int j = 0; j < grid; j++) {
            for (int i = 0; i < grid; i++) {
                int[][] corner = {{i, j}, {i + 1, j}, {i + 1, j + 1}, {i, j + 1}};
                for (int[] c : corner) {
                    double u = c[0] * 2.0 / grid - 1, v = c[1] * 2.0 / grid - 1, r = Math.min(1, Math.hypot(u, v)), k = (1 - r) * (1 - r);
                    double sx = l.x() + u * l.halfW();
                    out[n++] = new float[]{(float) (sx * cz + l.z() * cx), (float) (l.y() + v * l.halfH()), (float) (-sx * cx + l.z() * cz), (float) (l.alpha() * k)};
                }
            }
        }
        return out;
    }

    /**
     * The glow, all additive meshes with vertex colours (no per-pixel work): a soft glow volume round the body (camera-facing ellipses at
     * three depths: behind the root, through the body, a small low one in front, strongest low, violet-pink near the root) and the light on the
     * ground ({@link #ground}, a disc on the floor).
     */
    public static List<Light> lights(double t) {
        double g = glow(t), breath = 1 + .08 * Math.sin(t * .9), pulse = 1 + .12 * Math.sin(t * 1.7 + 1);
        List<Light> out = new ArrayList<>();
        // round 4 (author: «свечения чуть более агрессивного, и на игрока, и на пространство»): every light is stronger, and there are three kinds more:
        // the light ON the body (a tint over the model and a pale rim at both sides), the light in the air high up, and the glow of the branches
        // (FxHalo.ALPHA). All additive: nothing here darkens the picture.
        out.add(new Light("air", 0, 3.1, -.9, 1.6, 1.7, 0x6A54E8, .50 * g * breath));
        out.add(new Light("air_hi", 0, 2.4, -.5, 1.2, 1.6, 0x7A5CFF, .30 * g * pulse));
        out.add(new Light("back", 0, 1.8, -1.3, 1.8, 2.2, 0x6A3CD0, .62 * g * breath));
        out.add(new Light("body", 0, .9, 0, 1.4, 1.4, 0x9A4CE0, .56 * g * breath));
        out.add(new Light("front", 0, .4, .8, 1.5, .8, 0xC070E8, .50 * g * breath));
        out.add(new Light("tint", 0, .85, .6, .7, 1.25, 0xA866FF, .52 * g * pulse));
        out.add(new Light("rim_l", -.55, .8, .35, .28, .95, 0xD888F8, .62 * g * pulse));
        out.add(new Light("rim_r", .55, .8, .35, .28, .95, 0x7A8CFF, .62 * g * pulse));
        return out;
    }

    /** The strength of the whole effect at bench second {@code t}: in from 1.2 to 3.8 s, closed over the last 1.3 s. */
    public static double glow(double t) { return smooth(1.2, 3.8, t) * (1 - smooth(SECONDS - 1.3, SECONDS, t)); }

    /** Spokes and rings of the ground light's mesh. */
    public static final int SPOKES = 24, RINGS = 8;
    /** One light on the floor: mean radius (blocks), where its brightest ring lies (share of the radius), peak alpha, colour 0xRRGGBB. */
    public record Ground(String name, double radius, double ring, double peak, int rgb) {}
    /** The violet disc (radius 3, brightest in a ring just outside the body) and the pink-violet pool near the root, as in the panel. */
    public static final Ground[] GROUNDS = {new Ground("disc", 2.7, .28, .36, 0x8A52E8), new Ground("root", 1.6, .45, .46, 0xD060C8)};
    /** Mean radius of the widest light on the ground, in blocks. */
    public static final double GROUND_R = 3.0;

    /**
     * The light on the floor: a horizontal disc round the body, quiet right under it, brightest in a ring just outside the body and falling off
     * softly, its edge slightly irregular and breathing slowly. Quads of four vertices (x, y, z, alpha) in the body frame, just above the floor,
     * to be drawn additively.
     */
    public static float[][] ground(Ground gr, double t) { return ground(gr, t, SPOKES, RINGS); }

    /** The same with {@code spokes} and {@code rings} (the quality governor lowers them). */
    public static float[][] ground(Ground gr, double t, int spokes, int rings) {
        double g = glow(t);
        float[][] out = new float[spokes * rings * 4][];
        int n = 0;
        for (int s = 0; s < spokes; s++) {
            for (int r = 0; r < rings; r++) {
                int[][] corner = {{s, r}, {s + 1, r}, {s + 1, r + 1}, {s, r + 1}};
                for (int[] c : corner) {
                    double th = (c[0] % spokes) * 2 * Math.PI / spokes, rho = c[1] / (double) rings;
                    double rad = gr.radius() * (1 + .10 * Math.sin(3 * th + .25 * t) + .07 * Math.sin(5 * th - .17 * t + 1));
                    double prof = rho < gr.ring() ? smooth(0, gr.ring(), rho) * .75 + .25 * smooth(0, .1, rho) : Math.pow(1 - (rho - gr.ring()) / (1 - gr.ring()), 2.3);
                    out[n++] = new float[]{(float) (Math.sin(th) * rad * rho), .02F, (float) (Math.cos(th) * rad * rho), (float) (gr.peak() * g * prof)};
                }
            }
        }
        return out;
    }
}
