package io.github.verycooltimo.murim.technique;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Resource contract from CLAUDE.md §2a and the 6 November animation brief. */
class DarkFragranceAnimationTest {
    private JsonObject resource(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    @Test void clipsUseKnownBonesAndMatchTheirTechniqueClocks() throws Exception {
        Set<String> allowed = Set.of("head", "torso", "left_arm", "right_arm", "left_leg", "right_leg");
        for (String form : List.of("behind", "trail", "floating")) {
            String name = "dark_fragrance_" + form;
            JsonObject technique = resource("/data/murim/murim_techniques/" + name + ".json");
            assertEquals("murim:" + name, technique.get("animation").getAsString());
            String clipName = technique.get("animation").getAsString().substring("murim:".length());
            JsonObject clip = resource("/assets/murim/player_animations/" + clipName + ".json")
                    .getAsJsonObject("animations").getAsJsonObject(clipName);
            boolean looping = clip.get("loop").getAsBoolean();
            assertEquals(form.equals("floating"), looping, name);
            double length = clip.get("animation_length").getAsDouble();
            if (!looping) {
                int ticks = technique.getAsJsonObject("phases").entrySet().stream()
                        .mapToInt(e -> e.getValue().getAsInt()).sum();
                assertEquals(ticks / 20.0, length, 1e-9, name);
            } else {
                assertEquals(1.0, length, 1e-9, name);
            }
            JsonObject bones = clip.getAsJsonObject("bones");
            assertEquals(allowed, bones.keySet(), name);
            for (var bone : bones.entrySet()) {
                assertTrue(allowed.contains(bone.getKey()), name + "/" + bone.getKey());
                for (var channel : bone.getValue().getAsJsonObject().entrySet()) {
                    JsonObject keys = channel.getValue().getAsJsonObject();
                    assertTrue(keys.has("0.0"), name);
                    assertTrue(keys.has(Double.toString(length)), name);
                    for (String time : keys.keySet()) {
                        assertTrue(Double.parseDouble(time) >= 0 && Double.parseDouble(time) <= length, name);
                    }
                    if (looping) assertEquals(keys.get("0.0"), keys.get(Double.toString(length)), name + "/" + bone.getKey());
                }
            }
        }
    }
}
