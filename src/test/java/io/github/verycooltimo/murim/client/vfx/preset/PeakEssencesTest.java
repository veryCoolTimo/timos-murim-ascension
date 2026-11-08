package io.github.verycooltimo.murim.client.vfx.preset;

import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PeakEssencesTest {
    private static EssencePreset.Layer layer(String name) throws Exception {
        try(var stream=PeakEssencesTest.class.getResourceAsStream("/assets/murim/vfx_presets/"+name+".json")) {
            assertNotNull(stream);
            return EssencePreset.read(new InputStreamReader(stream,StandardCharsets.UTF_8)).layers().getFirst();
        }
    }
    @Test void allEighteenRowsMergeLooksBeforeOverrides() throws Exception {
        var main=layer("main");var bands=new PeakEssences(layer("fist"),main).merged();
        assertEquals(18,bands.size());
        assertEquals(.13,bands.get(0).p("bodyAmt"));assertEquals(.09,bands.get(1).p("bodyAmt"));
        assertEquals(.07,bands.get(0).p("coreAmt"));assertEquals(.06,bands.get(1).p("coreAmt"));
        for(int i=0;i<2;i++) {
            var l=bands.get(i);assertEquals(main.folds(),l.folds());assertEquals(.2,l.p("foldDetail"));
            assertEquals(.12,l.p("haloAmt"));assertEquals(25.96,l.p("start")+l.p("dur"),1e-12);
            assertEquals(20+i*3,l.seed());assertEquals(0,l.p("fiberAmt"));
        }
        int at=2,twins=0;
        for(int i=0;i<12;i++) {
            var r=PeakLineElement.ROWS[i];var l=bands.get(at++);
            assertEquals(r[0],l.p("width"));assertEquals(r[1],l.p("start"));
            assertEquals(20+(i+2)*3,l.seed());assertEquals(r[10],l.p("paletteStart"));assertEquals(r[11],l.p("paletteEnd"));
            assertEquals(r[0]<.15?.4:r[0]>=.26?.5:.42,l.p("bodyAmt"));
            assertEquals(r[0]<.15?1:r[0]>=.26?.65:.85,l.p("coreAmt"));
            assertEquals(25.5+r[9]+.14+.35,l.p("start")+l.p("dur"),1e-12);
            assertEquals(0,l.p("pCount"));assertEquals(0,l.p("threads"));
            if(r[12]>0) {
                var twin=bands.get(at++);twins++;
                assertEquals(20+(i+2+40)*3,twin.seed());assertEquals(.45,twin.p("coreAmt"));
                assertEquals(r[0]*.5,twin.p("width"));assertEquals(r[1]+.15,twin.p("start"));
                assertEquals(r[10]+.08,twin.p("paletteStart"));assertEquals(r[11]+.08,twin.p("paletteEnd"));
                assertEquals(l.p("haloAmt"),twin.p("haloAmt"));
            }
        }
        assertEquals(4,twins);assertEquals(18,at);
        var bench=BenchSelection.of("essences",-1,-1,0);
        assertEquals(12.6,bench.from());assertEquals(27,bench.to());
    }
    @Test void sceneOrbitsPreserveEveryLabHeightRadiusTiltAndSpeed() throws Exception {
        var fist=layer("fist");
        var table=io.github.verycooltimo.murim.client.vfx.bt.BtPeakMath.ESSENCES;
        for(int i=0;i<12;i++) {
            var element=new PeakLineElement(fist,PeakLineElement.ROWS[i],i+2,"pink");
            var orbit=table.get(i);
            assertEquals(orbit.radius(),element.orbitRadius());
            assertEquals((585-orbit.yc())/360,
                    PeakLineElement.blocksY((540-orbit.yc())/350),1e-12);
            assertEquals(orbit.radius()/360,PeakLineElement.blocksX(orbit.radius()/350),1e-12);
            for(double t:new double[]{17,19.5,22.5,24,25.2}) {
                var expected=io.github.verycooltimo.murim.client.vfx.bt.BtPeakMath.head(orbit,t);
                var actual=element.at(t);
                assertEquals(expected.x(),actual.x(),.01,"row "+i+" at "+t);
                assertEquals(expected.y(),actual.y(),.01,"row "+i+" at "+t);
                assertEquals(expected.depth(),actual.depth(),.0001);
            }
        }
        assertEquals(0,PeakLineElement.blocksY((540-585)/350.0),1e-12);
        assertEquals(1,PeakLineElement.blocksX(360.0/350),1e-12);
    }
    @Test void fireSharesAuraStrokeRenderTypeAndTrailImplementation() throws Exception {
        // Like DialogueCaptureGateTest, allow nested test-runner working directories.
        Path relativeSource=Path.of("src/main/java/io/github/verycooltimo/murim/client/vfx/bt/BtDraw.java");
        Path workingDirectory=Path.of("").toAbsolutePath();
        Path root=workingDirectory;
        while(root!=null&&!Files.isRegularFile(root.resolve(relativeSource))) {
            root=root.getParent();
        }
        assertNotNull(root,"BtDraw.java source tree not found above "+workingDirectory);
        String draw=Files.readString(root.resolve(relativeSource));
        int start=draw.indexOf("static void auraFlame(");int end=draw.indexOf("private static final VfxColour FIRE_EDGE",start);
        assertTrue(start>=0,"auraFlame method not found in BtDraw.java");
        assertTrue(end>start,"FIRE_EDGE boundary not found after auraFlame");
        int first=draw.indexOf("static void firstFlame(");
        String firstRate=draw.substring(first,start);
        assertTrue(firstRate.contains("blueFlame(c,trail"),"First Rate needs an alpha body beneath its brush crests");
        assertTrue(firstRate.contains("MurimRenderTypes.ribbon()"),"cyan sheet layer missing");
        assertTrue(firstRate.contains("if(seed>.9)"),"brush crests must stay sparse");
        assertTrue(firstRate.contains("MurimRenderTypes.auraStrokes()"));
        String flow=draw.substring(start,end);
        assertTrue(flow.contains("MurimRenderTypes.auraStrokes()"));
        assertTrue(flow.contains("AuraRenderer.flowStrip("));
        assertFalse(flow.contains("btFlame()"));
    }
}
