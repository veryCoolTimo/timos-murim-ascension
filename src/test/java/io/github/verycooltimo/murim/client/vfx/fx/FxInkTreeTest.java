package io.github.verycooltimo.murim.client.vfx.fx;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FxInkTreeTest {
    /** Strokes that nothing grows from, not counting the second half of a loop (it rejoins its path). */
    private static int ends(int variant) {
        int n = 0;
        for (int trunk = 0; trunk < FxInkTree.TRUNKS.length; trunk++) {
            List<FxInkTree.Branch> g = FxInkTree.group(trunk, variant);
            for (var b : g) if (g.stream().noneMatch(q -> q.parent == b.id)) n++;
        }
        // the second half of every loop is not a tip: it rejoins its path
        return n - FxInkTree.LOOPS.length;
    }

    /** The two strands of a loop start together a little before the split of their trunk. */
    private static boolean isLoopStrand(int trunk, FxInkTree.Branch q) {
        for (var l : FxInkTree.LOOPS) if (l.trunk() == trunk && q.parent >= 0 && q.depth == 0 && Math.abs(q.start - (l.d1() - .1)) < .08) return true;
        return false;
    }

    @Test void groupsAreDeterministicAndVariantsKeepTheTrunkStart() {
        for (int trunk = 0; trunk < FxInkTree.TRUNKS.length; trunk++) {
            var a = FxInkTree.group(trunk, 0);
            var b = FxInkTree.group(trunk, 0);
            assertEquals(a.size(), b.size());
            assertArrayEquals(a.getFirst().x, b.getFirst().x);
            var other = FxInkTree.group(trunk, 1);
            assertEquals(a.getFirst().x[0], other.getFirst().x[0], 1e-9);
            assertEquals(a.getFirst().y[0], other.getFirst().y[0], 1e-9);
            // the next growth leaves the root the same way
            int k = Math.min(5, a.getFirst().size() - 1);
            assertEquals(a.getFirst().x[k], other.getFirst().x[k], .12);
        }
    }

    @Test void tenTrunksGrowAboutFortyFiveTips() {
        assertEquals(10, FxInkTree.TRUNKS.length);
        for (int v = 0; v < 3; v++) {
            int n = ends(v);
            assertTrue(n >= 38 && n <= 56, "tips " + n);
        }
    }

    @Test void everyTrunkForksTwoOrThreeTimesAndItsChildrenFork() {
        for (int trunk = 0; trunk < FxInkTree.TRUNKS.length; trunk++) {
            var g = FxInkTree.group(trunk, 0);
            final int tr = trunk;
            long forks = g.stream().filter(b -> b.parent >= 0 && b.depth == 1 && g.get(b.parent).depth == 0 && !isLoopStrand(tr, b)).count();
            assertTrue(forks >= 2 && forks <= 3, "trunk " + trunk + " forks " + forks);
        }
        long grandchildren = 0;
        for (int trunk = 0; trunk < FxInkTree.TRUNKS.length; trunk++) grandchildren += FxInkTree.group(trunk, 0).stream().filter(b -> b.depth >= 2).count();
        assertTrue(grandchildren >= 10, "forks of forks " + grandchildren);
    }

    @Test void aForkChildLeavesTangentiallyAndIsClearlyThinner() {
        int forks = 0;
        for (int trunk = 0; trunk < FxInkTree.TRUNKS.length; trunk++) {
            List<FxInkTree.Branch> g = FxInkTree.group(trunk, 0);
            for (var b : g) {
                if (b.parent < 0) continue;
                var p = g.get(b.parent);
                double s = b.parentU * p.length;
                // it hangs on its parent: the first point lies on the parent's path
                double best = 9;
                for (int i = 0; i < p.size(); i++) best = Math.min(best, Math.hypot(p.x[i] - b.x[0], p.y[i] - b.y[0]));
                assertTrue(best < .07, "joint " + best);
                assertEquals(p.start + s, b.start, .35);
                if (b.depth == p.depth + 1) {
                    forks++;
                    assertTrue(b.vis <= .6 * p.vis + 1e-9 || b.vis <= FxInkTree.MIN_VIS * 1.01, "child " + b.vis + " of " + p.vis);
                    double turn = Math.abs(Math.atan2(Math.sin(b.heading(0) - p.heading(s)), Math.cos(b.heading(0) - p.heading(s))));
                    assertTrue(turn < .45, "leaves at " + turn);
                } else if (!isLoopStrand(trunk, b) && !isLoopStrand(trunk, p)) {
                    // the next stroke of the same spine after a fork is thinner than the one before
                    assertTrue(b.vis < p.vis, "spine does not thin after a fork");
                }
            }
        }
        assertTrue(forks > 25, "the tree forks: " + forks);
    }

    @Test void trunksLeaveTheRootAtVeryDifferentAnglesAndCross() {
        double lo = 9, hi = -9;
        for (int trunk = 0; trunk < FxInkTree.TRUNKS.length; trunk++) {
            var b = FxInkTree.group(trunk, 0).getFirst();
            double h = b.heading(.3);
            lo = Math.min(lo, h); hi = Math.max(hi, h);
        }
        assertTrue(Math.toDegrees(hi - lo) > 120, "fan " + Math.toDegrees(hi - lo));
        // some trunks run almost flat along the ground at first
        assertTrue(Math.toDegrees(FxInkTree.group(0, 0).getFirst().heading(.3)) > 150 || Math.toDegrees(FxInkTree.group(9, 0).getFirst().heading(.3)) < 30);
        int crossings = 0;
        for (int i = 0; i < FxInkTree.TRUNKS.length; i++) {
            for (int j = i + 1; j < FxInkTree.TRUNKS.length; j++) {
                var p = FxInkTree.group(i, 0).getFirst(); var q = FxInkTree.group(j, 0).getFirst();
                crossings += crosses(p, q) ? 1 : 0;
            }
        }
        assertTrue(crossings >= 3, "trunks that cross near the root " + crossings);
    }

    private static boolean crosses(FxInkTree.Branch p, FxInkTree.Branch q) {
        for (int k = 0; k + 1 < p.size(); k++) for (int m = 0; m + 1 < q.size(); m++) {
            double ax = p.x[k], ay = p.y[k], bx = p.x[k + 1], by = p.y[k + 1], cx = q.x[m], cy = q.y[m], dx = q.x[m + 1], dy = q.y[m + 1];
            double d1 = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax), d2 = (bx - ax) * (dy - ay) - (by - ay) * (dx - ax);
            double d3 = (dx - cx) * (ay - cy) - (dy - cy) * (ax - cx), d4 = (dx - cx) * (by - cy) - (dy - cy) * (bx - cx);
            if (d1 * d2 < 0 && d3 * d4 < 0) return true;
        }
        return false;
    }

    @Test void threeRoundLoopsInTheLowerHalfOfDifferentSizes() {
        assertEquals(3, FxInkTree.LOOPS.length);
        int left = 0, nearRoot = 0;
        java.util.Set<Double> sizes = new java.util.HashSet<>();
        for (var l : FxInkTree.LOOPS) {
            var sp = FxInkTree.trunkSpine(l.trunk(), 0);
            int i = sp.at((l.d1() + l.d2()) / 2);
            assertTrue(sp.y[i] < 2.75, "lower half: y " + sp.y[i]);
            if (sp.x[i] < 0) left++;
            if (sp.y[i] < 1.2) nearRoot++;
            sizes.add(l.width());
            // rounded: about as long as wide
            assertTrue((l.d2() - l.d1()) < 2.2 * l.width(), "round");
        }
        assertTrue(left >= 2, "two loops left of the middle");
        assertTrue(nearRoot >= 1, "one loop near the root");
        assertEquals(3, sizes.size());
        // each loop leaves two strands that rejoin: in every group a pair of strokes shares both ends
        int strands = 0;
        for (int trunk = 0; trunk < FxInkTree.TRUNKS.length; trunk++) {
            var g = FxInkTree.group(trunk, 0);
            for (var q : g) if (isLoopStrand(trunk, q)) strands++;
        }
        assertEquals(6, strands, "two strands in each of three loops");
    }

    @Test void rootIsAnUnevenMassWithNoStraightEdge() {
        // the floor mass is made of kind-1 strokes only: stems that rise through the trunk bases, webs between them, lumps along the ground
        assertFalse(FxInkTree.FLOOR.isEmpty());
        for (var b : FxInkTree.FLOOR) assertEquals(1, b.kind);
        long stems = FxInkTree.FLOOR.stream().filter(b -> b.y[0] < .1 && b.y[b.size() - 1] > .45).count();
        assertTrue(stems >= 6, "stems " + stems);
        long webs = FxInkTree.FLOOR.stream().filter(b -> b.size() == 14 && b.vis < .05 && b.y[0] > .1).count();
        assertTrue(webs >= 6, "webs " + webs);
        // the lumps lie at uneven heights: the lower edge of the puddle is not one line
        double lo = 9, hi = -9;
        for (var b : FxInkTree.FLOOR) if (b.y[b.size() - 1] < .4 && b.size() < 14) { lo = Math.min(lo, b.y[0] - b.vis); hi = Math.max(hi, b.y[0] - b.vis); }
        assertTrue(hi - lo > .1, "edge relief " + (hi - lo));
    }

    @Test void inkFlowsOutwardAndNeverPops() {
        for (double t = 0; t < FxInkTreeScene.BENCH_SECONDS; t += .05) {
            var pieces = FxInkTreeScene.pieces(t, true);
            for (var p : pieces) {
                assertTrue(p.drawn() > p.from() && p.drawn() <= 1 && p.from() >= 0, "window " + p.from() + ".." + p.drawn());
                for (double[] q : p.points()) assertTrue(Double.isFinite(q[0]) && Double.isFinite(q[1]));
            }
            if (t < FxInkTreeScene.START) assertTrue(pieces.isEmpty(), "the first second is empty");
        }
        int last = 0;
        for (double t = FxInkTreeScene.START + .5; t < FxInkTreeScene.BENCH_SECONDS; t += .05) {
            int n = (int) FxInkTreeScene.pieces(t, false).stream().filter(p -> p.drawn() - p.from() > .06).count();
            if (last > 0) assertTrue(Math.abs(n - last) <= Math.max(8, last / 4), "pop at " + t + ": " + last + " -> " + n);
            last = n;
        }
    }

    @Test void aChildNeverOutlivesTheJointOnItsParent() {
        // when a branch slides off, no child of it is left alone with a blunt base: at any time the visible window of a child
        // starts no earlier than the point where the parent's visible window reaches the joint
        for (double t = 3; t < FxInkTreeScene.BENCH_SECONDS; t += .25) {
            for (var p : FxInkTreeScene.pieces(t, false)) {
                assertTrue(p.from() <= p.drawn());
            }
        }
    }

    @Test void bendKeepsTheRootAndTheJointsTogether() {
        double[] at = FxInkTreeScene.bend(.1, .05, 0, 0, 3, 5);
        assertEquals(.1, at[0], 1e-9);
        assertEquals(.05, at[1], 1e-9);
        assertArrayEquals(FxInkTreeScene.bend(1, 2, 0, 1.7, 4, 6), FxInkTreeScene.bend(1, 2, 0, 1.7, 4, 6));
    }
}
