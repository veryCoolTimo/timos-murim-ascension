package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.technique.RainRules;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RainPresentationTest {
    @Test void centreIsAboveVictimAndAwayFromCaster() {
        Vec3 feet = new Vec3(12, 64, -7);
        for (Vec3 caster : new Vec3[]{new Vec3(0, 64, -7), new Vec3(20, 64, 9), feet}) {
            Vec3 centre = RainPresentation.centre(feet, caster);
            assertEquals(feet.y + 15, centre.y, 1e-9);
            double dx = centre.x - feet.x, dz = centre.z - feet.z;
            assertTrue(Math.hypot(dx, dz) <= .5);
            assertTrue(dx * (feet.x - caster.x) + dz * (feet.z - caster.z) >= 0);
        }
    }

    @Test void wholeFlowerStaysFourBlocksClearAtEveryTier() {
        // Old tilted cup: maximum lobe radius, breathing, depth and small particle extent.
        for (int tier = 0; tier <= 12; tier++) {
            assertTrue(RainPresentation.lowest(tier) >= 4, "tier " + tier);
        }
    }

    @Test void lancesGrowMonotonicallyAndZeroHasNoEffects() {
        int previous = 0;
        assertEquals(0, RainPresentation.lanceCount(0));
        assertEquals(0, RainPresentation.flowerPetals(0));
        for (int tier = 0; tier <= 12; tier++) {
            int count = RainPresentation.lanceCount(tier);
            assertTrue(count >= previous);
            previous = count;
        }
        assertEquals(80, RainPresentation.lanceCount(8));
    }

    @Test void cloudAndStrokeCountsFollowPresentationDensity() {
        int[] flowers = {0, 0, 0, 288, 432, 576, 768, 960, 1200};
        int[][] beams = {{0,0,0}, {0,0,0}, {0,0,0}, {15,18,21}, {22,26,31},
                {29,35,41}, {39,47,54}, {48,58,68}, {60,72,84}};
        int[] columns = {0, 0, 0, 0, 0, 0, 21, 26, 33};
        for (int tier = 0; tier <= 12; tier++) {
            int row = Math.min(8, tier);
            assertEquals(flowers[row], RainPresentation.flowerPetals(tier));
            for (int wave = 0; wave < 3; wave++) {
                int count = RainPresentation.beamCount(tier, wave);
                assertEquals(beams[row][wave], count);
                if (tier > 0) assertTrue(count >= RainPresentation.beamCount(tier - 1, wave));
            }
            assertEquals(columns[row], RainPresentation.columnCount(tier));
        }
    }

    @Test void ribbonsRespectTheExistingTierLadder() {
        assertEquals(0, RainPresentation.streamCount(0));
        assertEquals(0, RainPresentation.streamCount(4));
        for (int tier = 5; tier <= 12; tier++) {
            assertTrue(RainPresentation.streamCount(tier) >= 3);
            assertTrue(RainPresentation.streamCount(tier) <= 5);
            assertEquals(RainPresentation.streamCount(tier) * 24, RainPresentation.streamPetals(tier));
        }
    }

    @Test void everyBodyEndpointAndApproachClearsTheVictimEye() {
        Vec3 feet = new Vec3(12, 64, -7);
        for (double height : new double[]{1.5, 1.8, 2.9}) {
            Vec3 eye = feet.add(0, height * .9, 0);
            for (double width : new double[]{.4, .6, 1.4}) for (int i = 0; i < 360; i++) {
                double angle = Math.toRadians(i);
                Vec3 end = RainPresentation.bodyEnd(feet, eye, width, height, angle);
                assertTrue(RainPresentation.eyeSafe(end, eye));
                assertTrue(Math.abs(Math.max(Math.abs(end.x - feet.x), Math.abs(end.z - feet.z)) - width / 2) < 1e-8);
                for (double slant : new double[]{10, 17.5, 25}) {
                    double h = feet.y + 15 - end.y;
                    Vec3 from = end.add(Math.cos(angle) * h * Math.tan(Math.toRadians(slant)), h,
                            Math.sin(angle) * h * Math.tan(Math.toRadians(slant)));
                    Vec3 approach = end.add(Math.cos(angle) * .75, .2, Math.sin(angle) * .75);
                    for (int step = 0; step <= 100; step++)
                        assertTrue(RainPresentation.eyeSafe(RainPresentation.lancePoint(from, approach, end, step / 100.0), eye));
                }
            }
        }
    }

    @Test void groundEndpointsStayInRainRadiusAndClearTheEye() {
        Vec3 feet = new Vec3(12, 64, -7), eye = feet.add(0, 1.62, 0);
        for (int i = 0; i < 360; i++) for (double r : new double[]{0, .5, 1}) {
            double angle = Math.toRadians(i), radius = RainRules.RAIN_RADIUS * Math.sqrt(r);
            Vec3 end = feet.add(Math.cos(angle) * radius, -.12, Math.sin(angle) * radius);
            assertTrue(RainPresentation.eyeSafe(end, eye));
            assertTrue(Math.hypot(end.x - feet.x, end.z - feet.z) <= RainRules.RAIN_RADIUS + 1e-9);
        }
    }

    @Test void inactivePitchIsPositiveZeroForEitherDirection() {
        for (double tick : new double[]{RainRules.BLOOM - 1, RainRules.BLOOM,
                RainPresentation.rainEnd() + 10, RainPresentation.rainEnd() + 11}) {
            for (double desired : new double[]{-1000, -100, -0.0, 0, 100, 1000}) {
                assertEquals(0.0f, RainPresentation.headPitch(tick, desired),
                        "tick " + tick + ", desired " + desired);
            }
        }
    }

    @Test void pitchEasesInClampsAndReturnsOverTenTicks() {
        assertEquals(0, RainPresentation.headPitch(RainRules.BLOOM, -100));
        double previous = 0;
        for (double t = RainRules.BLOOM; t <= RainRules.BLOOM + 8; t += .25) {
            float pitch = RainPresentation.headPitch(t, -1000);
            assertTrue(pitch <= 0);
            double magnitude = Math.abs(pitch);
            assertTrue(magnitude >= previous && magnitude <= 90);
            previous = magnitude;
        }
        assertEquals(90, previous);
        previous = 90;
        for (double t = RainPresentation.rainEnd(); t <= RainPresentation.rainEnd() + 10; t += .25) {
            float pitch = RainPresentation.headPitch(t, -1000);
            assertTrue(pitch <= 0);
            double magnitude = Math.abs(pitch);
            assertTrue(magnitude <= previous && magnitude >= 0);
            previous = magnitude;
        }
        assertEquals(0, previous);
        assertEquals(90, RainPresentation.headPitch(RainRules.BLOOM + 8, 1000));
    }

    @Test void skyStartsAtNineStarsAndNeverExceedsQuarterStrength() {
        for (int stars = 0; stars <= 12; stars++) for (double t = 0; t <= 240; t += .5) {
            double strength = RainPresentation.skyStrength(stars, t);
            assertTrue(strength >= 0 && strength <= .25);
            if (stars < 9) assertEquals(0, strength);
        }
        assertEquals(.12, RainPresentation.skyStrength(9, RainRules.BLOOM + 10), 1e-9);
        assertEquals(.25, RainPresentation.skyStrength(12, RainRules.BLOOM + 10), 1e-9);
        assertEquals(0, RainPresentation.skyStrength(12, RainPresentation.rainEnd() + 20));
    }
}
