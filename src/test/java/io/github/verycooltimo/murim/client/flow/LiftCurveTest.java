package io.github.verycooltimo.murim.client.flow;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LiftCurveTest {
    static final double MAX = 1.3;

    @Test void startsAndEndsOnTheFloor() {
        assertEquals(0, LiftCurve.height(0, MAX), 1e-9);
        assertEquals(0, LiftCurve.height(LiftCurve.TOTAL, MAX), 1e-9);
        assertEquals(0, LiftCurve.height(LiftCurve.TOUCH, MAX), 1e-6);
    }

    @Test void anticipationSinksFiveCentimetresThenRisesWithAnOvershoot() {
        assertEquals(-.05, LiftCurve.height(LiftCurve.ANTIC, MAX), 1e-6);
        double peak = 0;
        for (double a = LiftCurve.ANTIC; a < LiftCurve.SETTLE_END; a += .25) peak = Math.max(peak, LiftCurve.height(a, MAX));
        assertTrue(peak > MAX + .02 && peak <= MAX + .045, "overshoot " + (peak - MAX));
        assertEquals(MAX, LiftCurve.height(LiftCurve.SETTLE_END, MAX), 1e-6);
    }

    @Test void theRiseIsFastAtFirstAndBrakesAtTheTop() {
        double early = LiftCurve.speed(14, MAX, LiftCurve.Params.DEFAULT), late = LiftCurve.speed(30, MAX, LiftCurve.Params.DEFAULT);
        assertTrue(early > 2 * late && late > 0, early + " " + late);
    }

    @Test void theBobStaysUnderFourCentimetres() {
        for (double a = LiftCurve.SETTLE_END; a < LiftCurve.DESCENT; a += .25) assertTrue(Math.abs(LiftCurve.height(a, MAX) - MAX) <= .0401);
    }

    @Test void theDescentBrakesBeforeTheFloor() {
        var p = LiftCurve.Params.DEFAULT;
        double mid = Math.abs(LiftCurve.speed(91, MAX, p)), end = Math.abs(LiftCurve.speed(LiftCurve.TOUCH - 1, MAX, p));
        assertTrue(end < mid * .15, mid + " " + end);
        double lowest = 0;
        for (double a = LiftCurve.TOUCH; a < LiftCurve.TOTAL; a += .25) lowest = Math.min(lowest, LiftCurve.height(a, MAX));
        assertEquals(-.06, lowest, .002);
    }

    @Test void theHeightNeverJumps() {
        double prev = LiftCurve.height(.01, MAX);
        for (double a = .05; a < LiftCurve.TOTAL; a += .05) {
            double h = LiftCurve.height(a, MAX);
            assertTrue(Math.abs(h - prev) < .06, "jump " + (h - prev) + " at " + a);
            prev = h;
        }
    }
}
