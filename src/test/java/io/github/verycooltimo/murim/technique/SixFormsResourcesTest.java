package io.github.verycooltimo.murim.technique;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Read resources directly: no registry bootstrap required. */
class SixFormsResourcesTest {
    private static JsonObject json(String path) throws Exception {
        try (var stream = SixFormsResourcesTest.class.getResourceAsStream("/" + path)) {
            assertNotNull(stream, path);
            return JsonParser.parseReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    @Test void clipsMatchDataAndBothHitSchedules() throws Exception {
        Set<String> bones = Set.of("torso", "head", "right_arm", "left_arm", "right_leg", "left_leg");
        int[] total = {32, 46, 30, 46, 68};
        for (int i = 0; i < 5; i++) {
            String name = "six_harmonies_" + SixRules.FORMS[i];
            JsonObject d = json("data/murim/murim_techniques/" + name + ".json");
            TechniqueDefinition parsed = TechniqueDefinition.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, d).getOrThrow();
            assertEquals(total[i], parsed.totalTicks());
            assertTrue(parsed.behavior() instanceof TechniqueBehavior.SixForm);
            JsonObject phases = d.getAsJsonObject("phases");
            assertEquals(total[i], phases.entrySet().stream().mapToInt(e -> e.getValue().getAsInt()).sum());
            assertEquals(SixRules.WINDUP[i], phases.get("windup").getAsInt());
            assertEquals(SixRules.COOLDOWN[i], d.get("cooldown_ticks").getAsInt());
            assertEquals(SixRules.UNLOCK[i], d.getAsJsonArray("requires").get(0).getAsJsonObject().get("star").getAsInt());
            assertEquals("murim:six_harmonies", d.getAsJsonArray("requires").get(0).getAsJsonObject().get("technique").getAsString());
            assertEquals("murim:six_form", d.getAsJsonObject("behavior").get("type").getAsString());
            assertEquals("basic", d.get("tier").getAsString());
            assertEquals(i != 4, d.getAsJsonObject("interruption").get("break_on_damage").getAsBoolean());
            JsonObject clip = json("assets/murim/player_animations/" + name + ".json").getAsJsonObject("animations").getAsJsonObject(name);
            assertEquals((i == 3 ? SixRules.animationTick(i, 4, total[i], -1) : i == 4 ? 58 : total[i]) / 20D,
                    clip.get("animation_length").getAsDouble(), 1E-9);
            assertEquals(bones, clip.getAsJsonObject("bones").keySet());
            JsonObject rotation = clip.getAsJsonObject("bones").getAsJsonObject("right_arm").getAsJsonObject("rotation");
            // SixFormAnimations plays the authored clip on animationTick's remapped clock.
            for (int tier = 1; tier <= 4; tier++) {
                if (i == 1) {
                    for (int since : SixRules.chain(tier)) {
                        assertSnap(rotation, name, i, tier, SixRules.WINDUP[i] + since, -1);
                    }
                } else if (i == 4) {
                    for (int trigger = 0; trigger < SixRules.COUNTER_WINDOW; trigger++) {
                        int[] answers = tier < 4 ? new int[]{SixRules.COUNTER_VERTICAL}
                                : new int[]{SixRules.COUNTER_VERTICAL, SixRules.COUNTER_SLASH};
                        for (int since : answers) {
                            assertSnap(rotation, name, i, tier, trigger + since, trigger);
                        }
                    }
                } else {
                    assertSnap(rotation, name, i, tier, SixRules.WINDUP[i], -1);
                }
            }
        }
    }
    private static void assertSnap(JsonObject rotation, String name, int form, int tier,
                                   int serverTick, int trigger) {
        double clipTick = SixRules.animationTick(form, tier, serverTick, trigger);
        assertTrue(rotation.entrySet().stream().anyMatch(e ->
                Math.abs(Double.parseDouble(e.getKey()) - clipTick / 20D) < 1E-9
                        && e.getValue().getAsJsonObject().get("easing").getAsString().equals("linear")),
                name + " missing snap at clip tick " + clipTick + " (tier " + tier
                        + ", server tick " + serverTick + ", trigger " + trigger + ")");
    }

    @Test void counterPosesMoveAcrossTheHoldUntilTheDamageTick() throws Exception {
        int form = 4;
        String name = "six_harmonies_" + SixRules.FORMS[form];
        JsonObject rotation = json("assets/murim/player_animations/" + name + ".json")
                .getAsJsonObject("animations").getAsJsonObject(name).getAsJsonObject("bones")
                .getAsJsonObject("right_arm").getAsJsonObject("rotation");
        // A valid held apex is insufficient: the old clips swung forward BEFORE reaching it.
        double preparationStart = .65;
        double apex = 1.1;
        for (var key : rotation.entrySet()) {
            double time = Double.parseDouble(key.getKey());
            if (time > preparationStart && time <= apex)
                assertTrue(key.getValue().getAsJsonObject().getAsJsonArray("vector").get(0).getAsDouble() <= -97,
                        name + " premature forward swing at " + time);
        }
        for (int tier = 1; tier <= 4; tier++) for (int trigger = 0; trigger < SixRules.COUNTER_WINDOW; trigger++) {
            int offset = trigger;
            int start = offset + (tier == 4 ? 0 : SixRules.COUNTER_ANSWER + 4);
            int strike = offset + SixRules.COUNTER_VERTICAL - 2;
            int hit = offset + SixRules.COUNTER_VERTICAL;
            double previous = armPitch(rotation, form, tier, start, trigger);
            assertEquals(-150, previous, 1E-4);
            for (int tick = start + 1; tick <= strike; tick++) {
                double pitch = armPitch(rotation, form, tier, tick, trigger);
                assertTrue(pitch < previous, name + " must wind up throughout hold");
                previous = pitch;
            }
            assertEquals(-165.1, previous, 1E-4);
            assertEquals(2, hit - strike);
            assertTrue(armPitch(rotation, form, tier, hit - 1, trigger) < -150,
                    name + " blade fell before contact");
            assertEquals(-27.1, armPitch(rotation, form, tier, hit, trigger), 1E-4,
                    name + " contact must share the downswing key");
        }
    }
    @Test void fearWindupHoldsOverheadThenCutsDirectlyToContact() throws Exception {
        JsonObject bones = json("assets/murim/player_animations/six_harmonies_fear.json")
                .getAsJsonObject("animations").getAsJsonObject("six_harmonies_fear").getAsJsonObject("bones");
        JsonObject arm = bones.getAsJsonObject("right_arm");
        var windup = arm.getAsJsonObject("rotation").getAsJsonObject("0.7").getAsJsonArray("vector");
        // Pitch past vertical draws the blade behind; yaw/roll raise the elbow to the cut's other side.
        assertEquals(-185, windup.get(0).getAsDouble(), 1E-4);
        assertTrue(windup.get(1).getAsDouble() < 0);
        assertTrue(windup.get(2).getAsDouble() > 0);
        assertTrue(arm.getAsJsonObject("position").getAsJsonObject("0.7")
                .getAsJsonArray("vector").get(2).getAsDouble() > 0);
        assertEquals(18, bones.getAsJsonObject("torso").getAsJsonObject("rotation").getAsJsonObject("0.7")
                .getAsJsonArray("vector").get(1).getAsDouble(), 1E-4);
    }

    @Test void masterFearHoldsTheCockedBackWindupForAtLeastTenTicks() throws Exception {
        JsonObject rotation = json("assets/murim/player_animations/six_harmonies_fear.json")
                .getAsJsonObject("animations").getAsJsonObject("six_harmonies_fear")
                .getAsJsonObject("bones").getAsJsonObject("right_arm").getAsJsonObject("rotation");
        int heldTicks = 0;
        for (int age = 6; age <= 18; age++) {
            double pitch = armPitch(rotation, 3, SixRules.tier(12), age, -1);
            // the clip's wind-up (-150 at 0.5-0.6 s) and at most a tenth of a turn on towards the overhead key (-185 at 0.7 s)
            assertTrue(pitch <= -150 && pitch >= -160.5, "sword arm must stay in the wind-up at " + age + ": " + pitch);
            heldTicks++;
        }
        assertTrue(heldTicks >= 10);
        assertTrue(armPitch(rotation, 3, 4, 19, -1) < -150, "blade fell before contact");
        assertEquals(-27.1, armPitch(rotation, 3, 4, 20, -1), 1E-4);
    }

    private static double armPitch(JsonObject rotation, int form, int tier, int age, int trigger) {
        double time = SixRules.animationTick(form, tier, age, trigger) / 20D;
        double before = -1, after = Double.POSITIVE_INFINITY, a = 0, b = 0;
        for (var entry : rotation.entrySet()) {
            double key = Double.parseDouble(entry.getKey());
            double pitch = entry.getValue().getAsJsonObject().getAsJsonArray("vector").get(0).getAsDouble();
            if (key <= time && key > before) { before = key; a = pitch; }
            if (key >= time && key < after) { after = key; b = pitch; }
        }
        return before == after ? a : a + (b - a) * (time - before) / (after - before);
    }

    @Test void languagesHaveEveryNewKeyAndSameKeySets() throws Exception {
        JsonObject en = json("assets/murim/lang/en_us.json"), ru = json("assets/murim/lang/ru_ru.json");
        assertEquals(en.keySet(), ru.keySet());
        // Count form resources, independently of mentor advice that also uses the form ids.
        Set<String> keys = en.keySet().stream().filter(k -> k.startsWith("technique.murim.six_harmonies_")
                || k.startsWith("form.murim.six_harmonies_") || k.startsWith("book.murim.six_harmonies_")
                || k.equals("style.murim.six_harmonies_forms")).collect(Collectors.toSet());
        assertEquals(21, keys.size());
        for (String form : SixRules.FORMS) for (String key : new String[]{"technique.murim.six_harmonies_" + form,
                "technique.murim.six_harmonies_" + form + ".weakness", "form.murim.six_harmonies_" + form,
                "book.murim.six_harmonies_" + form}) {
            assertTrue(en.has(key) && ru.has(key), key); assertFalse(ru.get(key).getAsString().isBlank());
        }
    }
}
