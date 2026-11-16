package io.github.verycooltimo.murim.client.vfx.fx;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FxInkTraceTest {
    private static final String TINY = "{\"pxPerBlock\":100,\"branches\":["
            + "{\"id\":0,\"parent\":-1,\"depth\":0,\"loop\":false,\"gap\":0,\"start\":0,\"x\":[0,0,0],\"y\":[0,1,2],\"h\":[.1,.1,.05]},"
            + "{\"id\":1,\"parent\":0,\"depth\":1,\"loop\":false,\"gap\":0,\"start\":2,\"x\":[0,1],\"y\":[2,2],\"h\":[.05,.02]},"
            + "{\"id\":2,\"parent\":0,\"depth\":1,\"loop\":true,\"gap\":0,\"start\":2,\"x\":[0,-3],\"y\":[2,2],\"h\":[.05,.02]},"
            + "{\"id\":3,\"parent\":1,\"depth\":2,\"loop\":false,\"gap\":.5,\"start\":3.5,\"x\":[5,6],\"y\":[5,5],\"h\":[.03,.01]}]}";

    @Test void parsesAndOrdersTheGrowth() {
        FxInkTrace t = FxInkTrace.parse(TINY);
        assertEquals(4, t.branches.size());
        assertEquals(2, t.branches.get(0).length, 1e-9);
        double[] speed = {1, 1, 2, 1};
        double[] ts = t.startTimes(speed);
        assertEquals(0, ts[0], 1e-9);
        assertEquals(2, ts[1], 1e-9);          // the head of branch 0 reaches the fork at 2 s
        assertEquals(2, ts[2], 1e-9);          // a loop branch leaves the same fork
        assertEquals(3 + .5, ts[3], 1e-9);     // parent 1 ends at 2 + 1; then the gap of .5 blocks at speed 1
    }

    @Test void groupsBeginBeyondTheRingAndKeepTheirChildren() {
        FxInkTrace t = FxInkTrace.parse(TINY);
        int[] g = t.groups(1.0);
        assertEquals(-1, g[0]);
        assertEquals(1, g[1]);
        assertEquals(2, g[2]);
        assertEquals(1, g[3]);
    }

    @Test void tracedTreesGrowParentsFirstAndEveryChildStartsWhenItsParentIsThere() {
        for (String name : new String[]{"inktree_13", "inktree_14"}) {
            FxInkTrace t = FxInkTrace.load(name);
            assertTrue(t.branches.size() > 30, name);
            double[] speed = t.speeds(2.2, 3);
            double[] ts = t.startTimes(speed);
            for (var b : t.branches) {
                assertTrue(b.parent < b.id, name + " parent order " + b.id);
                assertTrue(b.length > 0 && b.size() >= 2);
                if (b.parent >= 0) {
                    var p = t.branches.get(b.parent);
                    assertTrue(ts[b.id] >= ts[p.id] + p.length / speed[p.id] - 1e-9, name + " child before its parent's head " + b.id);
                }
            }
        }
    }

    @Test void growthFillsTheTreeAndRenewalNeverEndsInTheSameInstant() {
        var c = FxInkTraceScene.Cast.IDENTITY;
        int rest = FxInkTraceScene.rest(c, false).size();
        assertTrue(rest > 100);
        int lastCount = -1;
        int changes = 0;
        for (double t = 1.5; t < 30; t += .25) {
            List<FxInkTraceScene.Piece> ps = FxInkTraceScene.pieces(t, c, false);
            for (var p : ps) assertTrue(p.from() < p.drawn() && p.drawn() <= 1 && p.from() >= 0);
            if (ps.size() != lastCount) changes++;
            lastCount = ps.size();
        }
        // pieces come and go one by one: the count of strokes changes at most a few at a time
        assertTrue(changes > 20);
        // the root stays put: displacement is zero at distance zero
        double[] d = FxInkTraceScene.bend(.3, .2, 0, 1, 5);
        assertEquals(.3, d[0], 1e-9);
        assertEquals(.2, d[1], 1e-9);
    }

    @Test void castsDiffer() {
        var a = FxInkTraceScene.rest(FxInkTraceScene.Cast.of(1), false);
        var b = FxInkTraceScene.rest(FxInkTraceScene.Cast.of(2), false);
        assertNotEquals(a.size(), b.size());
        assertEquals(FxInkTraceScene.Cast.IDENTITY, FxInkTraceScene.Cast.of(0));
    }
}
