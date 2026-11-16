package io.github.verycooltimo.murim.client.vfx.fx;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FxTree3DTest {
    private static final FxTree3D TREE = FxTree3D.get();

    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    @Test void aStraightTracedBranchBecomesACurveThatLeavesEveryPlane() {
        // a straight line in the trace, 2 blocks long, wrapped on the ink shell
        int n = 20;
        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) { x[i] = .1 + .02 * i; y[i] = .2 + .1 * i; }
        FxShellWrap.Shell sh = TREE_SHELL();
        int bent = 0;
        for (long key = 0; key < 20; key++) {
            double[][] p = FxShellWrap.wrap(sh, x, y, 0, key).p();
            double[] a = sub(p[n / 2], p[0]), c = sub(p[n - 1], p[0]);
            double[] nrm = cross(a, c);
            double nl = Math.sqrt(dot(nrm, nrm));
            if (nl < 1e-9) continue;
            double dev = 0;
            for (int i = 0; i < n; i++) dev = Math.max(dev, Math.abs(dot(sub(p[i], p[0]), nrm)) / nl);
            if (dev > .03) bent++;
        }
        assertTrue(bent >= 14, "wrapped branches off one plane (over 3 cm): " + bent + " of 20");
    }

    private static FxShellWrap.Shell TREE_SHELL() { return FxTree3D.LAYERS[0].shell(); }
    private static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    private static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }

    @Test void swingIsZeroAtTheForkAndGrowsTowardsTheTip() {
        int n = 30;
        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) { x[i] = 0; y[i] = .15 * i; }
        double early = 0, late = 0;
        for (long key = 0; key < 40; key++) {
            double[] s = FxShellWrap.wrap(TREE_SHELL(), x, y, 0, key).swing();
            assertEquals(0, s[0], 1e-12);
            early += Math.abs(s[2]); late += Math.abs(s[n - 1]) + Math.abs(s[n - 5]);
            for (double v : s) assertTrue(Math.abs(v) <= FxShellWrap.MAX_SWING + 1e-9);
        }
        assertTrue(late / 2 > early * 2, "swing near the tip " + late / 2 + " versus near the fork " + early);
    }

    @Test void everyChildStartsExactlyOnItsParentAfterWrapping() {
        int kids = 0;
        for (FxTree3D.Branch b : TREE.branches) {
            if (b.parent < 0) continue;
            FxTree3D.Branch par = TREE.branches.get(b.parent);
            kids++;
            for (int k = 0; k < 3; k++) assertEquals(par.p[3 * b.forkIndex + k], b.p[k], 1e-5);
            assertEquals(par.swing[b.forkIndex], b.swing[0] , .55, "child's swing starts from the parent's");
        }
        assertTrue(kids > 150, "children " + kids);
    }

    @Test void nothingComesNearerTheAxisThanTheKeepOutBelowHeadHeight() {
        double min = 9;
        for (FxTree3D.Branch b : TREE.branches) {
            for (int i = 0; i < b.size(); i++) {
                double r = Math.hypot(b.p[3 * i], b.p[3 * i + 2]);
                if (b.p[3 * i + 1] < FxShellWrap.KEEP_HEIGHT) min = Math.min(min, r);
                assertTrue(b.p[3 * i + 1] >= 0, "under the floor");
            }
        }
        assertTrue(min >= .45, "nearest point to the axis below 1.6 blocks: " + min);
    }

    @Test void theFrontSectorStaysLow() {
        int low = 0;
        for (FxTree3D.Branch b : TREE.branches) {
            if (b.layer != 1 && b.layer != 3) continue;     // the layers that are thinned in front
            for (int i = 0; i < b.size(); i++) {
                double az = Math.toDegrees(Math.atan2(b.p[3 * i], b.p[3 * i + 2]));
                if (Math.abs(az) < FxTree3D.FRONT_HALF_DEG) { assertTrue(b.p[3 * i + 1] <= FxTree3D.FRONT_TOP + 1e-6, "branch " + b.id + " y=" + b.p[3 * i + 1]); low++; }
            }
        }
        assertTrue(low > 0, "something stands low in front");
        // and the ink behind is tall
        double top = 0;
        for (FxTree3D.Branch b : TREE.branches) if (b.layer == 0) for (int i = 0; i < b.size(); i++) top = Math.max(top, b.p[3 * i + 1]);
        assertTrue(top > 3.5, "tree 13 height " + top);
    }

    @Test void layersStandOnTheirOwnShellsAndTheInkEncirclesThePlayer() {
        double[] meanR = new double[FxTree3D.LAYERS.length];
        int[] cnt = new int[meanR.length];
        double minAz = 999, maxAz = -999;
        for (FxTree3D.Branch b : TREE.branches) {
            for (int i = 0; i < b.size(); i++) {
                meanR[b.layer] += Math.hypot(b.p[3 * i], b.p[3 * i + 2]) - FxShellWrap.K * b.p[3 * i + 1]; cnt[b.layer]++;
            }
            if (b.family == FxTree3D.INK) for (int i = 0; i < b.size(); i++) {
                double az = Math.toDegrees(Math.atan2(b.p[3 * i], b.p[3 * i + 2])); minAz = Math.min(minAz, az); maxAz = Math.max(maxAz, az);
            }
        }
        for (int l = 0; l < meanR.length; l++) assertTrue(cnt[l] > 0, "layer " + l + " has branches");
        assertTrue(meanR[4] / cnt[4] > meanR[0] / cnt[0] + .5, "blue shell larger than the ink shell");
        assertTrue(meanR[2] / cnt[2] > meanR[0] / cnt[0] + .1, "violet shell larger than the ink shell");
        assertTrue(minAz < -150 && maxAz > 150, "the ink reaches " + minAz + " .. " + maxAz + " degrees round the body");
    }

    @Test void aboutAHundredBranchEndsWholeAndAllLayersGrow() {
        int ends = TREE.leaves();
        assertTrue(ends >= 90 && ends <= 260, "branch ends " + ends);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int[] n = {0};
        TREE.visit(FxInkTraceScene.START - .1, (b, f, d, h, t, g) -> n[0]++);
        assertEquals(0, n[0]);
        for (double t = 1; t < 16; t += .25) TREE.visit(t, (b, f, d, h, tl, g) -> seen.add(b.id));
        assertEquals(TREE.branches.stream().filter(b -> !b.hidden).count(), seen.size());
    }

    @Test void buildIsDeterministic() {
        FxTree3D other = new FxTree3D(5);
        assertEquals(TREE.branches.size(), other.branches.size());
        for (int i = 0; i < other.branches.size(); i++) assertArrayEquals(TREE.branches.get(i).p, other.branches.get(i).p);
    }

    @Test void haloWidthIsClampedWhereThePathTurnsTightly() {
        // a path that turns by 90 degrees over 0.1 block: the turning radius there is about 0.1, so the half-width must stay under it
        float[] p = {0, 0, 0, 0, .1F, 0, .1F, .1F, 0, .2F, .1F, 0};
        assertTrue(FxHalo.turnLimit(p, 4, 1) < .15);
        float[] straight = {0, 0, 0, 0, .1F, 0, 0, .2F, 0};
        assertEquals(Double.MAX_VALUE, FxHalo.turnLimit(straight, 3, 1));
    }

    @Test void aboutAQuarterOfTheInkIsHiddenAndNoChildLosesItsParentPoint() {
        double share = TREE.hiddenShare();
        assertTrue(share > .2 && share < .4, "hidden share of the ink weight: " + share);
        for (var b : TREE.branches) if (b.parent >= 0) assertTrue(b.size() >= 2);
        int[] seen = {0};
        TREE.visit(9, (b, from, drawn, head, tail, gen) -> { assertFalse(b.hidden); seen[0]++; });
        assertTrue(seen[0] > 60);
    }

    @Test void theDetailCutDropsThinBranchesOnly() {
        FxTree3D t = new FxTree3D(5);
        int[] all = {0}, cut = {0};
        t.visit(9, (b, f, d, h, tl, g) -> all[0]++);
        t.detailCut = .05;
        t.visit(9, (b, f, d, h, tl, g) -> { cut[0]++; assertTrue(b.meanHalf >= .05); });
        assertTrue(cut[0] < all[0] && cut[0] > 0);
    }
}
