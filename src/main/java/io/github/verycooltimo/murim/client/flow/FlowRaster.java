package io.github.verycooltimo.murim.client.flow;

/**
 * A small software canvas for the pill game's screen: an ARGB buffer with hard-edged shapes only (no anti-aliasing, no blur), so the
 * picture is pixel art by construction. The game draws into it at about 320x180 and the client blows it up with nearest filtering.
 * No Minecraft classes here: the same code renders the frames the tests dump.
 */
public final class FlowRaster {

    public final int w;
    public final int h;
    /** Straight (not premultiplied) ARGB. */
    public final int[] px;

    public FlowRaster(int w, int h) {
        this.w = w;
        this.h = h;
        this.px = new int[w * h];
    }

    public void clear(int argb) {
        java.util.Arrays.fill(px, argb);
    }

    // ------------------------------------------------------------------ pixels

    /** Source-over blend of {@code rgb} at opacity {@code a} (0..1). */
    public void over(int x, int y, int rgb, double a) {
        if (x < 0 || y < 0 || x >= w || y >= h || a <= 0.0D) {
            return;
        }
        a = Math.min(1.0D, a);
        int i = y * w + x;
        int d = px[i];
        double da = (d >>> 24) / 255.0D;
        double oa = a + da * (1.0D - a);
        if (oa <= 0.0D) {
            return;
        }
        int r = (int) Math.round((((rgb >> 16) & 255) * a + ((d >> 16) & 255) * da * (1.0D - a)) / oa);
        int g = (int) Math.round((((rgb >> 8) & 255) * a + ((d >> 8) & 255) * da * (1.0D - a)) / oa);
        int b = (int) Math.round(((rgb & 255) * a + (d & 255) * da * (1.0D - a)) / oa);
        px[i] = ((int) Math.round(oa * 255.0D) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    /** Additive blend: light on top of what is there (the mockup's «lighter»). */
    public void add(int x, int y, int rgb, double a) {
        if (x < 0 || y < 0 || x >= w || y >= h || a <= 0.0D) {
            return;
        }
        int i = y * w + x;
        int d = px[i];
        int r = clamp((int) Math.round(((d >> 16) & 255) + ((rgb >> 16) & 255) * a));
        int g = clamp((int) Math.round(((d >> 8) & 255) + ((rgb >> 8) & 255) * a));
        int b = clamp((int) Math.round((d & 255) + (rgb & 255) * a));
        int da = d >>> 24;
        int na = Math.max(da, (int) Math.round(Math.min(1.0D, a) * 255.0D));
        px[i] = (na << 24) | (r << 16) | (g << 8) | b;
    }

    private void put(int x, int y, int rgb, double a, boolean additive) {
        if (additive) {
            add(x, y, rgb, a);
        } else {
            over(x, y, rgb, a);
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    public static int mix(int a, int b, double t) {
        t = Math.max(0.0D, Math.min(1.0D, t));
        int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    // ------------------------------------------------------------------ shapes

    public void rect(int x0, int y0, int x1, int y1, int rgb, double a, boolean additive) {
        for (int y = Math.max(0, y0); y < Math.min(h, y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) {
                put(x, y, rgb, a, additive);
            }
        }
    }

    public void disc(double cx, double cy, double r, int rgb, double a, boolean additive) {
        int x0 = (int) Math.floor(cx - r - 1);
        int x1 = (int) Math.ceil(cx + r + 1);
        int y0 = (int) Math.floor(cy - r - 1);
        int y1 = (int) Math.ceil(cy + r + 1);
        double r2 = r * r;
        for (int y = Math.max(0, y0); y <= Math.min(h - 1, y1); y++) {
            for (int x = Math.max(0, x0); x <= Math.min(w - 1, x1); x++) {
                double dx = x + .5D - cx;
                double dy = y + .5D - cy;
                if (dx * dx + dy * dy <= r2) {
                    put(x, y, rgb, a, additive);
                }
            }
        }
    }

    /** A glow in hard steps: {@code bands} nested discs, each adding a share, so the centre carries the whole {@code a}. */
    public void glow(double cx, double cy, double r, int rgb, double a, int bands) {
        for (int i = 0; i < bands; i++) {
            disc(cx, cy, r * (1.0D - (double) i / bands), rgb, a / bands, true);
        }
    }

    private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

    /**
     * A glow that falls off smoothly without blur: the falloff is cut into {@code levels} steps and the steps are mixed by an ordered
     * dither (a 4x4 Bayer pattern), the way pixel art draws a gradient. Every pixel is either lit at one of the levels or not.
     */
    public void glowDither(double cx, double cy, double r, int rgb, double a, int levels) {
        int x0 = (int) Math.floor(cx - r - 1);
        int x1 = (int) Math.ceil(cx + r + 1);
        int y0 = (int) Math.floor(cy - r - 1);
        int y1 = (int) Math.ceil(cy + r + 1);
        for (int y = Math.max(0, y0); y <= Math.min(h - 1, y1); y++) {
            for (int x = Math.max(0, x0); x <= Math.min(w - 1, x1); x++) {
                double d = Math.hypot(x + .5D - cx, y + .5D - cy) / r;
                if (d >= 1.0D) {
                    continue;
                }
                double f = (1.0D - d) * (1.0D - d * .35D);
                double t = (BAYER[(y & 3) * 4 + (x & 3)] + .5D) / 16.0D;
                double step = Math.floor(f * levels + t) / levels;
                if (step > 0.0D) {
                    add(x, y, rgb, a * step);
                }
            }
        }
    }

    /** A ring (or an arc: angles in radians from the top, clockwise; {@code a1 - a0 >= 2 pi} is the whole ring). */
    public void arc(double cx, double cy, double r, double thick, double a0, double a1, int rgb, double a, boolean additive) {
        double ro = r + thick / 2.0D;
        double ri = Math.max(0.0D, r - thick / 2.0D);
        boolean full = a1 - a0 >= Math.PI * 2 - 1.0E-6D;
        int x0 = (int) Math.floor(cx - ro - 1);
        int x1 = (int) Math.ceil(cx + ro + 1);
        int y0 = (int) Math.floor(cy - ro - 1);
        int y1 = (int) Math.ceil(cy + ro + 1);
        for (int y = Math.max(0, y0); y <= Math.min(h - 1, y1); y++) {
            for (int x = Math.max(0, x0); x <= Math.min(w - 1, x1); x++) {
                double dx = x + .5D - cx;
                double dy = y + .5D - cy;
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d < ri || d > ro) {
                    continue;
                }
                if (!full) {
                    double ang = Math.atan2(dx, -dy);
                    if (ang < 0) {
                        ang += Math.PI * 2;
                    }
                    double rel = ang - (a0 % (Math.PI * 2));
                    if (rel < 0) {
                        rel += Math.PI * 2;
                    }
                    if (rel > a1 - a0) {
                        continue;
                    }
                }
                put(x, y, rgb, a, additive);
            }
        }
    }

    public void ring(double cx, double cy, double r, double thick, int rgb, double a, boolean additive) {
        arc(cx, cy, r, thick, 0.0D, Math.PI * 2, rgb, a, additive);
    }

    /** A thick segment with flat ends (hard edge: a pixel is in when its centre is within half the width). */
    public void line(double x0, double y0, double x1, double y1, double width, int rgb, double a, boolean additive) {
        double half = Math.max(0.5D, width / 2.0D);
        int bx0 = (int) Math.floor(Math.min(x0, x1) - half - 1);
        int bx1 = (int) Math.ceil(Math.max(x0, x1) + half + 1);
        int by0 = (int) Math.floor(Math.min(y0, y1) - half - 1);
        int by1 = (int) Math.ceil(Math.max(y0, y1) + half + 1);
        double vx = x1 - x0;
        double vy = y1 - y0;
        double len2 = vx * vx + vy * vy;
        for (int y = Math.max(0, by0); y <= Math.min(h - 1, by1); y++) {
            for (int x = Math.max(0, bx0); x <= Math.min(w - 1, bx1); x++) {
                double px0 = x + .5D - x0;
                double py0 = y + .5D - y0;
                double t = len2 == 0.0D ? 0.0D : Math.max(0.0D, Math.min(1.0D, (px0 * vx + py0 * vy) / len2));
                double dx = px0 - vx * t;
                double dy = py0 - vy * t;
                if (dx * dx + dy * dy <= half * half) {
                    put(x, y, rgb, a, additive);
                }
            }
        }
    }

    /** A polyline of normalised points scaled to the canvas; {@code dash} > 0 draws dashes of that length with a gap of {@code gap}. */
    public void polyline(double[][] pts, double width, int rgb, double a, boolean additive) {
        for (int i = 1; i < pts.length; i++) {
            line(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1], width, rgb, a, additive);
        }
    }

    public void dashed(double[][] pts, double width, double dash, double gap, int rgb, double a, boolean additive) {
        double run = 0.0D;
        for (int i = 1; i < pts.length; i++) {
            double dx = pts[i][0] - pts[i - 1][0];
            double dy = pts[i][1] - pts[i - 1][1];
            double l = Math.hypot(dx, dy);
            if (l == 0.0D) {
                continue;
            }
            int steps = Math.max(1, (int) Math.ceil(l));
            for (int s = 0; s < steps; s++) {
                double t0 = (double) s / steps;
                double t1 = (double) (s + 1) / steps;
                double at = run + l * t0;
                if (at % (dash + gap) < dash) {
                    line(pts[i - 1][0] + dx * t0, pts[i - 1][1] + dy * t0, pts[i - 1][0] + dx * t1, pts[i - 1][1] + dy * t1, width,
                            rgb, a, additive);
                }
            }
            run += l;
        }
    }

    /** Round beads along a polyline, {@code spacing} apart (the poison spans of the mockup). */
    public void beads(double[][] pts, double radius, double spacing, int rgb, double a, boolean additive) {
        double carry = 0.0D;
        for (int i = 1; i < pts.length; i++) {
            double dx = pts[i][0] - pts[i - 1][0];
            double dy = pts[i][1] - pts[i - 1][1];
            double l = Math.hypot(dx, dy);
            if (l == 0.0D) {
                continue;
            }
            double at = carry;
            while (at <= l) {
                disc(pts[i - 1][0] + dx * at / l, pts[i - 1][1] + dy * at / l, radius, rgb, a, additive);
                at += spacing;
            }
            carry = at - l;
        }
    }

    /** Fills a polygon (even-odd) by scanlines. */
    public void polygon(double[] xs, double[] ys, int rgb, double a, boolean additive) {
        int n = xs.length;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double y : ys) {
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        double[] cross = new double[n];
        for (int y = Math.max(0, (int) Math.floor(min)); y <= Math.min(h - 1, (int) Math.ceil(max)); y++) {
            double sy = y + .5D;
            int c = 0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double ya = ys[i];
                double yb = ys[j];
                if ((ya <= sy && yb > sy) || (yb <= sy && ya > sy)) {
                    cross[c++] = xs[i] + (sy - ya) / (yb - ya) * (xs[j] - xs[i]);
                }
            }
            java.util.Arrays.sort(cross, 0, c);
            for (int k = 0; k + 1 < c; k += 2) {
                int xa = (int) Math.ceil(cross[k] - .5D);
                int xb = (int) Math.floor(cross[k + 1] - .5D);
                for (int x = Math.max(0, xa); x <= Math.min(w - 1, xb); x++) {
                    put(x, y, rgb, a, additive);
                }
            }
        }
    }

    /** The four-point star of the finale (8 vertices, the inner ones at 12 % of the radius). */
    public void star(double cx, double cy, double r, int rgb, double a) {
        double[] xs = new double[8];
        double[] ys = new double[8];
        for (int i = 0; i < 8; i++) {
            double ang = i / 8.0D * Math.PI * 2 - Math.PI / 2;
            double rr = i % 2 == 1 ? r * .12D : r;
            xs[i] = cx + Math.cos(ang) * rr;
            ys[i] = cy + Math.sin(ang) * rr;
        }
        polygon(xs, ys, rgb, a, true);
    }
}
