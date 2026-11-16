package io.github.verycooltimo.murim.client.vfx.fx;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FxQualityTest {
    private static void run(FxQuality q, double ms, double seconds) {
        for (double t = 0; t < seconds; t += 1 / 60.0) q.update(ms, 1 / 60.0);
    }

    @Test void fastGameStaysAtFullQuality() {
        var q = new FxQuality();
        run(q, 4, 60);
        assertEquals(0, q.tier());
        assertEquals(1, q.particleScale(), 1e-9);
        assertEquals(10, q.grid());
    }

    @Test void slowGameStepsDownOneTierAtATimeAndStopsAtTheLast() {
        var q = new FxQuality();
        run(q, 40, 2.5);
        assertTrue(q.tier() >= 1 && q.tier() < 3, "stepped down slowly: " + q.tier());
        run(q, 40, 30);
        assertEquals(FxQuality.TIERS - 1, q.tier());
        assertEquals(.2, q.particleScale(), 1e-9);
        assertEquals(5, q.grid());
        assertTrue(q.detailCut() > 0);
    }

    @Test void oneHitchIsIgnored() {
        var q = new FxQuality();
        run(q, 5, 5);
        q.update(500, 1 / 60.0);
        run(q, 5, 5);
        assertEquals(0, q.tier());
    }

    @Test void recoversWhenThereIsHeadroomButOnlyAfterTheHold() {
        var q = new FxQuality();
        run(q, 40, 30);
        assertEquals(3, q.tier());
        run(q, 6, 3);
        assertEquals(3, q.tier(), "not at once");
        run(q, 6, 40);
        assertEquals(0, q.tier());
        assertEquals(1, q.particleScale(), 1e-9);
    }

    @Test void particleShareSlewsWithoutJumping() {
        var q = new FxQuality();
        double prev = q.particleScale(), worst = 0;
        for (double t = 0; t < 30; t += 1 / 60.0) {
            q.update(40, 1 / 60.0);
            worst = Math.max(worst, Math.abs(q.particleScale() - prev));
            prev = q.particleScale();
        }
        assertTrue(worst < .01, "per-frame change " + worst);
    }

    @Test void aTierTakenBackSoonMakesTheNextRiseWaitLonger() {
        var q = new FxQuality();
        run(q, 40, 2);                     // not 3 s: a second step down would then land on the first fast frame
        int low = q.tier();
        run(q, 6, 7.5);                    // climbs one tier after the first hold
        assertEquals(low - 1, q.tier(), "tier after the climb");
        run(q, 40, 2.5);                   // too much again, soon after
        assertEquals(low, q.tier());
        run(q, 6, 6);
        assertEquals(low, q.tier(), "the hold doubled: still down after 6 s");
    }

    @Test void forcedTierStays() {
        var q = new FxQuality();
        q.force(2);
        run(q, 3, 60);
        assertEquals(2, q.tier());
    }
}
