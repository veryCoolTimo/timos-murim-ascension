package io.github.verycooltimo.murim.cultivation.flow;

/**
 * The way of the stream through the body in normalised screen coordinates (x as a share of the 4:3 picture's width, y of its
 * height): from the dantian up the right side, over the shoulders, down the left side and back. Ported from the mockup.
 */
public final class FlowRoute {

    private static final double[][] LOOP = {{.5, .80}, {.585, .74}, {.625, .6}, {.64, .45}, {.665, .31}, {.6, .215}, {.5, .19},
            {.4, .215}, {.335, .31}, {.36, .45}, {.375, .6}, {.415, .74}, {.5, .80}};

    /** Picture aspect (the way is measured in screen units, not in shares). */
    private static final double ASPECT = 4.0D / 3.0D;

    private final double[][] pts;
    private final double[] len;

    public FlowRoute(double share) {
        double[][] base = new double[LOOP.length][];
        for (int i = 0; i < LOOP.length; i++) {
            base[i] = LOOP[i].clone();
        }
        double[][] cut = base;
        int n = Math.max(4, (int) Math.round(base.length * share));
        if (share < 1.0D) {
            cut = new double[n + 3][];
            System.arraycopy(base, 0, cut, 0, n);
            cut[n] = new double[] {.5, .52};
            cut[n + 1] = new double[] {.5, .66};
            cut[n + 2] = new double[] {.5, .80};
        }
        int steps = 8;
        double[][] dense = new double[(cut.length - 1) * steps + 1][];
        int k = 0;
        for (int i = 0; i < cut.length - 1; i++) {
            double[] a = cut[i];
            double[] b = cut[i + 1];
            for (int j = 0; j < steps; j++) {
                dense[k++] = new double[] {a[0] + (b[0] - a[0]) * j / steps, a[1] + (b[1] - a[1]) * j / steps};
            }
        }
        dense[k] = cut[cut.length - 1].clone();
        for (int it = 0; it < 2; it++) {
            // Read the previous pass so smoothing preserves the mirrored outer loop.
            double[][] previous = dense.clone();
            for (int i = 1; i < dense.length - 1; i++) {
                dense[i] = new double[] {(previous[i - 1][0] + 2 * previous[i][0] + previous[i + 1][0]) / 4,
                        (previous[i - 1][1] + 2 * previous[i][1] + previous[i + 1][1]) / 4};
            }
        }
        this.pts = dense;
        this.len = new double[dense.length];
        for (int i = 1; i < dense.length; i++) {
            len[i] = len[i - 1] + Math.hypot((dense[i][0] - dense[i - 1][0]) * ASPECT, dense[i][1] - dense[i - 1][1]);
        }
    }

    /** Position at {@code s} in 0..1 of the way. */
    public double[] at(double s) {
        double d = Math.max(0.0D, Math.min(1.0D, s)) * len[len.length - 1];
        int i = 1;
        while (i < len.length - 1 && len[i] < d) {
            i++;
        }
        double t = (d - len[i - 1]) / Math.max(1.0E-9D, len[i] - len[i - 1]);
        return new double[] {pts[i - 1][0] + (pts[i][0] - pts[i - 1][0]) * t, pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * t};
    }

    /** Unit direction of the way at {@code s}. */
    public double[] dir(double s) {
        double[] a = at(Math.max(0.0D, s - 0.004D));
        double[] b = at(Math.min(1.0D, s + 0.004D));
        double l = Math.hypot(b[0] - a[0], b[1] - a[1]);
        if (l == 0.0D) {
            l = 1.0D;
        }
        return new double[] {(b[0] - a[0]) / l, (b[1] - a[1]) / l};
    }

    /** The smoothed polyline, for drawing the faint meridian. */
    public double[][] points() {
        return pts;
    }
}
