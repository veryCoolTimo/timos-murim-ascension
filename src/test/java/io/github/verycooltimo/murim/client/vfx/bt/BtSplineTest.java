package io.github.verycooltimo.murim.client.vfx.bt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BtSplineTest {
    @Test void broadBaseOnlyTapersInTopThirdAndChannelsVaryThreefold() {
        assertTrue(BtSpline.flameWidth(0)>=.9);
        assertEquals(1,BtSpline.flameWidth(.6),1e-12);
        assertTrue(BtSpline.flameWidth(.9)<BtSpline.flameWidth(.7));
        for(int ch=0;ch<5;ch++)for(int i=0;i<=1000;i++) {
            double w=BtSpline.channelWidth(i/1000.0,ch);
            assertTrue(w>=.5&&w<=1.5);
        }
    }
    @Test void passesThroughKnotsAndPreservesStraightLines() {
        assertEquals(2,BtSpline.sample(-1,2,5,8,0),1e-12);
        assertEquals(5,BtSpline.sample(-1,2,5,8,1),1e-12);
        for(int i=0;i<=12;i++) {
            double t=i/12.0;assertEquals(2+3*t,BtSpline.sample(-1,2,5,8,t),1e-12);
        }
    }
    @Test void adjacentSegmentsHaveContinuousTangent() {
        double e=1e-6;
        double left=(BtSpline.sample(-1,2,4,1,1)-BtSpline.sample(-1,2,4,1,1-e))/e;
        double right=(BtSpline.sample(2,4,1,6,e)-BtSpline.sample(2,4,1,6,0))/e;
        assertEquals(left,right,1e-4);
        assertEquals(0,BtSpline.flameWidth(1));
        assertTrue(BtSpline.flameWidth(.2)>BtSpline.flameWidth(0));
    }
}
