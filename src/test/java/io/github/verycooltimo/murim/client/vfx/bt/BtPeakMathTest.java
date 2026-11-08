package io.github.verycooltimo.murim.client.vfx.bt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BtPeakMathTest {
    @Test void haloUsesTheLabCentreAndBreathingPatchRadius() {
        assertEquals(.72,BtPeakHalo.height(),1e-12);
        for(double t:new double[]{12.6,14.8,20,25.2,25.7}) {
            // SIZE is .815 lab blocks ×1.3 since 7160fb3b (the port's shell read as a weak arc next to the lab's bubble)
            double base=BtPeakHalo.SIZE*BtPeakMath.haloScale(t);
            for(int i=0;i<120;i++) {
                double r=BtPeakHalo.radius(t,i*Math.PI*2/120);
                assertTrue(r>=base*.94&&r<=base*1.06);
            }
        }
        assertTrue(BtPeakHalo.radius(25.2,0)<BtPeakHalo.radius(20,0));
    }
    @Test void labCrossSectionAndHueCompression() {
        double[][] expected={{.32,.5,.65,.085,.6,.38},{.2,.42,.85,.085,.55,.35},{.12,.4,1,.12,.45,.25}};
        for(var values:expected) {
            var m=BtPeakMath.material(values[0]);
            assertEquals(values[1],m.body());assertEquals(values[2],m.core());
            assertEquals(values[3],m.coreWidth());assertEquals(values[4],m.halo());assertEquals(values[5],m.ragged());
            assertEquals(m.body(),BtPeakMath.bodyAlpha(m,0,.5,0),1e-9);
            assertEquals(0,BtPeakMath.bodyAlpha(m,1,.5,0));
            assertEquals(m.core(),BtPeakMath.coreAlpha(m,0));
            assertEquals(m.core()/Math.E,BtPeakMath.coreAlpha(m,m.coreWidth()),1e-9);
            assertEquals(m.halo()*.3,BtPeakMath.glowAlpha(m,0),1e-9);
            assertEquals(0,BtPeakMath.glowAlpha(m,3.2));
        }
        assertEquals(0xff8a30,BtPeakMath.secondColour("pink",false));
        assertEquals(0xff3d7a,BtPeakMath.secondColour("pink",true));
        assertEquals(0x4a8cff,BtPeakMath.secondColour("green",true));
        assertArrayEquals(new double[]{1,.5,.25},BtPeakMath.compress(4,2,1),1e-9);
        assertArrayEquals(new double[]{.4,.2,.1},BtPeakMath.compress(.4,.2,.1),1e-9);
    }
    @Test void twelveEssencesAndMonotoneSpin() {
        assertEquals(12,BtPeakMath.ESSENCES.size());
        double previous=0;for(double t=.005;t<34;t+=.005){double p=BtPeakMath.phi(t);assertTrue(p>previous);previous=p;}
    }
    @Test void continuousReleaseAndTangentVelocity() {
        for(var o:BtPeakMath.ESSENCES) {
            double rel=o.release(),dt=1e-7;var h=BtPeakMath.head(o,rel);var before=BtPeakMath.head(o,rel-.01);var after=BtPeakMath.at(o,rel+dt);
            assertEquals(h,BtPeakMath.at(o,rel));
            assertEquals((h.x()-before.x())*100,(after.x()-h.x())/dt,.001);
            assertEquals((h.y()-before.y())*100,(after.y()-h.y())/dt,.001);
            var a=BtPeakMath.trail(o,25.5,16);var b=BtPeakMath.trail(o,25.5+dt,16);
            for(int i=0;i<a.length;i++){assertEquals(a[i].x(),b[i].x(),.01);assertEquals(a[i].y(),b[i].y(),.01);}
        }
    }
    @Test void joltsJumpAndHold() {
        assertEquals(.12,BtPeakMath.front(BtPeakMath.JOLT1,4.8),1e-9);
        assertEquals(.12,BtPeakMath.front(BtPeakMath.JOLT1,4.95),1e-9);
        assertEquals(.25,BtPeakMath.front(BtPeakMath.JOLT1,5.08),1e-9);
        assertEquals(4.47,BtPeakMath.jolt(BtPeakMath.JOLT1,.12),1e-9);
    }
    @Test void deterministicTrailsAndHueWrap() {
        for(var o:BtPeakMath.ESSENCES)assertArrayEquals(BtPeakMath.trail(o,26,24),BtPeakMath.trail(o,26,24));
        assertEquals(BtPeakMath.hue(.1),BtPeakMath.hue(1.1));
        assertEquals(0xff0000,BtPeakMath.hue(0));
    }
}
