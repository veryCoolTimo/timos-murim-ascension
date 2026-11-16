package io.github.verycooltimo.murim.client.flow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PillVeinTreeTest {
    @Test void everyPointStaysInsideTheTorso() {
        for (int detail = 1; detail <= 3; detail++) {
            for (var b : new PillVeinTree(1114, detail).branches()) {
                for (double[] p : b.pts()) {
                    assertTrue(p[1] >= PillVeinTree.U_MIN - 1e-9 && p[1] <= PillVeinTree.U_MAX + 1e-9, "u " + p[1]);
                    assertTrue(Math.abs(p[0]) <= PillVeinTree.HALF + 1e-9, "x " + p[0]);
                }
            }
        }
    }

    @Test void itBranchesAndIsNotAStick() {
        var t = new PillVeinTree(1114, 3);
        assertTrue(t.branches().size() >= 25, "branches " + t.branches().size());
        int maxDepth = t.branches().stream().mapToInt(PillVeinTree.Branch::depth).max().orElse(0);
        assertTrue(maxDepth >= 2);
        // The trunk is jagged: it leaves the centre line.
        double widest = t.branches().getFirst().pts().stream().mapToDouble(p -> Math.abs(p[0])).max().orElse(0);
        assertTrue(widest > .1);
    }

    @Test void growthClockIsOrderedAndFitsOne() {
        var t = new PillVeinTree(1114, 3);
        double end = 0;
        for (var b : t.branches()) {
            assertTrue(b.start() >= 0 && b.dur() > 0);
            if (b.parent() >= 0) assertTrue(b.start() >= t.branches().get(b.parent()).start() - 1e-9);
            end = Math.max(end, b.start() + b.dur());
        }
        assertEquals(1.0, end, 1e-9);
    }

    @Test void sameSeedSameTree() {
        var a = new PillVeinTree(7, 2).branches();
        var b = new PillVeinTree(7, 2).branches();
        assertEquals(a.size(), b.size());
        assertArrayEquals(a.get(3).pts().get(1), b.get(3).pts().get(1));
    }

    @Test void wavesTightenOnTheBellyAndSpreadOnTheBurst() {
        for (int i = 0; i < PillWaves.MAX_LOOPS; i++) {
            double[] rest = PillWaves.point(i, 1.0, 0, 0), tight = PillWaves.point(i, 1.0, 1, 0), wide = PillWaves.point(i, 1.0, 0, 1);
            assertTrue(Math.hypot(tight[0], tight[2]) < Math.hypot(rest[0], rest[2]) * .5);
            assertTrue(Math.hypot(wide[0], wide[2]) > Math.hypot(rest[0], rest[2]) * 2);
        }
    }

    /** Writes the tree for the lab pictures (tools/vfx-lab/pill_vein_lab.py) to build/lab/pillveintree.json. */
    @Test void dumpForTheLab() throws Exception {
        var sb = new StringBuilder("[");
        boolean first = true;
        for (var b : new PillVeinTree(1114, 2).branches()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"depth\":").append(b.depth()).append(",\"start\":").append(b.start()).append(",\"dur\":").append(b.dur())
                    .append(",\"width\":").append(b.width()).append(",\"pts\":[");
            for (int i = 0; i < b.pts().size(); i++) sb.append(i > 0 ? "," : "").append('[').append(b.pts().get(i)[0]).append(',').append(b.pts().get(i)[1]).append(']');
            sb.append("]}");
        }
        sb.append(']');
        var dir = java.nio.file.Path.of("build", "lab");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("pillveintree.json"), sb.toString());
    }
}
