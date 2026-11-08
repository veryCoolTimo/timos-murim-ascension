package io.github.verycooltimo.murim.client.flow;

import io.github.verycooltimo.murim.cultivation.flow.FlowGame;

import java.util.ArrayList;
import java.util.List;

/**
 * The picture of the game «Провести поток»: a port of the approved mockup's {@code draw()} (plan-site/extra/mockups/pill-flow) onto a
 * pixel canvas. Torso silhouette, the faint meridian loop, the clot with a thick tapered trail and thin filaments, rings at the narrow
 * spots, light and dark fork branches, the poison spans, the pressure vignette and bar, the dissolve ring and the finale: three or four
 * tiny four-point stars, then one big «bam» that rises fast and falls slowly.
 *
 * <p>The scene is designed in a 240x180 box (the mockup's 4:3 stage); the canvas around it (a wider screen) is the same dark field.
 * Sizes are the mockup's pixels at about 640 px of height scaled to 180, and thickened where a one-pixel line would not read. Pure:
 * no Minecraft classes.
 */
public final class FlowScene {

    public static final int DW = 240;
    public static final int DH = 180;

    /** A caption for the client to draw with the game font at (x, y) of the canvas. */
    public record Label(String key, double x, double y, int rgb, int align, float scale) {
        Label(String key, double x, double y, int rgb, int align) {
            this(key, x, y, rgb, align, 0.8F);
        }
    }

    private static final double[][] BODY = {{.5, .08}, {.56, .1}, {.585, .16}, {.6, .19}, {.74, .24}, {.79, .33}, {.8, .6},
            {.85, 1.4}, {.15, 1.4}, {.2, .6}, {.21, .33}, {.26, .24}, {.4, .19}, {.415, .16}, {.44, .1}};

    private static final int RED = 0xE2485A;
    private static final int RED_HOT = 0xFF5468;

    private final FlowRaster r;
    private final FlowGame g;
    private final boolean flashes;
    private final int ox;
    private final int oy;
    private final List<Label> labels = new ArrayList<>();
    private double[][] torso;

    private FlowScene(FlowRaster r, FlowGame g, int ox, int oy, boolean flashes) {
        this.flashes = flashes;
        this.r = r;
        this.g = g;
        this.ox = ox;
        this.oy = oy;
    }

    /**
     * Draws a frame and returns the captions to put on top.
     *
     * @param shakeX  horizontal jitter in canvas pixels (the mockup shakes the scene, not the HUD)
     * @param flashes whether full-screen flashes are allowed (the accessibility switch); off keeps the tints faint and the white flash out
     */
    public static List<Label> draw(FlowRaster r, FlowGame g, int shakeX, int shakeY, boolean flashes) {
        int ox = (r.w - DW) / 2;
        int oy = (r.h - DH) / 2;
        FlowScene s = new FlowScene(r, g, ox, oy, flashes);
        s.background();
        s.ox2 = ox + shakeX;
        s.oy2 = oy + shakeY;
        s.body();
        s.route();
        if (g.state == FlowGame.State.TORN) {
            s.torn();
        } else {
            s.clot();
        }
        s.ox2 = ox;
        s.oy2 = oy;
        s.hud();
        return s.labels;
    }

    // the scene origin with the shake; the HUD uses the plain one
    private int ox2;
    private int oy2;

    private double X(double nx) {
        return ox2 + nx * DW;
    }

    private double Y(double ny) {
        return oy2 + ny * DH;
    }

    private static int hex(int rgb) {
        return rgb & 0xFFFFFF;
    }

    // ------------------------------------------------------------------ field and body

    /** The darkened world: a dark field, lighter in the middle, under which the world barely shows. */
    private void background() {
        double cx = r.w / 2.0D;
        double cy = r.h / 2.0D;
        double inner = DH * .1D;
        double outer = DH * .75D;
        int centre = 0x141622;
        int edge = 0x05060A;
        for (int y = 0; y < r.h; y++) {
            for (int x = 0; x < r.w; x++) {
                double d = Math.hypot((x + .5D - cx) / 1.0D, (y + .5D - cy)) ;
                double t = Math.max(0.0D, Math.min(1.0D, (d - inner) / (outer - inner)));
                t = Math.round(t * 10.0D) / 10.0D; // ten hard steps: no smooth gradient
                int c = FlowRaster.mix(centre, edge, t);
                r.px[y * r.w + x] = (0xF2 << 24) | c;
            }
        }
    }

    private double[][] torso() {
        if (torso != null) {
            return torso;
        }
        List<double[]> out = new ArrayList<>();
        double cx = BODY[0][0];
        double cy = BODY[0][1];
        out.add(new double[] {cx, cy});
        for (int i = 1; i < BODY.length; i++) {
            double[] a = BODY[i - 1];
            double[] b = BODY[i];
            double ex = (a[0] + b[0]) / 2;
            double ey = (a[1] + b[1]) / 2;
            for (int k = 1; k <= 8; k++) {
                double t = k / 8.0D;
                double mt = 1 - t;
                out.add(new double[] {mt * mt * cx + 2 * mt * t * a[0] + t * t * ex, mt * mt * cy + 2 * mt * t * a[1] + t * t * ey});
            }
            cx = ex;
            cy = ey;
        }
        torso = out.toArray(new double[0][]);
        return torso;
    }

    private void body() {
        double[][] t = torso();
        double[] xs = new double[t.length];
        double[] ys = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            xs[i] = X(t[i][0]);
            ys[i] = Y(t[i][1]);
        }
        // the torso: #1a1c2a at the sides, #22253a in the middle (a horizontal ramp of the mockup, in hard steps)
        double[] one = new double[0];
        int steps = 12;
        for (int s = 0; s < steps; s++) {
            double lo = X(0) + (double) s / steps * DW;
            double hi = X(0) + (double) (s + 1) / steps * DW;
            double mid = ((s + .5D) / steps - .5D) * 2;
            int col = FlowRaster.mix(0x22253A, 0x1A1C2A, Math.abs(mid));
            clipPolygon(xs, ys, lo, hi, col, .96D);
        }
        // rim light and the robe folds
        for (int i = 1; i <= t.length; i++) {
            int a = i - 1;
            int b = i % t.length;
            if (ys[a] > Y(1.05D) && ys[b] > Y(1.05D)) {
                continue;
            }
            r.line(xs[a], ys[a], xs[b], ys[b], 1, 0xB4AAD2, .24D, false);
        }
        fold(.43, .2, .5, .5, .46, 1.0);
        fold(.57, .2, .5, .5, .55, 1.0);
        // the dantian glows a little all the time
        r.glowDither(X(.5D), Y(.8D), DH * .09D, 0xFFE6AA, .28D, 5);
    }

    /** Fills the polygon only between two x limits (so a ramp of colours can be laid in vertical bands). */
    private void clipPolygon(double[] xs, double[] ys, double lo, double hi, int rgb, double a) {
        FlowRaster tmp = r;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double y : ys) {
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        double[] cross = new double[xs.length];
        for (int y = Math.max(0, (int) Math.floor(min)); y <= Math.min(tmp.h - 1, (int) Math.ceil(max)); y++) {
            double sy = y + .5D;
            int c = 0;
            for (int i = 0; i < xs.length; i++) {
                int j = (i + 1) % xs.length;
                if ((ys[i] <= sy && ys[j] > sy) || (ys[j] <= sy && ys[i] > sy)) {
                    cross[c++] = xs[i] + (sy - ys[i]) / (ys[j] - ys[i]) * (xs[j] - xs[i]);
                }
            }
            java.util.Arrays.sort(cross, 0, c);
            for (int k = 0; k + 1 < c; k += 2) {
                int xa = (int) Math.max(Math.ceil(cross[k] - .5D), Math.ceil(lo - .5D));
                int xb = (int) Math.min(Math.floor(cross[k + 1] - .5D), Math.ceil(hi - .5D) - 1);
                for (int x = Math.max(0, xa); x <= Math.min(tmp.w - 1, xb); x++) {
                    tmp.over(x, y, rgb, a);
                }
            }
        }
    }

    private void fold(double x0, double y0, double cx, double cy, double x1, double y1) {
        double px = X(x0);
        double py = Y(y0);
        for (int k = 1; k <= 16; k++) {
            double t = k / 16.0D;
            double mt = 1 - t;
            double qx = mt * mt * X(x0) + 2 * mt * t * X(cx) + t * t * X(x1);
            double qy = mt * mt * Y(y0) + 2 * mt * t * Y(cy) + t * t * Y(y1);
            r.line(px, py, qx, qy, 1, 0xFFFFFF, .06D, false);
            px = qx;
            py = qy;
        }
    }

    // ------------------------------------------------------------------ the way

    private double[] pos(double s) {
        double[] p = g.route.at(s);
        return new double[] {X(p[0]), Y(p[1])};
    }

    private double[][] scaled(double[][] pts) {
        double[][] out = new double[pts.length][];
        for (int i = 0; i < pts.length; i++) {
            out[i] = new double[] {X(pts[i][0]), Y(pts[i][1])};
        }
        return out;
    }

    private void route() {
        double[][] way = scaled(g.route.points());
        int c = g.pill.colour();
        int k = g.pill.core();
        // the faint meridian
        r.polyline(way, 3, 0xC8C8FF, .12D, false);
        // the passed part glows
        int steps = (int) Math.floor(g.s * 120);
        double[][] passed = new double[steps + 1][];
        for (int i = 0; i <= steps; i++) {
            passed[i] = pos(i / 120.0D);
        }
        if (steps > 0) {
            r.polyline(passed, 2D, c, .38D, false);
        }
        // the poison spans: green dashes that burn when the flow runs long
        for (double[] span : g.poison) {
            double[][] seg = new double[13][];
            for (int i = 0; i <= 12; i++) {
                seg[i] = pos(span[0] + (span[1] - span[0]) * i / 12.0D);
            }
            r.beads(seg, 1.9D, 2.7D, 0x5FCB5A, .75D, false);
        }
        // the forks: two short branches around the fork point
        for (FlowGame.Fork f : g.forks) {
            double[] centre = g.route.at(f.s);
            double[] dir = g.route.dir(f.s);
            double nx = -dir[1];
            double ny = dir[0];
            for (int side = -1; side <= 1; side += 2) {
                boolean good = side == f.good;
                double ox1 = X(centre[0]) + nx * side * DH * .075D;
                double oy1 = Y(centre[1]) + ny * side * DH * .075D;
                double[] sx = pos(f.s - .05D);
                double[] ex = pos(f.s + .04D);
                double glowA = good ? .55D + .3D * Math.sin(g.t * 5) : .6D;
                int col = good ? k : 0x5A4650;
                double[][] curve = new double[17][];
                for (int i = 0; i <= 16; i++) {
                    double t = i / 16.0D;
                    double mt = 1 - t;
                    curve[i] = new double[] {mt * mt * sx[0] + 2 * mt * t * ox1 + t * t * ex[0],
                            mt * mt * sx[1] + 2 * mt * t * oy1 + t * t * ex[1]};
                }
                r.polyline(curve, good ? 2 : 2.5D, col, glowA, false);
                if (!good) {
                    // knots: a few dark lumps on the blocked branch
                    for (int i = 0; i < 3; i++) {
                        double[] m = curve[4 + i * 4];
                        double rad = 1.6D + ((i * 37 + 13) % 5) * .2D;
                        r.disc(m[0], m[1], rad, 0x2B1D24, .95D, false);
                        r.ring(m[0], m[1], rad, 1, 0x6A3C46, .9D, false);
                    }
                }
                if (!f.done && Math.abs(g.s - f.s) < .08D + FlowGame.FORK_ZONE && f.chosen != 0 && f.sideOf(f.chosen) == side) {
                    r.ring(ox1, oy1, 4.2D, 1, 0xFFFFFF, .8D, false);
                }
                if (!f.done && g.s > f.s - FlowGame.FORK_ZONE - .05D && g.s < f.s + .02D) {
                    // the key name sits outside the bulge of its branch
                    labels.add(new Label(side == f.leftSide ? "fork.left" : "fork.right", ox1 + nx * side * 8, oy1 + ny * side * 8 - 3, 0xFFFFFF, 1));
                }
            }
        }
        // the narrow spots: a ring that closes red
        for (FlowGame.Narrow n : g.narrows) {
            double[] q = pos(n.s);
            double o = (Math.sin(g.t * 6.283D / n.period + n.phase) + 1) / 2;
            double rad = 3.2D + o * 5.2D;
            if (n.open) {
                r.ring(q[0], q[1], rad, 1.0D, c, .6D, false);
            } else {
                r.disc(q[0], q[1], rad, RED, .18D, false);
                r.ring(q[0], q[1], rad, 2D, RED, .95D, false);
            }
        }
    }

    // ------------------------------------------------------------------ the clot

    private void clot() {
        int c = g.pill.colour();
        int k = g.pill.core();
        double[] p = pos(g.s);
        double x = p[0];
        double y = p[1];
        boolean done = g.state == FlowGame.State.DONE;
        // the essence trail: one thick tapered band and two thin filaments (and one wavy filament for every extra pill)
        List<FlowGame.Trail> tr = g.trail;
        int n = tr.size();
        if (n > 2 && g.state != FlowGame.State.TIMEOUT) {
            double fade = done ? Math.max(0.0D, 1 - Math.max(0.0D, g.end - .35D) * 2.5D) : 1.0D;
            for (int i = 1; i < n; i++) {
                FlowGame.Trail a = tr.get(i - 1);
                FlowGame.Trail b = tr.get(i);
                double kk = (double) i / n;
                r.line(X(a.x), Y(a.y), X(b.x), Y(b.y), 1.0D + 4.0D * kk * kk, c, (.07D + .27D * kk * kk) * fade, true);
            }
            for (int ci = 1; ci < g.pill.colours().length; ci++) {
                double[][] fil = new double[n][];
                for (int i = 0; i < n; i++) {
                    double kk = (double) i / n;
                    double w = Math.sin(g.t * 5 + i * .3D + (ci - 1) * 2.1D) * 2.8D * kk;
                    fil[i] = new double[] {X(tr.get(i).x) + w, Y(tr.get(i).y) + w * .6D};
                }
                r.polyline(fil, 1.2D, g.pill.colours()[ci], .85D * fade, true);
            }
            for (int off = -1; off <= 1; off += 2) {
                double[][] thin = new double[n][];
                for (int i = 0; i < n; i++) {
                    double kk = (double) i / n;
                    double w = Math.sin(g.t * 7 + i * .35D + off) * 2.0D * kk;
                    double nx = off * w;
                    thin[i] = new double[] {X(tr.get(i).x) + nx, Y(tr.get(i).y) - nx * .4D};
                }
                r.polyline(thin, 1, k, .55D * fade, true);
            }
        }
        // the clot: a glow in steps, a bright core, orbiting sparks
        boolean surging = g.surging();
        double flick = surging ? (Math.sin(g.t * 60) > 0 ? 1.8D : 1.0D) : 1.0D;
        double rr = DH * .028D * (done ? 1 + Math.min(g.end, .35D) * 2.5D : 1.0D);
        double alpha = done ? Math.max(0.0D, 1 - Math.max(0.0D, g.end - .35D) * 2.5D) : 1.0D;
        if (g.state == FlowGame.State.RUN || done) {
            r.glowDither(x, y, rr * 3.4D * flick, c, .75D * alpha, 9);
            r.glow(x, y, rr * 1.7D * flick, k, .5D * alpha, 4);
            r.disc(x, y, rr * .75D, k, alpha, true);
            for (int ci = 1; ci < g.pill.colours().length; ci++) {
                double a = g.t * 4 + (ci - 1) * 3.1D;
                double bx = x + Math.cos(a) * rr * 1.5D;
                double by = y + Math.sin(a) * rr * 1.5D;
                r.glow(bx, by, rr * 1.6D, g.pill.colours()[ci], .7D * alpha, 3);
                r.disc(bx, by, rr * .5D, g.pill.cores()[ci], alpha, true);
            }
            for (int i = 0; i < 6; i++) {
                double a = g.t * 3 + i * 1.05D;
                double d = rr * (1.9D + Math.sin(g.t * 5 + i) * .5D);
                r.rect((int) Math.round(x + Math.cos(a) * d), (int) Math.round(y + Math.sin(a) * d),
                        (int) Math.round(x + Math.cos(a) * d) + 1, (int) Math.round(y + Math.sin(a) * d) + 1, k, .8D * alpha, true);
            }
        }
        if (g.state == FlowGame.State.RUN) {
            if (surging) {
                labels.add(new Label("hold", x, y - rr * 3.8D, 0xFFFFFF, 1, 1.0F));
            }
            if (g.bounceT >= 0) {
                labels.add(new Label("blocked", x, y - rr * 3.8D, 0xFFB3BC, 1, 1.0F));
            }
        }
        if (done) {
            finale();
        }
        if (g.state == FlowGame.State.BURST) {
            r.rect(0, 0, r.w, r.h, RED, Math.max(0.0D, (flashes ? .5D : .12D) - g.end * .3D), false);
        }
    }

    // ------------------------------------------------------------------ torn and finale

    private static double ease(double t) {
        return 1 - Math.pow(1 - Math.max(0.0D, Math.min(1.0D, t)), 3);
    }

    private void torn() {
        double dx = X(.5D);
        double dy = Y(.8D);
        double k = Math.min(1.0D, g.end * 1.2D);
        for (int i = 0; i < 14; i++) {
            double a = i / 14.0D * 6.283D + .2D;
            double rad = DH * .05D + DH * .45D * ease(k);
            r.line(dx, dy, dx + Math.cos(a) * rad, dy + Math.sin(a) * rad * .8D, 1 + 2 * (1 - k), g.pill.colour(), .8D * (1 - k), true);
        }
        r.rect(0, 0, r.w, r.h, RED, (flashes ? .28D : .1D) * (1 - k * .5D), false);
        labels.add(new Label("torn", r.w / 2.0D, oy + DH * .1D, 0xFFFFFF, 1, 1.25F));
    }

    /** After the Peak notes of 23.10: 3-4 tiny four-point stars flicker in different places, then one big «bam», sharp rise and slow fall. */
    private void finale() {
        double e = g.end;
        int k = g.pill.core();
        int c = g.pill.colour();
        double cx = X(.5D);
        double cy = Y(.62D);
        if (e < .5D) {
            double[][] spots = {{.42, .5}, {.6, .4}, {.47, .3}, {.56, .66}};
            for (int i = 0; i < spots.length; i++) {
                double t0 = i * .11D;
                if (e > t0 && e < t0 + .12D) {
                    double kk = (e - t0) / .12D;
                    r.star(X(spots[i][0]), Y(spots[i][1]), DH * .03D * (kk < .5D ? kk * 2 : 2 - kk * 2) + 2, k, .9D);
                }
            }
        }
        if (e >= .45D) {
            double t = e - .45D;
            double up = Math.min(1.0D, t / .12D);
            double down = t > .12D ? Math.max(0.0D, 1 - (t - .12D) / 1.3D) : 1.0D;
            double rad = DH * .42D * ease(up) * down;
            if (rad > 1) {
                r.star(cx, cy, rad, k, .9D);
                r.glowDither(cx, cy, rad * .9D, c, .6D * down, 10);
            }
            if (down > 0) {
                for (int i = 0; i < 16; i++) {
                    double a = i / 16.0D * 6.283D + (i % 2) * .1D;
                    double l = DH * (.12D + .3D * ((i * 7) % 5) / 5.0D) * ease(up) * down;
                    r.line(cx + Math.cos(a) * rad * .25D, cy + Math.sin(a) * rad * .25D, cx + Math.cos(a) * (rad * .25D + l),
                            cy + Math.sin(a) * (rad * .25D + l), i % 3 == 0 ? 2 : 1, k, .5D * down, true);
                }
            }
        }
        // the body keeps a soft glow afterwards
        r.glowDither(X(.5D), Y(.8D), DH * .35D, c, .24D, 8);
    }

    // ------------------------------------------------------------------ hud

    private void hud() {
        double pr = g.pressure / 100.0D;
        if (pr > 0.0D) {
            double cx = r.w / 2.0D;
            double cy = r.h / 2.0D;
            for (int y = 0; y < r.h; y++) {
                for (int x = 0; x < r.w; x++) {
                    double d = Math.hypot(x + .5D - cx, y + .5D - cy);
                    double t = Math.max(0.0D, Math.min(1.0D, (d - DH * .3D) / (DH * .5D)));
                    t = Math.floor(t * 12.0D) / 12.0D;
                    if (t > 0.0D) {
                        r.over(x, y, RED, .5D * pr * t);
                    }
                }
            }
        }
        if (g.state != FlowGame.State.TORN) {
            // the pressure bar (top left of the picture) and the dissolve ring (top right)
            int bx = ox + 5;
            int by = oy + 5;
            int bw = 54;
            r.rect(bx - 1, by - 1, bx + bw + 1, by + 5, 0x000000, .55D, false);
            r.rect(bx, by, bx + (int) Math.round(bw * pr), by + 4, pr > .7D ? RED_HOT : RED, 1.0D, false);
            labels.add(new Label("pressure", bx, by + 7, 0xCFC9DA, 0));
            double tl = Math.max(0.0D, 1 - g.t / g.time);
            double rx = ox + DW - 13;
            double ry = oy + 13;
            r.ring(rx, ry, 5.5D, 2, 0xFFFFFF, .18D, false);
            if (tl > 0.0D) {
                r.arc(rx, ry, 5.5D, 2, 0, Math.PI * 2 * tl, g.pill.colour(), .95D, false);
            }
            labels.add(new Label("dissolve", rx - 9, ry - 3, 0xCFC9DA, 2));
        }
        if (g.hard && g.state == FlowGame.State.RUN) {
            labels.add(new Label("hard", r.w / 2.0D, oy + 9, 0xFFB3BC, 1));
        }
        if (g.flash > 0 && flashes) {
            r.rect(0, 0, r.w, r.h, 0xFFFFFF, g.flash * .5D, false);
        }
    }
}
