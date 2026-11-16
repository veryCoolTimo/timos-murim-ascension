package io.github.verycooltimo.murim.client.flow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PillBodyVeinsTest {
    private static final PillBodyVeins.Joints J = PillBodyVeins.standard();

    @Test
    void sameSeedSameVeins() {
        var a = PillBodyVeins.build(J, 1114, 2);
        var b = PillBodyVeins.build(J, 1114, 2);
        assertEquals(a.size(), b.size());
        assertArrayEquals(a.get(3).pts()[2], b.get(3).pts()[2]);
    }

    @Test
    void nothingAboveTheCollarAndNothingInTheLegs() {
        for (var b : PillBodyVeins.build(J, 7, 3)) for (double[] p : b.pts()) {
            assertTrue(p[1] <= PillBodyVeins.COLLAR_V + 1e-9, "above the collar: " + p[1]);
            assertTrue(p[1] >= PillBodyVeins.FLOOR_V - 1e-9, "below the hips: " + p[1]);
        }
    }

    @Test
    void veinsReachBothHands() {
        var all = PillBodyVeins.build(J, 1114, 2);
        for (double[] hand : new double[][] {J.handL(), J.handR()}) {
            boolean near = all.stream().filter(PillBodyVeins.Branch::arm).anyMatch(b -> PillBodyVeins.dist(b.pts()[b.pts().length - 1], hand) < 1.5);
            assertTrue(near, "no vein ends at the hand");
        }
    }

    @Test
    void spreadsFromTheCentreOutward() {
        List<PillBodyVeins.Branch> all = PillBodyVeins.build(J, 1114, 2);
        assertEquals(0, all.get(0).start(), 1e-9);
        double lastEnd = 0;
        for (var b : all) {
            if (b.parent() >= 0) assertTrue(b.start() >= all.get(b.parent()).start() - 1e-9, "child before its parent");
            assertTrue(b.start() >= 0 && b.start() + b.dur() <= 1 + 1e-9);
            lastEnd = Math.max(lastEnd, b.start() + b.dur());
        }
        assertEquals(1, lastEnd, 1e-9);
        // the hands are far from the dantian: their veins start well after the trunk's start
        double handStart = all.stream().filter(b -> b.arm() && b.depth() == 3).mapToDouble(PillBodyVeins.Branch::start).min().orElse(0);
        assertTrue(handStart > .4, "the hands light late: " + handStart);
    }
}
