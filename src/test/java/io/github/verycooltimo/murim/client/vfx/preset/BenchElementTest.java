package io.github.verycooltimo.murim.client.vfx.preset;

import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Pure clock/selection/lab ports: no game or scene needs to be initialized. */
class BenchElementTest {
    private static PeakLineElement line(boolean thin) throws Exception {
        try(var stream=BenchElementTest.class.getResourceAsStream("/assets/murim/vfx_presets/fist.json")) {
            assertNotNull(stream);
            var preset=EssencePreset.read(new InputStreamReader(stream,StandardCharsets.UTF_8));
            return new PeakLineElement(preset.layers().getFirst(),thin);
        }
    }
    @Test void thickLookAndTwinOverrideTheFistAndDefaults() throws Exception {
        var element=line(false);var parent=element.merged(false);var twin=element.merged(true);
        assertEquals(.5,parent.p("bodyAmt"));assertEquals(.65,parent.p("coreAmt"));assertEquals(.38,parent.p("ragged"));
        assertEquals(.32,parent.p("width"));assertEquals(13.8,parent.p("start"));
        assertEquals(26,parent.seed()); // D row i=2, noiseSeed=20+i*3.
        assertEquals(20+(2+40)*3,twin.seed());
        assertEquals(.45,twin.p("coreAmt"));assertEquals(.5,twin.p("bodyAmt"));
        assertEquals(.16,twin.p("width"));assertEquals(13.95,twin.p("start"),1e-12);
        assertEquals(.98,twin.p("paletteStart"),1e-12);assertEquals(1.14,twin.p("paletteEnd"),1e-12);
        assertEquals(26.2,parent.p("start")+parent.p("dur"),1e-12);
        assertEquals(.35,parent.p("outDur"));assertEquals(2,parent.p("outMode"));assertEquals(0,parent.p("pCount"));
        assertEquals(2,element.ribbons(20).size());
        for(var ribbon:element.ribbons(20)) {
            // Exercise the actual production material (not a replacement flat colour).
            var l=ribbon.layer();var material=new EssenceMaterial(l);
            var rgb=material.shade(.5,0,1,1,20-l.p("start"),EssenceMath.trim(l,20-l.p("start"),0),0,1,3.7+l.seed()*7.31,0,16);
            assertTrue(Double.isFinite(rgb.r())&&Double.isFinite(rgb.g())&&Double.isFinite(rgb.b()));
            assertTrue(rgb.r()+rgb.g()+rgb.b()>0);
            assertEquals(16,l.path().size());assertEquals(24,l.widthProfile().size());
        }
    }
    @Test void modeFiveRunsPinkToOrangeAndOffsetsTheGoldTwin() throws Exception {
        var element=line(false);
        for(boolean twin:new boolean[]{false,true}) {
            var l=element.merged(twin);double shift=twin?.08:0;
            for(double u:new double[]{0,.25,.5,.75,1}) {
                var rgb=EssenceMaterial.hueRun(l.p("paletteStart"),l.p("paletteEnd"),u);
                int expected=io.github.verycooltimo.murim.client.vfx.bt.BtPeakMath.hue(.9+shift+.16*u);
                assertEquals((expected>>16)&255,Math.round(rgb.r()*255));
                assertEquals((expected>>8)&255,Math.round(rgb.g()*255));
                assertEquals(expected&255,Math.round(rgb.b()*255));
            }
            // Exercise shade too: mode 5 must not fall through to the red/amber palette.
            var material=new EssenceMaterial(l);
            for(double u:new double[]{.2,.8}) {
                var actual=material.shade(u,.2,1,1,4,new EssenceMath.Trim(0,1,0,0),1,1,3.7,0,16);
                var expected=EssenceMaterial.hueRun(.9+shift,1.06+shift,u)
                        .blend(EssenceMaterial.RGB.hex(l.colors().get("body")),l.p("goldTint"));
                double expectedPeak=Math.max(expected.r(),Math.max(expected.g(),expected.b()));
                expected=expected.mul(1/expectedPeak);
                double peak=Math.max(actual.r(),Math.max(actual.g(),actual.b()));
                assertTrue(peak>0);
                assertEquals(expected.r(),actual.r()/peak,1e-10);
                assertEquals(expected.g(),actual.g()/peak,1e-10);
                assertEquals(expected.b(),actual.b()/peak,1e-10);
            }
        }
        assertNotEquals(element.ribbons(20).get(0).layer().path(),element.ribbons(20).get(1).layer().path());
    }
    @Test void thinIsOnlyTheRoseRow() throws Exception {
        var element=line(true);var l=element.merged(false);
        assertEquals(.11,l.p("width"));assertEquals(15.4,l.p("start"));
        assertEquals(1,l.p("coreAmt"));assertEquals(.25,l.p("ragged"));assertEquals(0,l.p("pleat"));
        assertEquals(1,element.ribbons(20).size());assertTrue(element.ribbons(15.3).isEmpty());
    }
    @Test void ticksAreLabSecondsAndStopExactlyAtWindowEnd() {
        var clock=new BenchClock(13.8,27,false);
        assertEquals(13.8,clock.seconds(0));assertEquals(13.825,clock.seconds(.5),1e-12);
        for(int i=0;i<263;i++)clock.tick();
        assertFalse(clock.finished());assertEquals(26.95,clock.seconds(0),1e-12);
        clock.tick();assertTrue(clock.finished());assertEquals(27,clock.seconds(.9));
        clock.tick();assertEquals(27,clock.seconds(0));
        var loop=new BenchClock(1,2,true);for(int i=0;i<20;i++)loop.tick();
        assertFalse(loop.finished());assertEquals(1,loop.seconds(0));
        assertThrows(IllegalArgumentException.class,()->new BenchClock(2,1,false));
    }
    @Test void selectionNeverRegistersASceneOrOtherElement() {
        for(String name:List.of("line","line_thin","fire1","preset:fist")) {
            var selection=BenchSelection.of(name,-1,-1,4);
            var drawn=new java.util.ArrayList<String>();
            selection.draw(()->drawn.add("line"),()->drawn.add("fire1"),()->drawn.add("preset"));
            assertEquals(List.of(name.startsWith("preset:")?"preset":name.equals("line_thin")?"line":name),drawn);
        }
        assertEquals(12,BenchSelection.of("fire1",-1,-1,0).to());
        assertThrows(IllegalArgumentException.class,()->BenchSelection.of("bt_peak",-1,-1,0));
        assertThrows(IllegalArgumentException.class,()->BenchSelection.of("line",0,27,0));
        assertThrows(IllegalArgumentException.class,()->BenchSelection.of("line",20,28,0));
    }
    @Test void benchFireUsesLabClockAndProducesGeometryAtFourSeconds() {
        var selection=BenchSelection.of("fire1",-1,-1,0);
        var clock=new BenchClock(selection.from(),selection.to(),false);
        for(int i=0;i<80;i++)clock.tick();
        var geometry=io.github.verycooltimo.murim.client.vfx.bt.BtFireElement.benchGeometry(clock.seconds(0),.7,1.2);
        assertEquals(16,geometry.size());
        for(var flame:geometry) {
            assertTrue(flame.height()>0);assertTrue(flame.halfWidth()>0);assertTrue(flame.alpha()>.001);
            assertTrue(Double.isFinite(flame.x()+flame.depth()+flame.baseHeight()+flame.seed()));
        }
        assertTrue(io.github.verycooltimo.murim.client.vfx.bt.BtFireElement.benchGeometry(0,.7,1.2).isEmpty());
        assertTrue(io.github.verycooltimo.murim.client.vfx.bt.BtFireElement.benchGeometry(12,.7,1.2).isEmpty());
        // The scene's quality gate is deliberately absent from the isolated bench input.
        assertTrue(io.github.verycooltimo.murim.client.vfx.bt.BtFireGeometry.at(true,4,0,.7,1.2).isEmpty());
        var alpha=BenchSelection.of("alpha_test",-1,-1,0);
        assertEquals(0,alpha.from());assertEquals(12,alpha.to());
        var calls=new java.util.ArrayList<String>();
        alpha.draw(()->calls.add("alpha_test"),()->fail("fire route"),()->fail("preset route"));
        assertEquals(List.of("alpha_test"),calls);
    }
    @Test void fireHoldsUntilNineAndAHalfThenShrinksToLowEmbers() {
        assertEquals(1,io.github.verycooltimo.murim.client.vfx.bt.BtBenchFire.recession(9));
        assertEquals(1,io.github.verycooltimo.murim.client.vfx.bt.BtBenchFire.recession(9.5));
        assertEquals(.5,io.github.verycooltimo.murim.client.vfx.bt.BtBenchFire.recession(10),1e-12);
        assertEquals(0,io.github.verycooltimo.murim.client.vfx.bt.BtBenchFire.recession(10.5));
        assertEquals(0,io.github.verycooltimo.murim.client.vfx.bt.BtBenchFire.emberCount(10.49));
        assertEquals(5,io.github.verycooltimo.murim.client.vfx.bt.BtBenchFire.emberCount(10.5));
        assertEquals(5,io.github.verycooltimo.murim.client.vfx.bt.BtBenchFire.emberCount(11));
        assertEquals(0,io.github.verycooltimo.murim.client.vfx.bt.BtBenchFire.emberCount(11.5));
    }
    @Test void orbitRestoresRoundThreeBodyAndGlowWithDelayedGoldTwin() throws Exception {
        var ribbons=line(false).ribbons(20);
        // Round 3's lab W profile, without round 4's extra sine taper.
        double[] w={.2,.45,.65,.8,.92,1,1,.95,.8};
        for(int r=0;r<ribbons.size();r++) {
            var ribbon=ribbons.get(r);var widths=ribbon.layer().widthProfile();
            double phase=(2+(r==1?40:0))*1.7;
            for(int j=0;j<=23;j++) {
                double u=j/23.0,f=u*8;int i=Math.min(7,(int)Math.floor(f));
                double d=ribbon.depth().get((int)Math.round(u*15));
                double expected=(w[i]+(w[i+1]-w[i])*(f-i))*(.5+.5*Math.abs(Math.cos(Math.PI*(u-1.1*20)+phase)))*(.72+.33*d)*.8;
                if(r==1)expected*=j==0||j==23?0:Math.pow(Math.sin(Math.PI*u),.45);
                assertEquals(expected,widths.get(j),1e-12);
            }
            assertEquals(r==0?.6:.15,ribbon.layer().p("haloAmt")/(1+.45*EssenceMath.smooth(19,25.2,20)),1e-12);
        }
        assertEquals(.32,ribbons.getFirst().layer().p("width"),1e-12);
        assertEquals(.9,ribbons.get(1).layer().p("goldTint"),1e-12);
        var parent=ribbons.get(0).layer().path();var twin=ribbons.get(1).layer().path();
        assertTrue(Math.hypot(parent.get(8).x()-twin.get(8).x(),parent.get(8).y()-twin.get(8).y())>.03);
    }
    @Test void releaseIsContinuousAndEjectsTheWholeTrail() throws Exception {
        var element=line(false);var before=element.at(25.71);var after=element.at(25.710001);
        assertTrue(Math.hypot(after.x()-before.x(),after.y()-before.y())<.1);
        var released=element.trail(26.1);
        // Every point receives the same upper-right ejection, including the oldest tail.
        var orbit=element.trail(25.71);
        assertTrue(released.getFirst().x()>orbit.getFirst().x()+300);
        assertTrue(released.getFirst().y()<orbit.getFirst().y()-200);
        assertTrue(element.ribbons(27).isEmpty());
    }
}
