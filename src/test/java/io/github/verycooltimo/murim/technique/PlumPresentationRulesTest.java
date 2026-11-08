package io.github.verycooltimo.murim.technique;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PlumPresentationRulesTest {
    @Test
    void onlyTwelveStarsSelectFullMastery() {
        for (int star = 0; star <= 11; star++) assertFalse(PlumPresentationRules.fullMastery(star));
        assertTrue(PlumPresentationRules.fullMastery(12));
    }

    @Test
    void eachWaveTakesItsRadialThirdAndNothingSurvivesTheLast() {
        for (int[] waves : new int[][]{{128, 133, 138}, RainRules.COHORTS}) {
            assertEquals(0, PlumPresentationRules.cohort(.9));
            assertEquals(1, PlumPresentationRules.cohort(.5));
            assertEquals(2, PlumPresentationRules.cohort(.1));
            assertEquals(0, PlumPresentationRules.flowerAlpha(waves[0] + RainRules.FALL, .9, waves));
            assertEquals(1, PlumPresentationRules.flowerAlpha(waves[0], .1, waves));
            for (int i = 0; i <= 100; i++) {
                assertEquals(0, PlumPresentationRules.flowerAlpha(waves[2] + RainRules.FALL, i / 100.0, waves));
                double last = 1;
                for (double t = waves[0]; t <= waves[2] + RainRules.FALL; t += .25) {
                    double alpha = PlumPresentationRules.flowerAlpha(t, i / 100.0, waves);
                    assertTrue(alpha >= 0 && alpha <= last);
                    last = alpha;
                }
            }
        }
    }

    @Test
    void pilotTwoUsesBudgetedPaintedMassAndFloorsOpeningRibbons() throws Exception {
        String rain = vfxSource("RainVfx");
        String storm = vfxSource("PlumStormVfx");
        // Pilot 2 replaces the old Rain mass; the polish uses 1000 layered Sea slots.
        assertTrue(rain.contains("RainPresentation.flowerPetals(c.executionTier)"));
        assertTrue(rain.contains("VfxBudget.rainRoom() - rainLive()"));
        assertTrue(storm.contains("case VORTEX -> PlumPolish.seaSlots();"));
        assertFalse(rain.contains("PlumVfx.depthSize("));
        assertTrue(rain.contains("MurimRenderTypes.rainPetals()"));
        assertTrue(rain.contains("material[i] = Math.max(.04, w[i]);"));
        // The only raw strip call must be inside the half-width-floor helper.
        assertEquals(1, rain.split("PlumVfx\\.stripVar\\(", -1).length - 1);
        assertFalse(rain.contains("PlumVfx.strip("));
        assertFalse(storm.contains("s.flow == Flow.VORTEX && j > 1"));
        assertTrue(storm.contains("} else if (s.flow == Flow.VORTEX) {"));
        String collision = storm.substring(storm.indexOf("private static void collideLoosePetals("),
                storm.indexOf("private static Vec3 cutPoint("));
        assertFalse(collision.contains("Flow.VORTEX"), "Sea petals must retain build circulation");
    }

    @Test
    void fullBloomKeepsItsMassAndUsesTheLiveSwordInsteadOfRemoteBranches() throws Exception {
        String storm = vfxSource("PlumStormVfx");
        assertTrue(storm.contains("case BLOOM -> 700;"));
        assertFalse(storm.contains("treePoint("));
        assertFalse(storm.contains("bloomBranches("));
        String frame = storm.substring(storm.indexOf("private static void followBloomBlade("),
                storm.indexOf("private record Emission"));
        assertTrue(frame.contains("BoneAnchorLayer.Bone.BLADE_BASE"));
        assertTrue(frame.contains("BoneAnchorLayer.Bone.BLADE_TIP"));
        assertTrue(storm.contains("followBloomBlade(s);"));
        assertTrue(storm.contains("i % 32 == 0 || s.flow == Flow.BLOOM && i % 5 == 0"));
        assertTrue(storm.contains("if (s.flow != Flow.RIVER && s.flow != Flow.BLOOM) at = avoidStrikes"),
                "attached blossoms must not be pushed away from their bone anchors");
    }

    private static String vfxSource(String name) throws Exception {
        Path relative = Path.of("src/main/java/io/github/verycooltimo/murim/client/vfx/" + name + ".java");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(relative))) root = root.getParent();
        assertNotNull(root, "VFX source tree not found");
        return Files.readString(root.resolve(relative));
    }

    @Test
    void scatterOpeningCannotSendClientMovement() throws Exception {
        // A fake-server-player position assertion misses this regression: sendDash is a client packet.
        // Follow DialogueCaptureGateTest: runners may start below the repository root.
        Path relativeSource = Path.of("src/main/java/io/github/verycooltimo/murim/technique/ScatterExecutor.java");
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path root = workingDirectory;
        while (root != null && !Files.isRegularFile(root.resolve(relativeSource))) {
            root = root.getParent();
        }
        assertNotNull(root, "ScatterExecutor.java source tree not found above " + workingDirectory);
        String source = Files.readString(root.resolve(relativeSource));
        String opening = source.substring(source.indexOf("public static boolean start("), source.indexOf("public static void tick("));
        assertFalse(opening.contains("sendDash("));
        assertFalse(opening.contains("setDeltaMovement("));
        assertFalse(opening.contains("teleportTo("));
        assertFalse(opening.contains("moveTo("));
        assertTrue(source.substring(source.indexOf("public static void tick(")).contains("sendDash("),
                "the later final pass must keep its original movement");
    }
}
