package io.github.verycooltimo.murim.client.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.client.vfx.fx.FxTune;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * THE ONE PLACE of the scroll's route: a table of points in the UV space of the figure picture ({@code textures/gui/flow/scroll_body.png}),
 * read from {@code assets/murim/flow/scroll_route.json}. When the figure art is swapped only this json is adjusted. While live tuning is on
 * ({@link FxTune#ON}) every number can also be moved by a key: {@code flow.scroll.pt.<i>.u|v} for a point, {@code flow.scroll.fig.x|y|w|aspect} for the placement.
 * The route is resampled by arc length so the game's {@code s} (a share of the way) maps straight to a point.
 */
final class FlowScrollTable {
    private FlowScrollTable() { }

    static final int N = 480;
    private static double[][] pts;
    private static double figX = .04, figY = .02, figW = .92, aspect = .9;
    private static Route route;

    /** Placement of the figure on the paper (shares of the paper's width, height from the aspect). */
    record Fig(double x, double y, double w, double aspect) { }

    static final class Route {
        final double[] u = new double[N + 1], v = new double[N + 1];
        final double aspect;

        Route(double[][] p, double aspect) {
            this.aspect = aspect;
            // Catmull-Rom through the points, dense, then resampled by arc length
            int m = p.length, per = 24;
            double[][] dense = new double[(m - 1) * per + 1][];
            for (int i = 0; i < m - 1; i++) {
                double[] p0 = p[Math.max(0, i - 1)], p1 = p[i], p2 = p[i + 1], p3 = p[Math.min(m - 1, i + 2)];
                for (int k = 0; k < per; k++) {
                    double t = k / (double) per, t2 = t * t, t3 = t2 * t;
                    dense[i * per + k] = new double[] {
                            .5 * (2 * p1[0] + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3),
                            .5 * (2 * p1[1] + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)};
                }
            }
            dense[dense.length - 1] = p[m - 1].clone();
            double[] cum = new double[dense.length];
            for (int i = 1; i < dense.length; i++) cum[i] = cum[i - 1] + Math.hypot((dense[i][0] - dense[i - 1][0]) * aspect, dense[i][1] - dense[i - 1][1]);
            int j = 1;
            for (int i = 0; i <= N; i++) {
                double d = cum[cum.length - 1] * i / N;
                while (j < dense.length - 1 && cum[j] < d) j++;
                double f = (d - cum[j - 1]) / Math.max(1e-12, cum[j] - cum[j - 1]);
                f = Math.max(0, Math.min(1, f));
                u[i] = dense[j - 1][0] + (dense[j][0] - dense[j - 1][0]) * f;
                v[i] = dense[j - 1][1] + (dense[j][1] - dense[j - 1][1]) * f;
            }
        }

        /** Point at {@code s} in 0..1: {u, v}. */
        double[] at(double s) {
            double f = Math.max(0, Math.min(1, s)) * N;
            int i = Math.min(N - 1, (int) f);
            double t = f - i;
            return new double[] {u[i] + (u[i + 1] - u[i]) * t, v[i] + (v[i + 1] - v[i]) * t};
        }

        /** Unit direction at {@code s} in figure UV scaled by the aspect (so it is a true screen direction): {dx, dy}. */
        double[] dir(double s) {
            double[] a = at(Math.max(0, s - .006)), b = at(Math.min(1, s + .006));
            double dx = (b[0] - a[0]) * aspect, dy = b[1] - a[1], l = Math.max(1e-9, Math.hypot(dx, dy));
            return new double[] {dx / l, dy / l};
        }
    }

    private static void load() {
        try (var in = FlowScrollTable.class.getResourceAsStream("/assets/murim/flow/scroll_route.json")) {
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject f = o.getAsJsonObject("figure");
            figX = f.get("x").getAsDouble(); figY = f.get("y").getAsDouble(); figW = f.get("w").getAsDouble(); aspect = f.get("aspect").getAsDouble();
            JsonArray r = o.getAsJsonArray("route");
            pts = new double[r.size()][];
            for (int i = 0; i < pts.length; i++) pts[i] = new double[] {r.get(i).getAsJsonArray().get(0).getAsDouble(), r.get(i).getAsJsonArray().get(1).getAsDouble()};
        } catch (Exception e) {
            io.github.verycooltimo.murim.MurimMod.LOGGER.warn("scroll_route.json: {}", e.toString());
            pts = new double[][] {{.5, .66}, {.5, .3}, {.5, .66}};
        }
    }

    /** The route (rebuilt every frame while live tuning is on, once otherwise). */
    static Route route() {
        if (pts == null) load();
        if (route == null || FxTune.ON) {
            double[][] p = pts;
            if (FxTune.ON) {
                p = new double[pts.length][];
                for (int i = 0; i < pts.length; i++) p[i] = new double[] {FxTune.f("flow.scroll.pt." + i + ".u", (float) pts[i][0]), FxTune.f("flow.scroll.pt." + i + ".v", (float) pts[i][1])};
            }
            route = new Route(p, fig().aspect());
        }
        return route;
    }

    static Fig fig() {
        if (pts == null) load();
        return new Fig(FxTune.f("flow.scroll.fig.x", (float) figX), FxTune.f("flow.scroll.fig.y", (float) figY), FxTune.f("flow.scroll.fig.w", (float) figW),
                FxTune.f("flow.scroll.fig.aspect", (float) aspect));
    }
}
