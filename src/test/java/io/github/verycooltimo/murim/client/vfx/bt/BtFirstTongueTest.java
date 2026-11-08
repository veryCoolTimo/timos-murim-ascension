package io.github.verycooltimo.murim.client.vfx.bt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BtFirstTongueTest {
    @Test void narrowSideTongueRemainsBroadAtExactMinimumPhase() {
        assertEquals(.08316,BtFirstTongue.width(.055,0,-Math.PI/2),1e-12);
        assertTrue(BtFirstTongue.width(.055,2.0/3,-Math.PI/2-6)>=.08);
        assertEquals(0,BtFirstTongue.width(.055,1,-Math.PI/2-9));
    }
    @Test void overlappingClustersFitMatureLabEnvelope() {
        assertTrue(BtFirstTongue.COUNT>=18&&BtFirstTongue.COUNT<=24);
        double top=0,edge=0;
        for(int lane=0;lane<BtFirstTongue.COUNT;lane++) {
            var tongue=BtFirstTongue.lane(lane);
            assertTrue(tongue.halfWidth()*2>=.15&&tongue.halfWidth()*2<=.26);
            top=Math.max(top,tongue.base()+tongue.length());
            edge=Math.max(edge,Math.abs(tongue.x())+tongue.halfWidth());
            // The crown behind the head overlaps; only the last lanes are low wisps in front, outside the knees.
            if(lane<4) {
                var next=BtFirstTongue.lane(lane+1);
                assertTrue(next.x()-tongue.x()<next.halfWidth()+tongue.halfWidth());
            }
            if(lane>=19) {assertTrue(tongue.front());assertTrue(Math.abs(tongue.x())>=.6);assertTrue(tongue.base()+tongue.length()<.35);}
            else assertFalse(tongue.front());
        }
        assertTrue(top>=1.6&&top<=1.9);
        assertTrue(edge*2>=1.4&&edge*2<=1.7);
    }
    @Test void everyBodySegmentRemainsBroadUntilItsTipThird() {
        for(int lane=0;lane<BtFirstTongue.COUNT;lane++) {
            double half=BtFirstTongue.lane(lane).halfWidth();
            for(int segment=0;segment<=32;segment++)for(int phase=0;phase<64;phase++) {
                double u=segment/48.0;
                assertTrue(BtFirstTongue.width(half,u,phase*Math.PI/32)>=.08);
                // Side tongues use at least .055 block half width.
                assertTrue(BtFirstTongue.width(.055,u,phase*Math.PI/32)>=.08);
            }
            assertEquals(0,BtFirstTongue.width(half,1,0));
        }
    }
}
