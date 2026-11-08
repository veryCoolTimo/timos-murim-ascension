package io.github.verycooltimo.murim.client.vfx.preset;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.*;

class DantianVeinsTest {
    @Test void recipesAreStableBoundedAndScaled() {
        for(int ch=0;ch<5;ch++)for(int seg=0;seg<4;seg++) {
            var a=DantianVeins.generate(ch,seg,1);
            assertEquals(a,DantianVeins.generate(ch,seg,1));
            var scaled=DantianVeins.generate(ch,seg,2);
            assertTrue(a.points().size()>=5&&a.points().size()<=8);
            assertEquals(0,a.points().getFirst().offset());
            assertEquals(0,a.points().getLast().offset());
            for(int i=0;i<a.points().size();i++) {
                assertTrue(Math.abs(a.points().get(i).offset())<=.025);
                assertEquals(a.points().get(i).offset()*2,scaled.points().get(i).offset(),1e-12);
            }
            for(var twig:a.twigs()) {
                assertTrue(twig.length()>=.05&&twig.length()<=.15);
                assertTrue(Math.abs(twig.angle())>=Math.toRadians(25));
                assertTrue(Math.abs(twig.angle())<=Math.toRadians(60));
                assertTrue(twig.delay()>=.1&&twig.delay()<=.2);
                double root=a.points().get(twig.root()).along();
                int order=(ch==0?0:ch<3?4:7)+seg;
                double arrival=MERIDIAN_START+(order+root)*SEGMENT_SECONDS;
                for(double t=0;t<arrival+twig.delay();t+=.01)
                    assertEquals(0,DantianVeins.twigReach(t,ch,seg,root,twig.delay()),1e-12);
                assertTrue(DantianVeins.twigReach(arrival+twig.delay()+.1,ch,seg,root,twig.delay())>0);
            }
        }
        assertNotEquals(DantianVeins.generate(0,0,1),DantianVeins.generate(1,0,1));
    }
    @Test void trunkWidthTapersMonotonically() {
        for(double scale:new double[]{.5,1,2}) {
            double previous=Double.POSITIVE_INFINITY;
            for(int i=0;i<=100;i++) {
                double width=DantianVeins.width(i/100.0,scale);
                assertTrue(width<=previous);previous=width;
            }
            assertEquals(.045*scale,DantianVeins.width(0,scale),1e-12);
            assertEquals(.018*scale,DantianVeins.width(1,scale),1e-12);
        }
    }
    @Test void coreRemainsBrightThroughDarknessAndEndsAsAnEmber() {
        for(double scale:new double[]{.5,1,2}) {
            for(double t=0;t<SEED_TICKS/20.0;t+=.005)if(darkness(t)>0&&t<=RETRACT_START) {
                assertTrue(DantianVeins.coreAlpha(t)>=.6);
                assertTrue(DantianVeins.coreRadius(t,scale)>=.06*scale);
            }
            assertEquals(.025*scale,DantianVeins.coreRadius(SEED_TICKS/20.0,scale),1e-12);
        }
        assertEquals(.06,DantianVeins.coreRadius(CORE_START,1),1e-12);
        assertEquals(.192,DantianVeins.coreRadius(BURST_START,1),1e-12);
        assertEquals(DantianVeins.coreSize(1.2),DantianVeins.coreSize(2.4),1e-12);
        assertEquals(DantianVeins.shimmer(1,.4),DantianVeins.shimmer(2.2,.4),1e-12);
    }
    @Test void coreDominatesTheCentralChannelThroughoutGrowthAndHold() {
        for(double scale:new double[]{.5,1,2}) {
            for(double t=MERIDIAN_START;t<=RETRACT_START;t+=.005) {
                for(var route:DantianVeins.routes())if(route.region()==DantianVeins.Region.CENTRAL) {
                    assertTrue(DantianVeins.coreRadius(t,scale)>=2.5*DantianVeins.routeWidth(route,0,scale));
                }
                assertTrue(DantianVeins.coreAlpha(t)>=DantianVeins.alpha(t));
                assertEquals(1+.06*(DantianVeins.shimmer(t,0)-.72)/.28,DantianVeins.coreSize(t),1e-12);
            }
        }
    }
    @Test void networkContainsOnlySingleRoutesThroughKeyPoints() {
        assertEquals(DantianVeins.routes(),DantianVeins.regenerate());
        assertEquals(22,DantianVeins.routes().size());
        for(var region:DantianVeins.Region.values()) {
            long expected=switch(region) { case CENTRAL -> 3; case NECK -> 1; default -> 2; };
            assertEquals(expected,DantianVeins.routes().stream().filter(r->r.region()==region).count());
        }
        for(var route:DantianVeins.routes()) {
            assertEquals(0,route.from());assertEquals(1,route.to());
            assertEquals(0,DantianVeins.offset(route,0));assertEquals(0,DantianVeins.offset(route,1));
            assertEquals(1,DantianVeins.routes().stream().filter(r->r.channel()==route.channel()
                    &&r.region()==route.region()&&r.segment()==route.segment()).count());
        }
        for(int channel=1;channel<=4;channel++) {
            final int ch=channel;
            long twigs=DantianVeins.routes().stream().filter(r->r.channel()==ch
                    &&r.region()!=DantianVeins.Region.CHEST)
                    .mapToLong(r->DantianVeins.generate(r,1).twigs().size()).sum();
            assertTrue(twigs<=(channel<3?3:2));
        }
        for(var r:DantianVeins.routes())
            assertTrue(DantianVeins.generate(r,1).twigs().stream().noneMatch(DantianVeins.Twig::fork));
        assertEquals(10,DantianVeins.beads().size());
    }
    @Test void fadeIsSharedOverOneSecondWithoutRetraction() {
        for(var r:DantianVeins.routes()) {
            assertEquals(1,DantianVeins.reach(RETRACT_START-1.5,r),1e-12);
            for(double t=RETRACT_START;t<RETRACT_START+1.1;t+=.01) {
                assertEquals(1,DantianVeins.reach(t,r));
                assertEquals(1,DantianVeins.remaining(t,r));
                assertEquals(1-clamp(t-RETRACT_START),DantianVeins.alpha(t),1e-12);
            }
        }
        assertEquals(1,DantianVeins.alpha(RETRACT_START));
        assertEquals(0,DantianVeins.alpha(RETRACT_START+1),1e-12);
    }
    @Test void darknessFieldIsContinuousAcrossTheViewport() {
        for(double t:new double[]{.25,.375,.5,RETRACT_START,RETRACT_START+.5})
            for(int y=0;y<100;y++)for(int x=0;x<100;x++) {
                double a=shadeAlpha(t,x/100.0,y/100.0);
                assertTrue(Math.abs(a-shadeAlpha(t,(x+1)/100.0,y/100.0))<=.05);
                assertTrue(Math.abs(a-shadeAlpha(t,x/100.0,(y+1)/100.0))<=.05);
            }
    }
    @Test void finaleOrdersFlashSwellExplosionRoutesStampTextAndSharedFade() {
        assertEquals(.15,FLASH_SECONDS,1e-12);
        assertEquals(.3,SWELL_SECONDS,1e-12);
        assertEquals(CORE_START+SWELL_SECONDS,BURST_START,1e-12);
        assertEquals(.4,EXPLOSION_SECONDS,1e-12);
        assertEquals(BURST_START+EXPLOSION_SECONDS,MERIDIAN_START,1e-12);
        double previous=0;
        for(double t=CORE_START;t<BURST_START;t+=.001) {
            double radius=DantianVeins.coreRadius(t,1);
            assertTrue(radius>=previous);previous=radius;
        }
        assertEquals(1,flash(0));assertEquals(0,flash(CORE_START),1e-12);
        for(var route:DantianVeins.routes()) {
            assertEquals(0,DantianVeins.reach(BURST_START,route),1e-12);
            assertEquals(0,DantianVeins.reach(MERIDIAN_START,route),1e-12);
            assertEquals(1,DantianVeins.reach(TITLE_START,route),1e-12);
            assertEquals(0,DantianVeins.reach(CORE_START,route));
        }
        assertTrue(BURST_SECONDS>=.6&&BURST_SECONDS<=.8);
        assertEquals(MERIDIAN_START+10*SEGMENT_SECONDS,TITLE_START,1e-12);
        assertTrue(STAMP_SECONDS>=.10&&STAMP_SECONDS<=.15);
        assertEquals(1.6,stampScale(TITLE_START),1e-12);
        assertEquals(1,stampScale(TITLE_START+STAMP_SECONDS),1e-12);
        assertEquals(0,stampAlpha(TITLE_START));
        assertEquals(0,title(TITLE_START+STAMP_SECONDS),1e-12);
        assertEquals(.25,TEXT_FADE_SECONDS,1e-12);
        assertTrue(RETRACT_START-TITLE_FULL>=2.5);
        for(double t=CORE_START;t<RETRACT_START;t+=.01)assertTrue(DantianVeins.coreAlpha(t)>=.6);
        for(double t=TITLE_FULL;t<=RETRACT_START;t+=.01)assertEquals(1,title(t),1e-12);
        for(double t=RETRACT_START;t<=RETRACT_START+1;t+=.01) {
            assertEquals(DantianVeins.alpha(t),title(t),1e-12);
            assertEquals(title(t),darkness(t)/.65,1e-12);
        }
        assertTrue(SEED_TICKS/20.0<=148/20.0+1.5);
        assertTrue(SEED_TICKS/20.0>RETRACT_START+1);
    }
    @Test void brushTextureExistsAndHasExpectedDimensions() throws Exception {
        // Rule 05: runners may start below the repository root (e.g. in build/).
        var relativeSource=java.nio.file.Path.of("src/main/java/io/github/verycooltimo/murim/client/vfx/preset/DantianVeins.java");
        var workingDirectory=java.nio.file.Path.of("").toAbsolutePath();
        var root=workingDirectory;
        while(root!=null&&!java.nio.file.Files.isRegularFile(root.resolve(relativeSource))) {
            root=root.getParent();
        }
        assertNotNull(root,"DantianVeins.java source tree not found above "+workingDirectory);
        var path=root.resolve("src/main/resources/assets/murim/textures/gui/dantian_title.png");
        assertTrue(java.nio.file.Files.isRegularFile(path),"Missing brush texture: "+path);
        var image=javax.imageio.ImageIO.read(path.toFile());
        assertNotNull(image,"Unreadable brush texture: "+path);
        assertEquals(110,image.getWidth());assertEquals(192,image.getHeight());
        assertTrue(image.getColorModel().hasAlpha());
    }
}
