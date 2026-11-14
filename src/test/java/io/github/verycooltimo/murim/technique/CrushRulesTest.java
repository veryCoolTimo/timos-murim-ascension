package io.github.verycooltimo.murim.technique;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Contract tests for the API-independent blockout; not a substitute for contact GameTests. */
class CrushRulesTest {
    @Test void timelineAndStrikeMatchAnimation() throws Exception {
        int[] boundaries = {CrushRules.STANCE, CrushRules.VORTEX, CrushRules.CHARGE,
                CrushRules.CRACK, CrushRules.AIM, CrushRules.STRIKE, CrushRules.STUMBLE,
                CrushRules.RECOVER, CrushRules.END};
        int sum = 0;
        for (int i = 1; i < boundaries.length; i++) {
            assertTrue(boundaries[i] > boundaries[i - 1]);
            sum += boundaries[i] - boundaries[i - 1];
        }
        assertEquals(100, sum);
        try (var in = getClass().getResourceAsStream("/data/murim/murim_techniques/twenty_four_plum_crush.json")) {
            assertNotNull(in);
            var definition = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            var phases = definition.getAsJsonObject("phases");
            int phaseSum = phases.entrySet().stream().mapToInt(e -> e.getValue().getAsInt()).sum();
            assertEquals(CrushRules.END, phaseSum);
            assertEquals(CrushRules.STRIKE, phases.get("ritual").getAsInt() + phases.get("windup").getAsInt());
            assertEquals(CrushRules.COOLDOWN, definition.get("cooldown_ticks").getAsInt());
            assertEquals(CrushRules.HIT_STOP, definition.get("hit_stop_ticks").getAsInt());
            assertEquals("murim:plum_crush", definition.getAsJsonObject("behavior").get("type").getAsString());
            var requirement = definition.getAsJsonArray("requires").get(0).getAsJsonObject();
            assertEquals("murim:twenty_four_plum_sea", requirement.get("technique").getAsString());
            assertEquals(5, requirement.get("star").getAsInt());
        }
        try (var in = getClass().getResourceAsStream(
                "/assets/murim/player_animations/twenty_four_plum_crush.json")) {
            assertNotNull(in);
            JsonObject clip = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("animations")
                    .getAsJsonObject("twenty_four_plum_crush");
            assertEquals(CrushRules.END, clip.get("animation_length").getAsDouble() * 20);
            var bones = clip.getAsJsonObject("bones");
            assertEquals(Set.of("torso", "head", "right_arm", "left_arm", "right_leg", "left_leg"), bones.keySet());
            var arm = bones.getAsJsonObject("right_arm").getAsJsonObject("rotation");
            var before = arm.getAsJsonObject("2.65").getAsJsonArray("vector");
            var strike = arm.getAsJsonObject("2.7").getAsJsonArray("vector");
            assertEquals(54, CrushRules.STRIKE);
            assertTrue(before.get(0).getAsDouble() - strike.get(0).getAsDouble() > 150);
            assertEquals(strike, arm.getAsJsonObject("3.3").getAsJsonArray("vector"));
            for (int t = CrushRules.CHARGE; t <= CrushRules.AIM; t++)
                assertTrue(arm.has(String.valueOf(t / 20.0)), "Missing hum key at " + t);
            assertTrue(bones.getAsJsonObject("right_leg").getAsJsonObject("position").has("2.6"));
        }
    }

    @Test void damageAndPushMatchTheSpec() {
        assertEquals(10, CrushRules.DMG_PRIMARY);
        assertEquals(4, CrushRules.areaShare(-1));
        assertEquals(2, CrushRules.areaShare(4.5));
        assertEquals(2, CrushRules.areaShare(100));
        assertEquals(1.4, CrushRules.PUSH_PRIMARY);
        assertEquals(.8, CrushRules.PUSH_AREA);
        assertEquals(.3, CrushRules.PUSH_LIFT);
        assertEquals(1.6, CrushRules.PUSH_MAX);
        assertEquals(2.5, CrushRules.LUNGE);
        for (int tier = 0; tier <= 8; tier++)
            assertEquals(RiverRules.power(tier), CrushRules.power(tier));
    }

    @Test void qiMultiplierIsBoundedAndFreeCastersKeepNormalPower() {
        assertEquals(.8, CrushRules.qiMultiplier(0, 100, false));
        assertEquals(.8, CrushRules.qiMultiplier(-10, 100, false));
        assertEquals(.8, CrushRules.qiMultiplier(0, 0, false));
        assertEquals(1.25, CrushRules.qiMultiplier(100, 100, false));
        assertEquals(1.25, CrushRules.qiMultiplier(200, 100, false));
        assertEquals(1, CrushRules.qiMultiplier(100, 100, true));
        double previous = .8;
        for (int spent = 0; spent <= 100; spent++) {
            double multiplier = CrushRules.qiMultiplier(spent, 100, false);
            assertTrue(multiplier >= previous && multiplier <= 1.25);
            previous = multiplier;
        }
    }

    @Test void interruptionAndProjectileWindowsHaveExactBoundaries() {
        assertTrue(CrushRules.interrupts(49, 8));
        assertFalse(CrushRules.interrupts(50, 8));
        assertFalse(CrushRules.interrupts(49, 7.99));
        for (int t = 0; t <= 100; t++)
            assertEquals(t >= 50 && t <= 58, CrushRules.projectileWindow(t));
        assertEquals(14, CrushRules.RECOIL);
        assertEquals(20, CrushRules.MOB_STUN);
        assertEquals(12, CrushRules.PLAYER_STUN);
        assertEquals(10, CrushRules.BOSS_STUN);
    }
}
