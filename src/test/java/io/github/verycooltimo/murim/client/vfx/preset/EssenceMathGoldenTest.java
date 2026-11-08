package io.github.verycooltimo.murim.client.vfx.preset;

import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.verycooltimo.murim.client.vfx.preset.EssenceMath.*;

/** No Minecraft/NeoForge imports or initialization. Values are scalar evaluations of the
 * supplied lab.js formulas in SCREEN coordinates, converted back with fromScene.
 * The oracle did not call EssenceMath. Expected values intentionally live in the test.
 * Tolerance admits platform sin() differences amplified by the JS hash multiplier 43758.
 */
class EssenceMathGoldenTest {
    private static final double EPS = 2e-7;
    private static EssencePreset load(String scene) throws Exception {
        try (var stream = EssenceMathGoldenTest.class.getResourceAsStream("/assets/murim/vfx_presets/"+scene+".json")) {
            assertNotNull(stream, scene);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return EssencePreset.read(reader);
            }
        }
    }
    @Test void laboratoryPathWidthTwistAndTrim() throws Exception {
        check("main", 0, 0.17000000000000001, 0.25, 0, new double[]{-0.38077272817042218, 1.2436772582641227, 0.022936468566780617, 0.80196055814755118, 1, 0.55401108972194313, 0, 0.73432944606414008, 0});
        check("main", 1, 0.63, 1.2, 2, new double[]{-0.40302397466901424, 0.18009568258240052, 0.12991954179338105, 0.28269810805081919, -1, -0.73987822037030648, 0, 1, 0});
        check("main", 2, 0.41999999999999998, 0.80000000000000004, 0, new double[]{0.27309265681443939, 1.3393513649723152, 0.01564393257114198, 0.44850488505666142, 1, 0.59803618295688432, 0, 1, 0});
        check("green", 2, 0.31, 0.45000000000000001, 0, new double[]{0.55000609766762087, 1.1324915400687763, 0.024182544677266913, 0.70984755603250038, 1, 0.38462126102659316, 0, 0.95444606413994171, 0});
        check("fist", 0, 0.5, 1.1000000000000001, 0, new double[]{-0.09796265890620702, 0.017408590601991947, 0.096207306992925026, 0.38461885848714861, 1, 0.77840598474761369, 0, 1, 0});
        check("arc", 0, 0.82999999999999996, 1.6000000000000001, 0, new double[]{-0.26672999727162294, 0.41848340090627012, 0.0071469619200000001, 0.69957055241272814, 1, 0.71456353265259098, 0, 1, 0});
        check("green", 1, 0.37, 0.90000000000000002, 1, new double[]{-0.3497522990129317, 0.72939432742121502, 0.017593862779388073, 0.98698106868085156, 1, -0.10483270886450655, 0, 1, 0});
        check("silk", 0, 0.70999999999999996, 2.1000000000000001, 0, new double[]{0.41456928228458861, 0.57739487932921629, 0.035012218109909267, 0.33313249742520823, -1, 0.78884455891469962, 0.19753086419753074, 1, 0.19753086419753074});
        check("silk", 4, 0, 0.10000000000000001, 0, new double[]{-0.76165534746326391, 1.5320339576102082, 0.41364834686491331, 0.91219407455293067, 1, 0.29553551617166568, 0, 0.370262390670554, 0});
        check("main", 0, 1, 4, 0, new double[]{0.39744468931762739, -0.32156206494689121, 0.0029205099657764301, 0.71049994480069389, -1, 0.67470232839196231, 1, 1, 1});
    }
    private static void check(String scene,int index,double u,double t,int sheet,double[] expected) throws Exception {
        var layer=load(scene).layers().get(index);
        var sample=path(layer,u,t);var tw=twist(layer,u,t,sheet);var tr=trim(layer,t,0);
        double[] actual={sample.position().x(),sample.position().y(),halfWidth(layer,u,t,sheet),
                tw.facing(),tw.side(),tw.sine(),tr.a(),tr.b(),tr.luma()};
        assertArrayEquals(expected,actual,EPS,scene+" layer="+index+" u="+u+" t="+t);
    }
    @Test void cubicBezierAndMultiPointEndpoints() {
        var p=java.util.List.of(new EssencePreset.Point(0,0),new EssencePreset.Point(0,1),
                new EssencePreset.Point(1,1),new EssencePreset.Point(1,0));
        assertEquals(.15625,curve(p,.25).x(),1e-12);
        assertEquals(.5625,curve(p,.25).y(),1e-12);
        assertEquals(1.125,derivative(p,.25).x(),1e-12);
        assertEquals(1.5,derivative(p,.25).y(),1e-12);
        var q=new java.util.ArrayList<>(p);q.add(new EssencePreset.Point(2,-1));
        assertEquals(q.getFirst(),curve(q,0));assertEquals(q.getLast(),curve(q,1));
    }
    @Test void lagAndAllExitModes() throws Exception {
        var source=load("main").layers().getFirst();
        for(int mode=0;mode<=2;mode++) {
            var params=new HashMap<>(source.params());params.put("outMode",(double)mode);
            var l=new EssencePreset.Layer(params,source.colors(),source.path(),source.widthProfile(),source.folds(),source.veils(),source.seed());
            double t=l.p("dur")-l.p("outDur")*.5;
            var tr=trim(l,t+.12,.12);
            assertEquals(mode==2?0:.25,tr.a(),1e-12);
            assertEquals(mode==1?0:.25,tr.luma(),1e-12);
            assertEquals(.25,tr.out(),1e-12);
            assertEquals(0,trim(l,-.1,0).b(),1e-12);
            assertEquals(1,trim(l,l.p("dur")+.1,0).out(),1e-12);
        }
    }
    @Test void exportsContainEveryAuthoredRibbon() throws Exception {
        String[] names={"main","fist","arc","green","silk"};int[] counts={4,4,6,8,5};
        for(int i=0;i<names.length;i++)assertEquals(counts[i],load(names[i]).layers().size(),names[i]);
    }
}
