package io.github.verycooltimo.murim.client.vfx.bt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BtRank1MathTest {
    @Test void levelFollowsLabPhases() {
        assertEquals(0,BtRank1Math.level(0).energy());assertTrue(BtRank1Math.level(3.6).energy()>=.95);
        assertTrue(BtRank1Math.level(7.5).tear()>0);assertEquals(1,BtRank1Math.level(11.6).control());
        assertEquals(0,BtRank1Math.level(12).energy());assertEquals(1,BtRank1Math.level(14.2).violet());
        assertEquals(0,BtRank1Math.level(15.2).shell());assertEquals(1,BtRank1Math.level(15.2).aura());assertEquals(.85,BtRank1Math.level(20).aura(),1e-9);
    }
    @Test void inflowHasFourteenDeterministicCurvesWithExactEndpoints() {
        var streams=BtRank1Math.streams();assertEquals(14,streams.size());assertEquals(streams,BtRank1Math.streams());
        for(var s:streams) {
            assertEquals(s.start(),BtRank1Math.bezier(s,0));assertEquals(s.end(),BtRank1Math.bezier(s,1));
            assertTrue(s.t0()<s.t1());assertTrue(s.t1()<=304);assertTrue(Math.abs(s.start().x()-400)>200,"no stream directly over the face");
        }
    }
    @Test void rank1FireRewindsAndControlsDeterministically() {
        var a=new BtFireSim(BtFireSim.Mode.FIRST);var b=new BtFireSim(BtFireSim.Mode.FIRST);
        a.advance(7.5);b.advance(7.5);assertFalse(a.particles().isEmpty());assertEquals(a.particles().size(),b.particles().size());
        for(int i=0;i<a.particles().size();i++)assertEquals(a.particles().get(i).trail,b.particles().get(i).trail);
        a.advance(13.5);assertTrue(a.particles().isEmpty());a.advance(7.5);assertEquals(a.particles().size(),b.particles().size());
    }
}
