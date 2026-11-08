package io.github.verycooltimo.murim.client.vfx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.verycooltimo.murim.client.vfx.PlumLayers.*;

class PlumLadderTest {
    private static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("src/main/java"))) p = p.getParent();
        assertNotNull(p, "Cannot find repository source tree");
        return p;
    }
    private static String source(String relative) throws Exception {
        return Files.readString(root().resolve("src/main/java/io/github/verycooltimo/murim/" + relative));
    }
    @Test void exactStarsGateEveryFormAndRemainCumulative() {
        for (Form form : Form.values()) {
            var previous = EnumSet.noneOf(Kind.class);
            for (int stars = 0; stars <= 12; stars++) {
                var current = kinds(form, stars);
                assertTrue(current.containsAll(previous), form + " star " + stars);
                if (stars <= 4) assertTrue(current.isEmpty());
                if (stars < 9) assertFalse(current.contains(Kind.PETALS));
                if (stars < 10) for (Kind k : new Kind[]{Kind.FLOWER, Kind.ILLUSION, Kind.BRANCH, Kind.CLONES})
                    assertFalse(current.contains(k), form + " star " + stars + " " + k);
                if (stars < 11) assertFalse(current.contains(Kind.THREADS));
                if (stars < 12) assertFalse(current.contains(Kind.FULL));
                previous.addAll(current);
            }
        }
        assertTrue(has(Form.RAIN, 11, Kind.THREADS));
        assertTrue(has(Form.BLOOM, 12, Kind.FULL));
        assertFalse(has(Form.RAIN, 10, Kind.COLUMN));
        assertFalse(has(Form.RIVER, 11, Kind.WEDGE));
    }
    @Test void eachLayerStartsAtHalfDensityAndFillsAtTwelve() {
        for (Kind kind : Kind.values()) {
            int first = firstStar(kind);
            assertEquals(0, density(first - 1, kind));
            assertEquals(first == 12 ? 1 : .5, density(first, kind));
            double previous = 0;
            for (int stars = 0; stars <= 12; stars++) {
                assertTrue(density(stars, kind) >= previous);
                previous = density(stars, kind);
            }
            assertEquals(1, previous);
        }
        for (Form form : Form.values()) {
            assertEquals(0, petalCount(form, 0, 1000));
            assertEquals(0, petalCount(form, 8, 1000));
            assertEquals(1000, petalCount(form, 12, 1000));
            for (int stars = 1; stars < 10; stars++) assertEquals(1, scale(form, stars));
        }
    }
    @Test void lightIsLocalBeforeVolumeAndSkyWaitsForCompletion() {
        for (int stars = 0; stars <= 12; stars++) {
            if (stars < 8) assertEquals(0, light(stars));
            if (stars == 8 || stars == 9) assertTrue(light(stars) > 0 && light(stars) <= .2F);
            assertEquals(stars == 12 ? 1 : 0, skyTint(stars));
            assertEquals(15, rainFlowerHeight(stars));
        }
        assertEquals(1, light(12));
    }
    @Test void allStarsDispatchWithoutTheLegacyBoundary() throws Exception {
        String bridge = source("network/ClientPayloadBridge.java");
        assertFalse(bridge.contains("fullMastery()"));
        for (int stars = 1; stars <= 12; stars++) {
            for (String form : new String[]{"Rain", "River", "Scatter", "Sea", "FullBloom"}) {
                String method = form.equals("FullBloom") ? "accept" : "on" + form;
                assertTrue(bridge.contains("vfx." + form + "Vfx." + method + "(payload);"), "star " + stars);
                assertFalse(Files.exists(root().resolve("src/main/java/io/github/verycooltimo/murim/client/vfx/Classic" + form + "Vfx.java")));
            }
        }
        for (String form : new String[]{"Rain", "River", "Scatter", "Sea"})
            assertTrue(bridge.contains("vfx." + form + "Vfx.onTechniqueEvent(payload);"));
        assertTrue(source("client/vfx/RainVfx.java").contains("RainPresentation.centre(c.target, caster)"));
        assertTrue(source("client/vfx/PlumStormVfx.java").contains("PlumLayers.petalSlot(s.form, s.stars, i, s.count)"));
    }
    @Test void rainRestoresOldFlowerStreaksAndImpactWithoutPaintedBlossom() throws Exception {
        String rain = source("client/vfx/RainVfx.java");
        for (String helper : new String[]{"flowerBasis", "lobePoint", "coreRibbons", "rain", "impact",
                "fall", "splashLanding", "drift", "lobeTint"})
            assertTrue(rain.contains(" " + helper + "("), helper);
        assertTrue(rain.contains("Math.toRadians(40.0D)"));
        assertTrue(rain.contains("RainPresentation.beamCount(c.executionTier, k)"));
        assertTrue(rain.contains("RainPresentation.columnCount(c.executionTier)"));
        assertTrue(rain.contains("TechniqueCaption.impact(k == 2 ? 1.0F : 0.5F)"));
        // Neither opening nor bloom motes should regain the old ground-height fade.
        assertFalse(rain.contains("c.t() + partial >= 125"));
        assertFalse(rain.contains("t >= 125"));
        assertFalse(rain.contains("3 / 1.08 * c.scale, 8"));
        assertFalse(rain.contains("RainPresentation.lobeCell"));
        assertEquals(0, RainPresentation.flowerPetals(0));
        assertEquals(0, RainPresentation.columnCount(0));
        for (int wave = 0; wave < 3; wave++) assertEquals(0, RainPresentation.beamCount(0, wave));
    }

    @Test void swordPathsUseStraightCentreLines() throws Exception {
        String storm = source("client/vfx/PlumStormVfx.java");
        String cut = storm.substring(storm.indexOf("private static Vec3 cutPoint"), storm.indexOf("/** rain-09"));
        assertFalse(cut.contains("Math.sin"));
        assertFalse(cut.contains("Math.cos"));
        String stroke = storm.substring(storm.indexOf("private static Vec3 strokePoint"), storm.indexOf("/** Sample each"));
        assertFalse(stroke.contains("Math.sin"));
        assertFalse(stroke.contains("Math.cos"));
        assertFalse(storm.contains("Vec3[] arc"));
        assertFalse(source("client/vfx/RainVfx.java").contains("double[] qw = new double[cn"));
    }
    @Test void seaWaveHasIndependentSpeedsAndDelays() {
        double sum = 0, square = 0;
        int min = 6, max = 0, n = 2600;
        for (int i = 0; i < n; i++) {
            double speed = SeaPetalMotion.speed(i);
            sum += speed; square += speed * speed;
            min = Math.min(min, SeaPetalMotion.delay(i));
            max = Math.max(max, SeaPetalMotion.delay(i));
            assertTrue(Math.abs(speed - 1) >= .3 && Math.abs(speed - 1) <= .5);
            assertTrue(SeaPetalMotion.period(i) >= 8 && SeaPetalMotion.period(i) <= 16);
            assertEquals(0, SeaPetalMotion.clock(i, 0, 0));
        }
        double mean = sum / n;
        assertTrue(Math.sqrt(square / n - mean * mean) / mean >= .3);
        assertTrue(max - min >= 3);
    }
    @Test void lateSeaSweepHasAFrontAndLaggingPetals() {
        int n = 2600, lagging = 0;
        double sum = 0, square = 0, minDelay = 8, maxDelay = 0;
        for (int i = 0; i < n; i++) {
            double origin = -7.2 + 14.4 * i / (n - 1.0);
            double delay = SeaPetalMotion.sweepDelay(origin);
            minDelay = Math.min(minDelay, delay);
            maxDelay = Math.max(maxDelay, delay);
            assertTrue(SeaPetalMotion.sweepSpeed(i) >= .6 && SeaPetalMotion.sweepSpeed(i) <= 1.4);
            assertEquals(0, SeaPetalMotion.sweepTravel(i, 52 + delay, origin), 1e-12);
            if (i > 0) assertTrue(delay >= SeaPetalMotion.sweepDelay(origin - 14.4 / (n - 1)));
            // Finite differences of the same trajectory used by the renderer, after front passage.
            double speed = SeaPetalMotion.sweepTravel(i, 72.5, origin)
                    - SeaPetalMotion.sweepTravel(i, 71.5, origin);
            sum += speed;
            square += speed * speed;
            double unpeeled = (72 - 52 - delay) * SeaPetalMotion.sweepSpeed(i);
            if (SeaPetalMotion.sweepTravel(i, 72, origin) < unpeeled * .7) lagging++;
            for (double age = 52; age <= 90; age += .5) {
                var drift = SeaPetalMotion.sweepDrift(i, age, origin);
                assertTrue(Math.hypot(drift.side(), drift.up()) <= SeaPetalMotion.driftRadius(i) + 1e-9);
            }
        }
        double mean = sum / n;
        assertTrue(Math.sqrt(square / n - mean * mean) / mean >= .35);
        assertTrue(maxDelay - minDelay >= 6);
        assertTrue(lagging >= n * .2 && lagging <= n * .3);
    }
    @Test void riverWhiteBurstGrowsToTheUnchangedApex() throws Exception {
        assertEquals(.55, riverBurstScale(10));
        assertEquals(.8, riverBurstScale(11));
        assertEquals(1, riverBurstScale(12));
        String storm = source("client/vfx/PlumStormVfx.java");
        String burst = storm.substring(storm.indexOf("double burstScale ="),
                storm.indexOf("} else if (s.node", storm.indexOf("double burstScale =")));
        assertTrue(burst.contains("alphaScale *= (float) burstScale"));
        assertTrue(burst.contains("double size = burstScale"));
        assertFalse(burst.contains("* s.scale"));
    }
}
