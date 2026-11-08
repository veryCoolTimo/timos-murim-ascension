package io.github.verycooltimo.murim.client.decor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TipsyMotionTest {
    @Test void levelAmplitudesAndAccessibility() {
        float[] roll = {0, 2, 3.5F, 5};
        float[] yaw = {0, .6F, 1.2F, 2};
        for (int level = 0; level <= 3; level++) {
            assertEquals(roll[level], TipsyMotion.rollAmplitude(level));
            assertEquals(yaw[level], TipsyMotion.yawAmplitude(level));
            for (int tick = 0; tick < 1000; tick++) {
                assertEquals(0, TipsyMotion.roll(level, tick, 0, 1));
                assertEquals(0, TipsyMotion.yaw(level, tick, 0, 1));
                assertEquals(0, TipsyMotion.pitch(level, tick, 0, 1));
                assertEquals(0F, TipsyMotion.roll(level, tick, 1, 0));
                assertEquals(0F, TipsyMotion.yaw(level, tick, 1, 0));
                assertEquals(0F, TipsyMotion.pitch(level, tick, 1, 0));
                assertEquals(0F, TipsyMotion.roll(0, tick, 1, 1));
                assertEquals(0F, TipsyMotion.yaw(0, tick, 1, 1));
                assertEquals(0F, TipsyMotion.pitch(0, tick, 1, 1));
                assertTrue(Math.abs(TipsyMotion.roll(level, tick, 1, 1)) <= roll[level]);
                assertTrue(Math.abs(TipsyMotion.pitch(level, tick, 1, 1)) <= yaw[level] / 2);
            }
            assertEquals(yaw[level], TipsyMotion.yaw(level, 33, 1, 1), .00001F);
        }
    }

    @Test void pulseRisesAndFallsWithinTwentyFourTicks() {
        assertEquals(0, TipsyMotion.pulse(-1));
        assertEquals(0, TipsyMotion.pulse(0));
        assertEquals(1, TipsyMotion.pulse(12), .00001F);
        assertEquals(.012F, .012F * TipsyMotion.pulse(12), .00001F);
        assertEquals(.45F, .45F * TipsyMotion.pulse(12), .00001F);
        for (int tick = 1; tick <= 12; tick++) {
            assertTrue(TipsyMotion.pulse(tick) > TipsyMotion.pulse(tick - 1));
            assertEquals(TipsyMotion.pulse(tick), TipsyMotion.pulse(24 - tick), .00001F);
        }
        assertEquals(0, TipsyMotion.pulse(24));
        assertEquals(0, TipsyMotion.pulse(25));
    }

    @Test void pulseIsNonZeroForTicksOneThroughTwentyThree() {
        for (int tick = 1; tick <= 23; tick++) {
            assertTrue(TipsyMotion.pulse(tick) > 0, "Pulse must be visible at tick " + tick);
        }
    }

    @Test void fadeTakesTwoSecondsInAndThreeSecondsOutAndCanReverse() {
        float fade = 0;
        for (int tick = 0; tick < 20; tick++) fade = TipsyMotion.fadeStep(fade, true);
        assertEquals(.5F, fade, .00001F);
        for (int tick = 0; tick < 20; tick++) fade = TipsyMotion.fadeStep(fade, true);
        assertEquals(1, fade, .00001F);
        for (int tick = 0; tick < 30; tick++) fade = TipsyMotion.fadeStep(fade, false);
        assertEquals(.5F, fade, .00001F);
        assertTrue(TipsyMotion.fadeStep(fade, true) > fade);
        for (int tick = 0; tick < 30; tick++) fade = TipsyMotion.fadeStep(fade, false);
        assertEquals(0, fade);
        assertEquals(0, TipsyMotion.smooth(fade));
        assertEquals(1, TipsyMotion.smooth(1));
    }
}
