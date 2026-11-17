package io.github.verycooltimo.murim.client.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The route of the scroll game must lie inside the figure's arms and trunk, away from the outline and the marks, and must not cross itself. The test samples the curve the
 * board really draws (the same Catmull-Rom through the table, resampled by arc length: {@link FlowScrollTable#route()}) every board pixel, at the board's scale (the art is drawn
 * at 2x), and reads the same clearance field the board uses for its forks ({@link FlowScrollTable#clearance}).
 */
class FlowScrollRouteTest {
    /** Board pixels per art pixel: the figure is drawn at 2x of its art. */
    private static final double SCALE = 2.0;

    @Test
    void everySampleOfTheDrawnCurveStaysInsideTheFigureWithMargin() {
        FlowScrollTable.Route r = FlowScrollTable.route();
        FlowScrollTable.Fig fig = FlowScrollTable.fig();
        double halfWidth = FlowScrollTable.CHANNEL_ART * SCALE / 2, need = (halfWidth + 2.0) / SCALE;   // art px
        int n = FlowScrollTable.N, bad = 0, samples = 0;
        double worst = 1e9, worstU = 0, worstV = 0;
        for (int i = 0; i < n; i++) {
            double du = (r.u[i + 1] - r.u[i]) * fig.artW(), dv = (r.v[i + 1] - r.v[i]) * fig.artH();
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(du, dv) * SCALE));   // one sample per board pixel
            for (int k = 0; k < steps; k++) {
                double t = k / (double) steps, u = r.u[i] + (r.u[i + 1] - r.u[i]) * t, v = r.v[i] + (r.v[i + 1] - r.v[i]) * t;
                double c = FlowScrollTable.clearance(u, v);
                samples++;
                if (c < worst) { worst = c; worstU = u; worstV = v; }
                if (c < need) bad++;
            }
        }
        System.out.printf("scroll route: %d samples, need %.2f art px of clearance, worst %.2f at (%.3f, %.3f), %d too close%n", samples, need, worst, worstU, worstV, bad);
        assertTrue(samples > 800, "the route is sampled every board pixel");
        assertEquals(0, bad, "samples closer than channel half width + 2 px to the figure's edge or marks (worst " + worst + " art px at " + worstU + ", " + worstV + ")");
    }

    @Test
    void theRouteIsAClosedLoopThatDoesNotCrossItself() {
        FlowScrollTable.Route r = FlowScrollTable.route();
        int n = FlowScrollTable.N;
        assertEquals(r.u[0], r.u[n], 1e-6);
        assertEquals(r.v[0], r.v[n], 1e-6);
        double a = r.aspect;
        int crossings = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 8; j < n; j++) {
                if (i == 0 && j >= n - 8) continue;                  // the end meets the start
                if (cross(r.u[i] * a, r.v[i], r.u[i + 1] * a, r.v[i + 1], r.u[j] * a, r.v[j], r.u[j + 1] * a, r.v[j + 1])) crossings++;
            }
        }
        assertEquals(0, crossings, "the route crosses itself");
    }

    @Test
    void twoParts_ofTheRouteNeverOverlap_theTurnAtAPalmIncluded() {
        FlowScrollTable.Route r = FlowScrollTable.route();
        FlowScrollTable.Fig fig = FlowScrollTable.fig();
        double min = 1e9, apart = FlowScrollTable.CHANNEL_ART;   // art px between the centres of two parts of the route at the very least: the channels do not overlap
        int n = FlowScrollTable.N;
        for (int i = 0; i < n; i += 2) for (int j = i + 40; j < n; j += 2) {
            if (i < 12 && j > n - 12) continue;
            double d = Math.hypot((r.u[i] - r.u[j]) * fig.artW(), (r.v[i] - r.v[j]) * fig.artH());
            if (d < min) min = d;
        }
        System.out.printf("closest two parts of the route: %.1f art px%n", min);
        assertTrue(min >= apart, "two parts of the route come closer than " + apart + " art px: " + min);
    }

    private static boolean cross(double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy) {
        double d1 = o(cx, cy, dx, dy, ax, ay), d2 = o(cx, cy, dx, dy, bx, by), d3 = o(ax, ay, bx, by, cx, cy), d4 = o(ax, ay, bx, by, dx, dy);
        return d1 * d2 < 0 && d3 * d4 < 0;
    }

    private static double o(double ax, double ay, double bx, double by, double cx, double cy) { return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax); }
}
