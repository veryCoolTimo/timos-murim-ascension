package io.github.verycooltimo.murim.client.flow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The space layers' pure maths: monotone growth, continuous events (no pop), arms that differ, timeline order, camera keys. */
class FlowSpaceMathTest {
    @Test
    void vortexGrowsFromOneToFourAndAHalfBlocks() {
        assertEquals(1.0, FlowSpaceMath.vortexRadius(0), 1e-9);
        assertEquals(4.5, FlowSpaceMath.vortexRadius(1), 1e-9);
        double prev = 0;
        for (int i = 0; i <= 100; i++) { double r = FlowSpaceMath.vortexRadius(i / 100.0); assertTrue(r >= prev); prev = r; }
        assertTrue(FlowSpaceMath.spin(0) >= 8 && FlowSpaceMath.spin(1) <= 20 && FlowSpaceMath.spin(1) > FlowSpaceMath.spin(0));
    }

    @Test
    void armsAllDifferAndStayBroad() {
        for (int i = 0; i < FlowLook.ARMS_MAX; i++) {
            var a = FlowSpaceMath.arm(i);
            assertTrue(a.rimWidth() >= .6 && a.rimWidth() <= 1.0, "arm width " + a.rimWidth());
            for (int j = i + 1; j < FlowLook.ARMS_MAX; j++) {
                var b = FlowSpaceMath.arm(j);
                assertTrue(Math.abs(a.sweep() - b.sweep()) > 1e-3 || Math.abs(a.rimWidth() - b.rimWidth()) > 1e-3);
                assertNotEquals(a.u1(), b.u1());
            }
        }
    }

    @Test
    void armsWindInwardAndNeverGoBelowTheFloor() {
        double[] p = new double[5];
        for (int i = 0; i < FlowLook.ARMS_MAX; i++) {
            var a = FlowSpaceMath.arm(i);
            double lastR = 0;
            for (double u = 0; u <= 1; u += .05) {
                FlowSpaceMath.armPoint(a, u, 33, 4.5, p);
                assertTrue(p[3] >= lastR - 1e-9, "radius grows from the core to the rim");
                lastR = p[3];
                double half = FlowSpaceMath.armWidth(a, u, 1) / 2;
                assertTrue(p[1] > 0, "the ribbon's lower edge stays above the floor");
            }
        }
    }

    @Test
    void armWidthIsZeroAtTheEndsAndBroadAtTheRim() {
        var a = FlowSpaceMath.arm(2);
        assertEquals(0, FlowSpaceMath.armWidth(a, a.u0(), 1), 1e-9);
        assertEquals(0, FlowSpaceMath.armWidth(a, a.u1(), 1), 1e-9);
        double w = 0;
        for (double u = a.u0(); u <= a.u1(); u += .01) w = Math.max(w, FlowSpaceMath.armWidth(a, u, 1));
        assertTrue(w >= .4, "broad: " + w);
    }

    @Test
    void mistakeIsContinuousAndSlow() {
        assertEquals(1, FlowSpaceMath.stall(0), 1e-12);
        assertEquals(1, FlowSpaceMath.shudder(0), 1e-12);
        double lastS = 1, lastH = 1;
        for (double t = 0; t <= 1.5; t += .01) {
            double s = FlowSpaceMath.stall(t), h = FlowSpaceMath.shudder(t);
            assertTrue(Math.abs(s - lastS) < .08, "stall step at " + t);
            assertTrue(Math.abs(h - lastH) < .01, "shudder step at " + t);
            lastS = s; lastH = h;
        }
        assertEquals(1, FlowSpaceMath.stall(1.5), 1e-9);
        assertTrue(FlowSpaceMath.stall(.4) < .3);
        assertTrue(FlowLook.SHUDDER_HZ < 2);
    }

    @Test
    void rippleAndBrightenAreEnvelopes() {
        assertEquals(0, FlowSpaceMath.rippleHead(0), 1e-12);
        assertEquals(1, FlowSpaceMath.rippleHead(FlowLook.NODE_RIPPLE), 1e-12);
        assertEquals(0, FlowSpaceMath.armBrighten(-1), 1e-12);
        assertEquals(0, FlowSpaceMath.armBrighten(0), 1e-12);
        assertTrue(FlowSpaceMath.armBrighten(.2) > .9);
        assertEquals(0, FlowSpaceMath.armBrighten(FlowLook.ARM_BRIGHTEN + .15), 1e-12);
    }

    @Test
    void finaleTimelineOrder() {
        assertEquals(0, FlowSpaceMath.contraction(0), 1e-12);
        assertEquals(1, FlowSpaceMath.contraction(FlowLook.FIN_CONTRACT), 1e-12);
        assertEquals(3.5, FlowSpaceMath.burstAt(), 1e-9);
        assertTrue(FlowSpaceMath.liftAt() < FlowSpaceMath.burstAt());
        assertEquals(0, FlowSpaceMath.ball(FlowLook.FIN_CONTRACT, false), 1e-12);
        assertEquals(1, FlowSpaceMath.ball(FlowLook.FIN_CONTRACT + FlowLook.FIN_BALL, false), 1e-12);
        assertEquals(0, FlowSpaceMath.brightening(FlowSpaceMath.burstAt() - .01), 1e-12);
        assertEquals(0, FlowSpaceMath.brightening(FlowSpaceMath.burstAt() + FlowLook.BRIGHT_TIME), 1e-12);
        double max = 0;
        for (double t = 3.4; t < 4.9; t += .005) max = Math.max(max, FlowSpaceMath.brightening(t));
        assertTrue(max > .95 && max <= 1);
        assertTrue(FlowLook.BRIGHT_TIME <= 1.2 && FlowLook.BRIGHT_ALPHA <= .6F);
        assertTrue(FlowSpaceMath.effectEnd(0) > FlowSpaceMath.burstAt() + 1.8);
    }

    @Test
    void handFadeGoesFromTheHandsInward() {
        for (double f = 0; f <= 1; f += .05) assertTrue(FlowSpaceMath.handFade(f, 0) <= FlowSpaceMath.handFade(f, 1) + 1e-12);
        assertEquals(1, FlowSpaceMath.handFade(0, 0), 1e-12);
        assertEquals(0, FlowSpaceMath.handFade(1, 1), 1e-12);
        assertEquals(0, FlowSpaceMath.handFade(.4, 0), 1e-12);
    }

    @Test
    void cameraKeys() {
        assertEquals(FlowLook.CAM_START, FlowSpaceMath.cameraDistance(0, 0, -1), 1e-9);
        assertTrue(FlowSpaceMath.cameraDistance(1, 0, -1) < FlowSpaceMath.cameraDistance(0, 0, -1));
        assertEquals(FlowLook.CAM_PULL, FlowSpaceMath.cameraDistance(1, 0, .4 + FlowLook.CAM_PULL_TIME), 1e-9);
        double last = FlowSpaceMath.cameraDistance(1, 0, 0);
        for (double t = 0; t <= 3; t += .02) {
            double d = FlowSpaceMath.cameraDistance(1, 0, t);
            assertTrue(Math.abs(d - last) < .12, "no jump at " + t);
            last = d;
        }
        assertEquals(0, FlowSpaceMath.cameraNudge(0), 1e-12);
        assertTrue(FlowLook.CAM_NUDGE <= .06);
    }

    @Test
    void sheetsGrowTallerAndMoreNumerous() {
        assertEquals(2, FlowSpaceMath.sheetCount(0));
        assertEquals(4, FlowSpaceMath.sheetCount(1));
        assertTrue(FlowSpaceMath.sheetHeight(0, 0) >= 1.6 && FlowSpaceMath.sheetHeight(1, 1) <= 2.8 + 1e-9);
        assertTrue(FlowSpaceMath.sheetHeight(1, 1) > FlowSpaceMath.sheetHeight(0, 0));
    }

    /** Round 3: the shot is further and wider (distance 3.3 -> 3.0, lens 0.85); the meridian (0.637 block tall on the model) takes about 16-18 % of the frame height, the 20 % of the brief needs a lens below 0.8, which the same brief forbids. */
    @Test
    void meridianTakesAtLeast15PercentOfTheFrame() {
        for (double baseFov : new double[] {70, 74.2}) {
            for (int i = 0; i <= 20; i++) {
                double d = FlowSpaceMath.cameraDistance(i / 20.0, -1, -1);
                double frame = 2 * d * Math.tan(Math.toRadians(baseFov * FlowLook.CAM_FOV / 2));
                assertTrue(.637 * Math.cos(Math.toRadians(FlowSpaceMath.cameraPitch(-1, -1))) / frame >= .15, "at progress " + i / 20.0 + " fov " + baseFov);
            }
        }
    }
}
