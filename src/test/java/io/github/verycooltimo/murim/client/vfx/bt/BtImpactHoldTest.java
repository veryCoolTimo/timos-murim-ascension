package io.github.verycooltimo.murim.client.vfx.bt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BtImpactHoldTest {
    @Test void peakImpactStartsAtLab255OnTheSceneClockAndEndsBefore258() {
        assertEquals(25.5*20,BtImpactHold.startTick(4,516));
        assertEquals(25.5,BtClock.labSeconds(516,510,-1,0));
        assertEquals(0,BtImpactHold.peakAlpha(25.4999));
        assertEquals(1,BtImpactHold.peakAlpha(25.5));
        assertTrue(BtImpactHold.peakAlpha(25.7)>0);
        assertEquals(0,BtImpactHold.peakAlpha(25.75));
        assertEquals(0,BtImpactHold.peakAlpha(BtClock.labSeconds(516,-1,0,0)));
        assertEquals(0,BtImpactHold.peakAlpha(26.25));
    }

    @Test void peakStartsAtSceneTick510AndHoldsTwoTicks() {
        assertEquals(510,BtImpactHold.startTick(4,510));
        assertEquals(510,BtImpactHold.startTick(4,512)); // Packet age must not shift the lab tick.
        assertEquals(303,BtImpactHold.startTick(3,304));
        var peak=new BtImpactHold();
        for(int tick=509;tick<=512;tick++) {
            long now=(tick-510)*50_000_000L;
            if(tick>=BtImpactHold.startTick(4,510))peak.trigger(4,now);
            assertEquals(tick>=510&&tick<512,peak.frame(now,true).gradient());
        }
    }

    @Test void peakAndFirstBlowsKeepGradientAndSilhouetteThroughHandoffForTwoTicks() {
        var first=new BtImpactHold();var peak=new BtImpactHold();
        first.trigger(3,0);peak.trigger(4,0);
        for(long now:new long[]{0,50_000_000,99_999_999}) {
            // The release packet must not clear or restart either graphic blow.
            first.trigger(3,now);peak.trigger(4,now);
            var expected=first.frame(now,true);var actual=peak.frame(now,true);
            assertEquals(expected,actual);
            assertEquals(1,actual.alpha());assertTrue(actual.gradient());
            assertTrue(actual.silhouette());assertTrue(actual.ignoresSceneDepth());
        }
        assertFalse(peak.frame(100_000_000,true).gradient());
        assertFalse(first.frame(100_000_000,true).silhouette());
    }
    @Test void missingPolygonDoesNotSpendTheVisibleHold() {
        var peak=new BtImpactHold();peak.trigger(4,0);
        assertFalse(peak.frame(200_000_000,false).gradient());
        assertEquals(1,peak.frame(300_000_000,true).alpha());
        assertEquals(1,peak.frame(399_999_999,true).alpha());
    }
    @Test void slowRendererStillGetsTwoFrames() {
        var h=new BtImpactHold();h.trigger(0);
        assertEquals(1,h.render(200_000_000));
        assertEquals(1,h.render(400_000_000));
        assertEquals(0,h.render(600_000_000));
    }
    @Test void delayedFirstDrawStillGetsHundredVisibleMilliseconds() {
        var h=new BtImpactHold();h.trigger(0);
        assertEquals(1,h.render(200_000_000));
        assertEquals(1,h.render(201_000_000));
        assertEquals(1,h.render(299_999_999));
        assertEquals(0,h.render(300_000_000));
    }
    @Test void fastRendererHoldsHundredMillisecondsAndResets() {
        var h=new BtImpactHold();h.trigger(0);
        assertEquals(1,h.render(0));assertEquals(1,h.render(10_000_000));
        assertEquals(1,h.render(99_999_999));assertEquals(0,h.render(100_000_000));
        h.trigger(100_000_000);assertEquals(1,h.render(100_000_000));
        h.reset();assertEquals(0,h.render(100_000_001));
    }
}
