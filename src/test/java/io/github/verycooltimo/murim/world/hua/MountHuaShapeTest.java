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
            if (z.cave()) {
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
