package io.github.verycooltimo.murim.client.vfx.bt;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class BtFireSimTest {
    private static List<String> snapshot(BtFireSim s) {
        return s.particles().stream().map(p->p.x+":"+p.y+":"+p.z+":"+p.age+":"+p.life+":"+p.trail).toList();
    }
    @Test void deterministicAndIndependentOfFrameSchedule() {
        BtFireSim direct=new BtFireSim(),stepped=new BtFireSim();
        direct.advance(11.5);
        for(int i=0;i<=230;i++)stepped.advance(i/20.0);
        assertEquals(snapshot(direct),snapshot(stepped));
        BtFireSim repeat=new BtFireSim();for(double t:new double[]{0,3,6.4,10.1,10.8,11.5})repeat.advance(t);
        assertEquals(snapshot(direct),snapshot(repeat));
        repeat.advance(0);repeat.advance(11.5);assertEquals(snapshot(direct),snapshot(repeat));
    }
    @Test void particlesAndTrailsAreBoundedAndBurnOut() {
        BtFireSim s=new BtFireSim();int peak=0;
        for(int tick=0;tick<=400;tick++) {
            s.advance(tick/20.0);peak=Math.max(peak,s.particles().size());
            assertTrue(s.particles().size()<=BtFireSim.CAP);
            for(var p:s.particles())assertTrue(p.trail.size()<=8);
        }
        assertTrue(peak>=170);assertTrue(s.particles().isEmpty());
    }
    @Test void fireIsPressedDownBeforeBlowAndReturnsAfterIt() {
        assertTrue(BtFireSim.level(10.75).energy()<BtFireSim.level(10).energy());
        assertTrue(BtFireSim.level(10.9).energy()>.55);
        assertEquals(0,BtFireSim.level(15.6).energy(),.001);
    }
}
