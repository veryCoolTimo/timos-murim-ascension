package io.github.verycooltimo.murim.client.vfx.fx;

/**
 * Pure maths: a traced flat drawing (x across, y up, blocks from the root) is bent round the body's vertical axis onto a FLARING SHELL, a
 * surface of revolution that opens upward like a flame: azimuth = centre + sense * x * xScale / r0, height = y * yScale, radius = r0 + k * height + swing.
 * The swing is a radial offset that changes smoothly ALONG each branch (its own phase, period and amplitude), is zero where the branch leaves its
 * parent (the child inherits the parent's swing at the fork, so a child starts exactly on its parent) and grows towards the tip: the branch leaves
 * its shell, outward (towards the camera in front, away from it behind) and inward, so a branch that is straight in the trace is a space curve.
 * The path is fixed in time. Frame: x to the body's right, y up, z towards the camera; azimuth 0 = the camera side, growing towards +x.
 * Client-free: FxTree3D builds the tree with it, the unit test and tools/vfx-lab run the same code.
 */
public final class FxShellWrap {
    /** The inner shell's radius at the ground (blocks) and how fast it opens per block of height. */
    public static final double R0 = .75, K = .3;
    /** Nothing passes nearer the axis than this (blocks) at any height below {@link #KEEP_HEIGHT}. */
    public static final double MIN_RADIUS = .58, KEEP_HEIGHT = 1.6;
    /** The largest total swing, inherited along the tree (blocks). */
    public static final double MAX_SWING = .75;

    /** One shell and how a drawing sits on it. {@code sense} +1 reads the drawing unmirrored from outside, -1 from inside (unmirrored from the opposite side). */
    public record Shell(double centreDeg, double r0, double k, int sense, double xScale, double yScale, double swingScale, double phaseShift) {}

    /** The wrapped branch: points (x, y, z) per traced point and the swing at each, in blocks. */
    public record Wrapped(double[][] p, double[] swing) {}

    private FxShellWrap() {}

    public static double hash(long n) {
        double s = Math.sin(n * 12.9898 + 78.233) * 43758.5453;
        return s - Math.floor(s);
    }

    /** max(a, b) with a rounded corner of size eps; never below the larger of the two. */
    static double smax(double a, double b, double eps) { return .5 * (a + b + Math.sqrt((a - b) * (a - b) + eps * eps)); }

    /** The point of a shell at traced (x, y) with a radial swing, never nearer the axis than {@link #MIN_RADIUS} low down. */
    public static double[] point(Shell s, double x, double y, double swing) {
        double az = Math.toRadians(s.centreDeg()) + s.sense() * x * s.xScale() / s.r0(), h = Math.max(.04, y * s.yScale());   // below the root's level the drawing creeps along the floor
        double r = s.r0() + s.k() * h + swing;
        if (h < KEEP_HEIGHT) r = smax(r, MIN_RADIUS + .02, .06);
        r = Math.max(r, MIN_RADIUS);
        return new double[]{Math.sin(az) * r, h, Math.cos(az) * r};
    }

    /** The azimuth in degrees (-180..180, 0 = the camera side) of a traced x on a shell. */
    public static double azimuthDeg(Shell s, double x) {
        double a = s.centreDeg() + Math.toDegrees(s.sense() * x * s.xScale() / s.r0());
        a = (a + 540) % 360 - 180;
        return a;
    }

    /**
     * Wraps one traced branch. {@code startSwing} is the parent's swing at the fork (0 for a root), {@code key} makes the branch's phase, period
     * and amplitude. The swing is {@code startSwing + amp * g(s) * sin(phase + 2 pi s / period)} with g(0) = 0 and g growing to the tip.
     */
    public static Wrapped wrap(Shell s, double[] x, double[] y, double startSwing, long key) {
        int n = x.length;
        double[][] p = new double[n][];
        double[] sw = new double[n];
        double total = 0;
        for (int i = 1; i < n; i++) total += Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
        total = Math.max(total, .05);
        double amp = (.15 + .35 * hash(key * 7 + 1)) * s.swingScale(), period = 1.4 + 2.0 * hash(key * 7 + 2);
        double phase = 2 * Math.PI * hash(key * 7 + 3) + s.phaseShift();
        double along = 0;
        for (int i = 0; i < n; i++) {
            if (i > 0) along += Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
            double g = (1 - Math.exp(-along / .45)) * (.4 + .6 * Math.min(1, along / total));
            double v = startSwing + amp * g * Math.sin(phase + 2 * Math.PI * along / period);
            sw[i] = Math.clamp(v, -MAX_SWING, MAX_SWING);
            p[i] = point(s, x[i], y[i], sw[i]);
        }
        return new Wrapped(p, sw);
    }
}
