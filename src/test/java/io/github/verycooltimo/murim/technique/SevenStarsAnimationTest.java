package io.github.verycooltimo.murim.technique;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Asset contract from docs/design/research/r1-player-animation.md §4.3/§4.4. */
class SevenStarsAnimationTest {
    private static final Set<String> RIG = Set.of("torso", "head", "right_arm", "left_arm", "right_leg", "left_leg");

    @Test void clipsUsePlayerRigAndCoverEveryFootworkPhase() throws Exception {
        for (String name : new String[] {"seven_stars_shift", "seven_stars_shift_right", "seven_stars_follow"}) {
            boolean follow = name.equals("seven_stars_follow");
            String technique = follow ? name : "seven_stars_shift";
            JsonObject definition = read("data/murim/murim_techniques/" + technique + ".json");
            assertEquals("murim:" + technique, definition.get("animation").getAsString());
            JsonObject phases = definition.getAsJsonObject("phases");
            assertEquals(SevenStarsRules.PREPARATION, phases.get("windup").getAsInt());
            assertEquals(SevenStarsRules.movement(follow), phases.get("impact").getAsInt());
            assertEquals(SevenStarsRules.recovery(follow), phases.get("recovery").getAsInt());
            JsonObject clip = read("assets/murim/player_animations/" + name + ".json")
                    .getAsJsonObject("animations").getAsJsonObject(name);
            double length = clip.get("animation_length").getAsDouble();
            assertEquals((SevenStarsRules.PREPARATION + SevenStarsRules.movement(follow)
                    + SevenStarsRules.recovery(follow)) / 20.0, length, 1e-6);
            assertFalse(clip.get("loop").getAsBoolean());
            JsonObject bones = clip.getAsJsonObject("bones");
            assertEquals(RIG, bones.keySet());
            for (var bone : bones.entrySet()) {
                for (var channel : bone.getValue().getAsJsonObject().entrySet()) {
                    JsonObject keys = channel.getValue().getAsJsonObject();
                    // JSON time labels may spell the same instant as "0" or "0.0".
                    assertTrue(keys.keySet().stream().anyMatch(key -> Double.parseDouble(key) == 0),
                            name + ": missing initial key for " + bone.getKey() + "/" + channel.getKey());
                    assertTrue(keys.has("0.1"));
                    assertTrue(keys.has(follow ? "0.15" : "0.3"));
                    assertTrue(keys.has("0.3"), name + ": missing movement/recovery boundary");
                    assertTrue(keys.has(follow ? "0.5" : "0.6"));
                    for (var key : keys.entrySet()) {
                        double time = Double.parseDouble(key.getKey());
                        assertTrue(time >= 0 && time <= length, name + ": " + key.getKey());
                    }
                }
            }
        }
    }

    @Test void naturalStepEndEasesToVanillaOverFourTicks() throws Exception {
        assertEquals(4, io.github.verycooltimo.murim.client.AnimationTransitions.STEP_FADE_OUT_TICKS);
        for (String name : new String[] {"seven_stars_shift", "seven_stars_shift_right", "seven_stars_follow"}) {
            JsonObject clip = read("assets/murim/player_animations/" + name + ".json")
                    .getAsJsonObject("animations").getAsJsonObject(name);
            double end = clip.get("animation_length").getAsDouble();
            boolean follow = name.equals("seven_stars_follow");
            double fadeStart = follow ? .3 : .4;
            assertEquals(io.github.verycooltimo.murim.client.AnimationTransitions.STEP_FADE_OUT_TICKS,
                    (end - fadeStart) * 20, 1e-6);
            for (var bone : clip.getAsJsonObject("bones").entrySet()) {
                for (var channel : bone.getValue().getAsJsonObject().entrySet()) {
                    JsonObject keys = channel.getValue().getAsJsonObject();
                    assertTrue(keys.has(follow ? "0.3" : "0.4"), name + ": missing fade start");
                    // Slow-in/slow-out is baked into inner keys (lessons §4): the fade may have them, but every
                    // component only travels toward rest, so the four ticks stay one smooth return.
                    assertMonotonicToRest(keys, fadeStart, end, name + " " + bone.getKey() + "/" + channel.getKey());
                    JsonObject rest = keys.getAsJsonObject(follow ? "0.5" : "0.6");
                    assertEquals("easeInOutSine", rest.get("easing").getAsString());
                    if (rest.has("vector")) {
                        for (var component : rest.getAsJsonArray("vector")) assertEquals(0, component.getAsDouble());
                    } else {
                        assertEquals(0, rest.get("value").getAsDouble());
                    }
                }
            }
        }
    }

    @Test void dipperCutsOnEveryServerContactAndEasesHome() throws Exception {
        JsonObject definition = read("data/murim/murim_techniques/seven_stars_dipper.json");
        assertEquals(28, definition.getAsJsonObject("phases").get("impact").getAsInt());
        assertEquals(400, definition.get("cooldown_ticks").getAsInt());
        assertEquals(8, definition.getAsJsonArray("requires").get(0).getAsJsonObject().get("star").getAsInt());
        JsonObject clip = read("assets/murim/player_animations/seven_stars_dipper.json")
                .getAsJsonObject("animations").getAsJsonObject("seven_stars_dipper");
        double length = clip.get("animation_length").getAsDouble();
        // The last cut lands at 1.35 s; the clip holds it 0.3 s and eases home over 0.2 s (lessons §4).
        assertTrue(length >= 1.35 + .3 + .2 - 1e-6, "the last cut needs a hold and a return");
        assertFalse(clip.get("loop").getAsBoolean());
        assertEquals(RIG, clip.getAsJsonObject("bones").keySet());
        JsonObject arm = clip.getAsJsonObject("bones").getAsJsonObject("right_arm").getAsJsonObject("rotation");
        for (int step = 0; step < 7; step++) {
            // DipperService cuts on the landing tick of every 4-tick step: ticks 3, 7, ..., 27.
            String contact = trim(.15 + .2 * step), prep = trim(.1 + .2 * step);
            assertTrue(arm.has(contact), "no cut on contact " + contact);
            assertTrue(arm.has(prep), "no wind-up before contact " + contact);
        }
        for (var bone : clip.getAsJsonObject("bones").entrySet()) {
            for (var channel : bone.getValue().getAsJsonObject().entrySet()) {
                JsonObject keys = channel.getValue().getAsJsonObject();
                String last = keys.keySet().stream().max(java.util.Comparator.comparingDouble(Double::parseDouble)).orElseThrow();
                assertEquals(length, Double.parseDouble(last), 1e-6);
                assertEquals("easeInOutSine", keys.getAsJsonObject(last).get("easing").getAsString());
                assertMonotonicToRest(keys, length - .2, length, "dipper " + bone.getKey() + "/" + channel.getKey());
            }
        }
    }

    private static String trim(double t) {
        String s = String.format(java.util.Locale.ROOT, "%.3f", t).replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.equals("0") ? "0.0" : s;
    }

    /** From {@code from} to {@code to} every component of the channel moves only toward 0 and ends at 0. */
    private static void assertMonotonicToRest(JsonObject keys, double from, double to, String what) {
        java.util.List<double[]> values = keys.entrySet().stream()
                .filter(e -> Double.parseDouble(e.getKey()) >= from - 1e-6 && Double.parseDouble(e.getKey()) <= to + 1e-6)
                .sorted(java.util.Comparator.comparingDouble(e -> Double.parseDouble(e.getKey())))
                .map(e -> {
                    JsonObject v = e.getValue().getAsJsonObject();
                    if (v.has("vector")) {
                        var a = v.getAsJsonArray("vector");
                        return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
                    }
                    return new double[] {v.get("value").getAsDouble()};
                }).toList();
        assertFalse(values.isEmpty(), what + ": no keys in the return");
        for (int i = 1; i < values.size(); i++) {
            for (int c = 0; c < values.get(i).length; c++) {
                assertTrue(Math.abs(values.get(i)[c]) <= Math.abs(values.get(i - 1)[c]) + 1e-6, what + ": moves away from rest");
            }
        }
        for (double v : values.getLast()) assertEquals(0, v, 1e-6, what + ": does not end at rest");
    }

    private static JsonObject read(String relative) throws Exception {
        // Match TangRulesTest: test resources must not depend on the working directory.
        try (InputStream in = SevenStarsAnimationTest.class.getResourceAsStream("/" + relative)) {
            assertNotNull(in, "Missing classpath resource: " + relative);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
