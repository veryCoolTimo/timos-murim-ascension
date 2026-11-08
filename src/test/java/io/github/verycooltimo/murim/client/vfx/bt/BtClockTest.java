package io.github.verycooltimo.murim.client.vfx.bt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BtClockTest {
    @Test void sceneAndTailJoinAtBlow() {
        assertEquals(-1, BtClock.labSeconds(304, -1, -1, .5));
        assertEquals(3.025, BtClock.labSeconds(304, 60, -1, .5), 1e-9);
        assertEquals(15.2, BtClock.labSeconds(304, 310, -1, .5), 1e-9);
        assertEquals(15.2, BtClock.labSeconds(304, -1, 0, 0), 1e-9);
        assertEquals(19.975, BtClock.labSeconds(304, -1, 95, .5), 1e-9);
        assertEquals(-1, BtClock.labSeconds(304, -1, -1, 0));
    }
    @Test void peakImpactAndRecoveryIgnoreLateRankup() {
        for(int delay=0;delay<=8;delay++) {
            int packetTick=510+delay;
            var clock=new BtSceneClock();
            clock.sync(509,true,false);
            for(int tick=509;tick<=525;tick++) {
                int releaseAge=tick>=packetTick?tick-packetTick:-1;
                if(tick==packetTick)clock.sync(-1,false,true);
                double seconds=BtClock.peakSeconds(clock.age(),0);
                assertEquals(tick/20.0,seconds,1e-9,"release age "+releaseAge);
                if(tick==509)assertEquals(0,BtImpactHold.peakAlpha(seconds));
                if(tick==510)assertEquals(1,BtImpactHold.peakAlpha(seconds));
                if(tick==516) {assertEquals(25.8,seconds);assertEquals(0,BtImpactHold.peakAlpha(seconds));}
                if(tick==525)assertEquals(26.25,seconds);
                clock.tick();
            }
            clock.sync(-1,false,false);
            assertEquals(-1,clock.age());
        }
    }
}
