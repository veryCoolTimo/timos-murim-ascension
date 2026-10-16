package io.github.verycooltimo.murim.world.hua;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure terrain checks, also runnable with javac/JUnit without a Minecraft runtime. */
class MountHuaCeremonialAxisTest {
    private final MountHuaShape shape = new MountHuaShape(20261003L);

    @Test
    void ceremonialFlightsUseNineLanesAndOneHighSideCurbs() {
        for (int index : new int[] {3, 4}) {
            double[] p = MountHuaPlan.PATHS[index];
            assertEquals(9, MountHuaPlan.pathWidth(p));
            assertTrue(Double.isInfinite(MountHuaShape.pathAt(p, p[0], p[1] - 0.5)[0]), "flight spills onto lower court");
            assertTrue(Double.isInfinite(MountHuaShape.pathAt(p, p[3], p[4] + 0.5)[0]), "flight spills onto upper landing");
            for (int u = 1; u <= 10; u++) {
                double previous = p[2];
                for (double v = p[1] + 0.5; v < p[4]; v++) {
                    double y = MountHuaShape.pathAt(p, u + 0.5, v)[1];
                    assertEquals(y, shape.height(u + 0.5, v), 0, "lane " + u);
                    assertTrue(Math.abs(y - previous) <= 1);
                    previous = y;
                }
            }
            for (double u : new double[] {0.5, 11.5}) for (double v = p[1] + 0.5; v < p[4]; v++) {
                assertTrue(MountHuaShape.pathCheek(p, u, v));
                assertEquals(MountHuaShape.pathAt(p, u, v)[1] + 1, shape.height(u, v), 0);
            }
        }
        assertEquals(4, MountHuaPlan.pathWidth(MountHuaPlan.PATHS[0]));
        assertEquals(2.1, MountHuaPlan.pathHalfWidth(MountHuaPlan.PATHS[0]), 0);
    }

    @Test
    void allNineLanesJoinTheTrainingSquareLandingAndHallInEveryRotation() {
        for (int rotation = 0; rotation < 4; rotation++) {
            var site = new MountHuaSite(100, 100, 70, rotation, 20261003L);
            for (int u = 1; u <= 10; u++) {
                long previous = Math.round(site.worldY(155));
                for (int v = 34; v <= 46; v++) {
                    int[] w = site.toWorld(u + 0.5, v + 0.5);
                    double ru = site.localU(w[0] + 0.5, w[1] + 0.5);
                    double rv = site.localV(w[0] + 0.5, w[1] + 0.5);
                    assertEquals(u + 0.5, ru, 0);
                    assertEquals(v + 0.5, rv, 0);
                    long y = Math.round(site.worldY(shape.height(ru, rv)));
                    assertTrue(y >= previous && y - previous <= 1, "disconnected lane " + u + " at " + v);
                    if (v >= 38 && v < 43) assertEquals(Math.round(site.worldY(158)), y);
                    previous = y;
                }
                assertEquals(Math.round(site.worldY(161)), previous);
            }
        }
    }

    @Test
    void authoredSquareAndAxisStayFixedWhileTheHallKeepsRoofClearance() {
        var mentor = MountHuaPlan.ZONES.stream().filter(z -> z.id().equals("mentor")).findFirst().orElseThrow();
        var training = MountHuaPlan.ZONES.stream().filter(z -> z.id().equals("training")).findFirst().orElseThrow();
        var hall = MountHuaPlan.ZONES.stream().filter(z -> z.id().equals("main_hall")).findFirst().orElseThrow();
        assertEquals(-12, mentor.u());
        assertEquals(12, mentor.v());
        assertTrue(Math.abs(mentor.u() - training.u()) + mentor.width() / 2.0 <= training.width() / 2.0);
        assertTrue(Math.abs(mentor.v() - training.v()) + mentor.depth() / 2.0 <= training.depth() / 2.0);
        assertEquals(training.y(), mentor.y());
        assertEquals(77, hall.v() + hall.depth() / 2.0);
        assertEquals(46, hall.v() - hall.depth() / 2.0);
        assertEquals(6, training.u());
        assertEquals(11.5, training.v());
        assertEquals(48, training.width());
        assertEquals(47, training.depth());
        var gate = MountHuaPlan.ZONES.stream().filter(z -> z.id().equals("sect_gate")).findFirst().orElseThrow();
        assertEquals(14, gate.u());
        assertEquals(-24, gate.v());
        assertEquals(28, gate.width());
        assertEquals(10, gate.depth());
        var arch = MountHuaPlan.ZONES.stream().filter(z -> z.id().equals("arch")).findFirst().orElseThrow();
        assertEquals(6, arch.u());
        assertEquals(40.5, arch.v());
        assertEquals(13, arch.width());
        assertEquals(5, arch.depth());
        assertEquals(158, arch.y());
        var poles = MountHuaPlan.ZONES.stream().filter(z -> z.id().equals("poles")).findFirst().orElseThrow();
        assertEquals(-10, poles.u());
        assertEquals(27, poles.v());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new double[] {6, 35, 155, 6, 38, 158, 9}, MountHuaPlan.PATHS[3]);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new double[] {6, 43, 158, 6, 46, 161, 9}, MountHuaPlan.PATHS[4]);
        for (double u = mentor.u() - mentor.width() / 2.0 + 0.5; u < mentor.u() + mentor.width() / 2.0; u++) {
            for (double v = mentor.v() - mentor.depth() / 2.0 + 0.5; v < mentor.v() + mentor.depth() / 2.0; v++) {
                assertEquals(training.y(), shape.height(u, v), 0);
            }
        }
    }
}
