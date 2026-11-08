package io.github.verycooltimo.murim.client.vfx.preset;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.*;

class DantianFlowMathTest {
    @Test void radiusTracksGameAndBodyScale() {
        assertEquals(.03, radius(0,1),1e-12);
        assertEquals(.48, radius(1,1),1e-12);
        assertEquals(.51, radius(.5,2),1e-12);
    }
    @Test void smoothingIsIndependentOfStepSizeAndNeverOvershoots() {
        double stepped=.48;
        for(int i=0;i<5;i++)stepped=smoothRadius(stepped,.03,.05);
        assertEquals(smoothRadius(.48,.03,.25),stepped,1e-12);
        assertEquals(.03+.45/Math.E,stepped,1e-12);
        assertTrue(stepped>.03&&stepped<.48);
        assertEquals(.48,smoothRadius(.48,.03,0),1e-12);
    }
    @Test void secondRoundAcceleratesAsItTightensWithACap() {
        assertEquals(1,speed(1,.04));
        assertEquals(1.15,speed(2,.4),1e-12);
        assertTrue(speed(2,.1)>speed(2,.4));
        assertEquals(3.2,speed(2,0),1e-12);
    }
    @Test void murkRequiresSecondRoundAndAnUnbrokenInBandInterval() {
        assertFalse(peel(1,true,3));
        assertFalse(peel(2,false,3));
        assertFalse(peel(2,true,1.49));
        assertTrue(peel(2,true,1.5));
        double cadence=0;int count=0;
        for(int i=0;i<90;i++) {
            boolean band=i<20||i>=40;
            cadence=nextCadence(2,band,cadence,.05);
            if(peel(2,band,cadence)){count++;cadence=0;}
        }
        assertEquals(1,count);
        assertEquals(0,nextCadence(1,true,1,.05));
    }
    @Test void strainPushesOnlyTheHeadAndRecoversToBlue() {
        assertEquals(0,strain(0,true));
        assertEquals(.65,strain(0,false),1e-12);
        assertEquals(1,strain(2,false));
        assertEquals(0,push(1,0));
        assertEquals(.30,push(1,1),1e-12);
        assertEquals(.15,push(.5,1),1e-12);
        assertEquals(0,push(strain(0,true),1));
    }
    @Test void missReplacesOnlyLeadingHalfAndPushScalesWithRing() {
        for(double along:new double[]{0,.25,.5})assertEquals(0,missTint(1,along));
        assertEquals(1,missTint(1,1));
        assertEquals(.65,missTint(strain(0,false),.75),1e-12);
        assertEquals(0,missTint(0,1));
        for(double radius:new double[]{.075,.2,.48}) {
            assertEquals(radius*.30,radius*push(1,1),1e-12);
            assertEquals(0,radius*push(1,0));
        }
        assertEquals(Math.exp(-3),recoverStrain(1,0,1.5),1e-12);
        double stepped=1;
        for(int i=0;i<30;i++)stepped=recoverStrain(stepped,0,.05);
        assertEquals(recoverStrain(1,0,1.5),stepped,1e-12);
    }
    @Test void spiralKeepsTurnsAndNarrowsAtSmallRadius() {
        for(double radius:new double[]{.45,.2,.075}) {
            assertEquals(1.75,(spiralAngle(1)-spiralAngle(0))/(2*Math.PI),1e-12);
            assertEquals(radius,spiralRadius(radius,0),1e-12);
            assertEquals(radius*.35,spiralRadius(radius,1),1e-12);
            assertTrue(spiralRadius(radius,.25)>spiralRadius(radius,.75));
            assertTrue(ribbonWidth(radius)>=.015);
        }
        assertEquals(.015,ribbonWidth(.01),1e-12);
        assertTrue(ribbonWidth(.075)<ribbonWidth(.45));
    }
    @Test void seedHasOnePulseAndEmberUntilEnd() {
        assertEquals(176,SEED_TICKS);
        assertEquals(1,coil(0));assertEquals(0,coil(8));
        assertEquals(1.35,pulse(20),1e-12);
        assertTrue(pulse(19)<pulse(20)&&pulse(21)<pulse(20));
        for(int tick=0;tick<SEED_TICKS;tick++)assertEquals(1,ember(tick));
        assertEquals(0,ember(SEED_TICKS));assertEquals(0,ember(-1));
    }
    @Test void airConvergesBeforeBellyUnfurls() {
        assertEquals(1,startReach(0));
        assertEquals(.5,startReach(3));
        assertEquals(0,startReach(6));
        assertEquals(0,unfurl(6));
        assertEquals(.5,unfurl(6.6),1e-12);
        assertEquals(1,unfurl(7.2),1e-12);
        for(double t=0;t<6;t+=.1) assertTrue(startReach(t+.1)<=startReach(t));
    }
    @Test void bodyFloorsHoldUntilLastTwoSeconds() {
        assertEquals(.34,floorRadius(.1,1,100,1));
        assertEquals(.68,floorRadius(.1,1,100,2));
        assertEquals(.26,floorRadius(.1,2,8,1));
        assertEquals(.13,floorRadius(.1,2,9,1));
        assertEquals(.1,floorRadius(.1,2,10,1));
        assertEquals(.45,floorRadius(.45,1,0,1));
        for(double u=0;u<=1;u+=.05) assertTrue(readableSpiral(.26,u,7,1)>=.26);
        assertTrue(readableSpiral(.26,0,7,1)>readableSpiral(.26,1,7,1));
    }
    @Test void wavesHaveScaledAmplitudeAndTravelAlongRing() {
        for(double t=0;t<10;t+=.1)for(double a=0;a<7;a+=.1)
            assertTrue(Math.abs(wave(a,t,2))<=.12);
        assertEquals(.06,wave(Math.PI/6,0,1),1e-12);
        assertEquals(wave(.7,1,1),wave(.7+.8,2,1),1e-12);
        assertNotEquals(wave(.7,0,1),wave(.7,1,1));
    }
    @Test void finaleHasImpactInnerViewAndRelease() {
        assertEquals(1,impact(0));assertEquals(0,impact(.4));
        assertEquals(0,darkness(.25));assertEquals(.65,darkness(.5));
        assertEquals(.65,darkness(RETRACT_START));
        assertEquals(0,darkness(RETRACT_START+RETRACT_SECONDS));
        assertEquals(1,release(RETRACT_START+RETRACT_SECONDS));
        assertEquals(0,sharedFade(RETRACT_START+RETRACT_SECONDS));
        assertEquals(0,sharedFade(RETRACT_START+RETRACT_SECONDS+.1));
        assertEquals(0,title(TITLE_START));assertEquals(1,title(TITLE_FULL),1e-12);
        assertEquals(0,title(RETRACT_START+RETRACT_SECONDS),1e-12);
        assertEquals(0,release(RETRACT_START));
        assertEquals(.5,release(RETRACT_START+.5),1e-12);
        assertEquals(1,release(RETRACT_START+1),1e-12);
    }

    @Test void cinematicShadeRisesAtFlashAndHoldsUntilSharedFade() {
        assertEquals(0,shadeAlpha(-.1,0,0));
        assertEquals(0,shadeAlpha(0,0,0));
        assertEquals(.375,shadeAlpha(.1,0,0),1e-12);
        for(double t=.2;t<=RETRACT_START;t+=.01) {
            assertEquals(.75,shadeAlpha(t,0,0),1e-12);
            assertEquals(shadeAlpha(t,0,0),shadeAlpha(t,1,1),1e-12);
        }
        assertEquals(.375,shadeAlpha(RETRACT_START+.5,0,0),1e-12);
        assertEquals(0,shadeAlpha(RETRACT_START+RETRACT_SECONDS,0,0));
    }

    @Test void movedDimmerMatchesRoundElevenAtEveryQuarterTick() {
        // Frozen round-11 smoothstep rise (0.2s), 0.75 hold, then shared 1s release.
        for (int step = -80; step <= 800; step++) {
            double seconds = step / 80.0;
            double rise = Math.max(0, Math.min(1, seconds / .2));
            double fade = 1 - Math.max(0, Math.min(1, (seconds - 7.22) / 1.0));
            double expected = .75 * rise * rise * (3 - 2 * rise) * fade;
            assertEquals(expected, shadeAlpha(seconds, 0, 0), 1e-12, "seconds=" + seconds);
        }
    }

    @Test void seedHudHasNoViewportDimmerAndWorldDimmerPrecedesGlow() throws Exception {
        var relative = java.nio.file.Path.of("src/main/java/io/github/verycooltimo/murim/client");
        var root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.exists(root.resolve(relative))) root = root.getParent();
        assertNotNull(root, "Client source tree not found");
        String hud = java.nio.file.Files.readString(root.resolve(relative).resolve("MeditationHud.java"));
        String seed = hud.substring(hud.indexOf("private static void seedScene("), hud.indexOf("private static void seedText("));
        assertFalse(seed.contains("shadeAlpha"));
        assertFalse(seed.replaceAll("\\s+", "").contains("graphics.fill(0,0,w,h,"));
        assertTrue(seed.contains("BtImpactFrame.renderCreation"));
        String world = java.nio.file.Files.readString(root.resolve(relative).resolve("vfx/MeditationVfxRenderer.java"));
        int dimmer = world.indexOf("if (sceneAge >= 0) drawCreationDimmer((sceneAge + partial) / 20.0)");
        assertTrue(dimmer >= 0);
        assertTrue(dimmer < world.indexOf("BtGlow.prepare"));
        assertTrue(world.indexOf("BtGlow.prepare") < world.indexOf("creationFlow().render(ctx)"));
    }

    @Test void legacySegmentMathKeepsItsGrowthAndReleaseOrder() {
        for(int ch=0;ch<5;ch++)for(int seg=0;seg<(ch==0?4:3);seg++) {
            int order=(ch==0?0:ch<3?4:7)+seg;
            double start=MERIDIAN_START+order*SEGMENT_SECONDS;
            assertEquals(0,meridian(start,ch,seg),1e-12);
            assertEquals(.5,meridian(start+SEGMENT_SECONDS/2,ch,seg),1e-12);
            assertEquals(1,meridian(start+SEGMENT_SECONDS,ch,seg),1e-12);
            assertEquals(1,meridianRelease(RETRACT_START,ch,seg));
            assertEquals(0,meridianRelease(RETRACT_START+RETRACT_SECONDS,ch,seg),1e-12);
        }
        assertEquals(.5,meridianRelease(RETRACT_START+.95*RETRACT_SECONDS,0,0),1e-12);
    }

    @Test void coreBreathesEveryOnePointTwoSecondsAfterFormationPulse() {
        assertEquals(coreGlow(1),coreGlow(2.2),1e-12);
        assertTrue(coreGlow(1)>coreGlow(1.6));
        assertEquals(1.35,pulse(20),1e-12);
    }
    @Test void backFlowKeepsOcclusionAndOnlySilhouetteCrossingsHaveAFaintRim() {
        assertTrue(flowOccluded(-.3));
        assertFalse(flowOccluded(0));assertFalse(flowOccluded(.3));
        assertEquals(.15,edgeRim(-.3,0,1),1e-12);
        assertEquals(0,edgeRim(-.3,.08,1));
        assertEquals(0,edgeRim(.3,0,1));
        assertEquals(edgeRim(-.3,.02,1),edgeRim(-.6,.04,2),1e-12);
    }
}
