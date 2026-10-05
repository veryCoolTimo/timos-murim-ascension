package io.github.verycooltimo.murim.world.hua;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mount Hua height function: determinism, layout, trail and terraces, world placement. */
class MountHuaShapeTest {

    private final MountHuaShape shape = new MountHuaShape(20261003L);

    @Test
    void deterministicForTheSameSeed() {
        MountHuaShape other = new MountHuaShape(20261003L);
        for (int i = 0; i < 400; i++) {
            double u = -400 + (i * 37) % 800;
            double v = -500 + (i * 53) % 1000;
            assertEquals(shape.height(u, v), other.height(u, v), 0.0);
        }
    }

    @Test
    void southPeakIsTheHighestAndNorthPeakTheLowestOfTheFive() {
        double south = top(5, 125);
        double north = top(-10, -335);
        for (MountHuaPlan.Peak p : MountHuaPlan.PEAKS.subList(0, 5)) {
            double h = top(p.u(), p.v());
            assertTrue(h <= south + 0.5, p.name() + " " + h + " > south " + south);
            assertTrue(h >= north - 0.5, p.name() + " " + h + " < north " + north);
        }
        assertTrue(south > 225, "south " + south);
        assertTrue(north < 175, "north " + north);
    }

    @Test
    void terracesAreFlat() {
        for (MountHuaPlan.Zone z : MountHuaPlan.ZONES) {
            if (z.cave() || z.id().equals("grove")) { // the grove is a wooded outcrop, not a pad
                continue;
            }
            for (double du = -z.width() / 2.0 + 0.5; du < z.width() / 2.0; du += 3) {
                for (double dv = -z.depth() / 2.0 + 0.5; dv < z.depth() / 2.0; dv += 3) {
                    assertEquals(z.y(), shape.height(z.u() + du, z.v() + dv), 1e-9, z.id());
                }
            }
        }
    }

    @Test
    void trailClimbsNoSteeperThanStairs() {
        double[] prev = null;
        for (int i = 0; i < 4000; i++) {
            double y = shape.trailY(i);
            if (prev != null && i < 4000) {
                assertTrue(Math.abs(y - prev[0]) <= 1.0 + 1e-9, "step at " + i);
            }
            prev = new double[] {y};
        }
        assertEquals(MountHuaPlan.TRAIL.get(0).y(), shape.trailY(0), 1e-9);
    }

    @Test
    void outsideTheFootprintTheWorldIsUntouched() {
        assertEquals(0, shape.blend(MountHuaShape.MAX_U + 10, 0));
        assertEquals(0, shape.blend(0, MountHuaShape.MIN_V - 10));
        assertEquals(0, shape.height(MountHuaShape.MAX_U + 10, 0));
    }

    @Test
    void siteRotationRoundTrips() {
        for (int rot = 0; rot < 4; rot++) {
            MountHuaSite site = new MountHuaSite(3000, -1200, 70, rot, 1L);
            int[] w = site.toWorld(37, -112);
            assertEquals(37, site.localU(w[0], w[1]), 1e-9);
            assertEquals(-112, site.localV(w[0], w[1]), 1e-9);
            assertEquals(MountHuaSite.SUMMIT_Y, site.worldY(MountHuaPlan.SUMMIT), 1e-9);
        }
        // The trail foot (local north) faces the spawn.
        assertEquals(0, MountHuaSites.rotationTowards(0, -3000));
        assertEquals(1, MountHuaSites.rotationTowards(3000, 10));
        assertEquals(2, MountHuaSites.rotationTowards(5, 3000));
        assertEquals(3, MountHuaSites.rotationTowards(-3000, 0));
    }

    /**
     * Walks the stair along its centre line and both edges (width 4): the generated ground must
     * follow the planned stair everywhere (no dead ends, no blocks across the path) and never
     * rise or fall more than one block per step (author 04.10: «лестница обрывается резко»).
     */
    @Test
    void trailIsContinuousAndEven() {
        int n = shape.trailLength();
        double scale = (MountHuaSite.SUMMIT_Y - 70) / MountHuaPlan.SUMMIT;
        java.util.List<String> bad = new java.util.ArrayList<>();
        long prev = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            double[] p = shape.trailPoint(i);
            double[] d = shape.trailDir(i);
            double nu = -d[1];
            double nv = d[0];
            long planned = Math.round(70 + shape.trailY(i) * scale);
            for (double off : new double[] {-1.5, 0, 1.5}) {
                long ground = Math.round(70 + shape.height(p[0] + nu * off, p[1] + nv * off) * scale);
                // Centre line exactly on the plan; the edges may differ by one step (inside of a turn).
                long tolerance = off == 0 ? 0 : 1;
                if (Math.abs(ground - planned) > tolerance && bad.size() < 20) {
                    bad.add(String.format("i=%d off=%.1f at (%.0f,%.0f): ground %d planned %d", i, off, p[0], p[1], ground, planned));
                }
            }
            if (prev != Long.MIN_VALUE && Math.abs(planned - prev) > 1 && bad.size() < 20) {
                bad.add(String.format("i=%d step %d -> %d", i, prev, planned));
            }
            prev = planned;
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    /**
     * The approved terrain stays as it was outside the climb face: every pad (+2 blocks) and the
     * stair are bit-identical with and without the climb, and nothing changes outside its box.
     */
    @Test
    void climbOnlyChangesItsOwnFace() {
        MountHuaShape bare = new MountHuaShape(20261003L, false);
        java.util.List<String> bad = new java.util.ArrayList<>();
        int changed = 0;
        for (double u = -160.5; u <= 160; u++) {
            for (double v = -110.5; v <= 220; v++) {
                double a = shape.height(u, v);
                double b = bare.height(u, v);
                if (a == b) {
                    continue;
                }
                changed++;
                if (!MountHuaShape.inClimbBox(u, v) && bad.size() < 10) {
                    bad.add(String.format("outside the box at (%.1f,%.1f): %.2f -> %.2f", u, v, b, a));
                }
                for (MountHuaPlan.Zone z : MountHuaPlan.ZONES) {
                    if (Math.abs(u - z.u()) <= z.width() / 2.0 + 2 && Math.abs(v - z.v()) <= z.depth() / 2.0 + 2
                            && bad.size() < 10) {
                        bad.add(String.format("pad %s changed at (%.1f,%.1f)", z.id(), u, v));
                    }
                }
            }
        }
        for (int i = 0; i < shape.trailLength(); i++) {
            assertEquals(bare.trailY(i), shape.trailY(i), 0.0, "stair sample " + i);
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
        assertTrue(changed > 500, "the climb carved only " + changed + " columns");
    }

    /**
     * The route can be climbed: every ledge has a flat stand spot; each next ledge is 2-6 blocks
     * higher (world heights, for any foot height) and touches the previous one within two blocks;
     * the first rises from the ancestors' terrace and the summit rim is one step above the last.
     */
    @Test
    void climbRouteIsClimbable() {
        java.util.List<MountHuaPlan.Ledge> route = MountHuaPlan.CLIMB.stream().filter(MountHuaPlan.Ledge::onRoute).toList();
        assertEquals(16, route.size());
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (MountHuaPlan.Ledge l : MountHuaPlan.CLIMB) {
            double[] s = shape.climbSpot(l);
            if (s == null) {
                bad.add(l.id() + ": no flat spot");
            } else if (shape.height(s[0], s[1]) != l.y() || shape.climbLedgeAt(s[0], s[1]) != l) {
                bad.add(l.id() + ": spot not on the ledge top");
            }
            if (l.kind() == MountHuaPlan.Kind.REST && shape.climbSeat(l) == null) {
                bad.add(l.id() + ": rest without a seat");
            }
        }
        for (int base : new int[] {63, 80, 100}) {
            double scale = (MountHuaSite.SUMMIT_Y - base) / MountHuaPlan.SUMMIT;
            double prevY = 172; // the ancestors' terrace
            for (MountHuaPlan.Ledge l : route) {
                long rise = Math.round(base + l.y() * scale) - Math.round(base + prevY * scale);
                if (rise < 2 || rise > 6) {
                    bad.add(String.format("base %d: %s rises %d", base, l.id(), rise));
                }
                prevY = l.y();
            }
        }
        for (int i = 0; i + 1 < route.size(); i++) {
            double gap = gapBetween(route.get(i), route.get(i + 1));
            if (gap > 2.25) { // one lip column between, straight or diagonal
                bad.add(String.format("%s -> %s: %.1f blocks apart", route.get(i).id(), route.get(i + 1).id(), gap));
            }
        }
        // First ledge right behind the ancestors' terrace; summit rim one step above the last ledge.
        MountHuaPlan.Ledge first = route.get(0);
        MountHuaPlan.Ledge last = route.get(route.size() - 1);
        boolean start = false;
        boolean rim = false;
        for (double u = -40.5; u <= 50; u++) {
            for (double v = 96.5; v <= 122; v++) {
                if (shape.climbLedgeAt(u, v) == first && shape.height(u, v) == first.y()
                        && (shape.height(u, v - 1) == 172 || shape.height(u, v - 2) == 172)) {
                    start = true;
                }
                if (shape.climbLedgeAt(u, v) == last && shape.height(u, v) == last.y()) {
                    double up = shape.height(u, v + 1);
                    if (up >= last.y() + 2 && up <= last.y() + 5.5 && shape.climbLedgeAt(u, v + 1) == null) {
                        rim = true;
                    }
                }
            }
        }
        if (!start) {
            bad.add("climb_1 does not border the ancestors' terrace");
        }
        if (!rim) {
            bad.add("no step from climb_16 up to the summit rim");
        }
        assertEquals(3, shape.climbRopes().size(), "rope posts at the three 5-block steps");
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    /** Smallest distance between flat top columns of two ledges. */
    private double gapBetween(MountHuaPlan.Ledge a, MountHuaPlan.Ledge b) {
        java.util.List<double[]> pa = flatColumns(a);
        java.util.List<double[]> pb = flatColumns(b);
        double best = Double.MAX_VALUE;
        for (double[] p : pa) {
            for (double[] q : pb) {
                best = Math.min(best, Math.hypot(p[0] - q[0], p[1] - q[1]));
            }
        }
        return best;
    }

    private java.util.List<double[]> flatColumns(MountHuaPlan.Ledge l) {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        for (double u = Math.floor(l.u() - l.ru() - 2) + 0.5; u <= l.u() + l.ru() + 2; u++) {
            for (double v = Math.floor(l.v() - l.rv() - 2) + 0.5; v <= l.v() + l.rv() + 2; v++) {
                if (shape.climbLedgeAt(u, v) == l && shape.height(u, v) == l.y()) {
                    out.add(new double[] {u, v});
                }
            }
        }
        return out;
    }

    private double top(double u, double v) {
        double best = 0;
        for (int du = -12; du <= 12; du += 2) {
            for (int dv = -12; dv <= 12; dv += 2) {
                best = Math.max(best, shape.height(u + du, v + dv));
            }
        }
        return best;
    }
}
