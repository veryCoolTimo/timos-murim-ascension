package io.github.verycooltimo.murim.technique;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Resource contracts: both translations, honest weaknesses, and animation contact/recovery alignment. */
class MountHuaJunkDataTest {
    private static JsonObject resource(String name) throws Exception {
        Path path = Path.of("src/main/resources", name);
        if (!Files.exists(path)) path = Path.of("../..", path.toString());
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    @Test
    void curriculaHaveMatchingClipsAndBothLocales() throws Exception {
        for (String id : new String[]{"true_six_elements_sword", "taiyi_sword"}) {
            JsonObject data = resource("data/murim/murim_techniques/" + id + ".json");
            JsonObject phases = data.getAsJsonObject("phases");
            int total = phases.entrySet().stream().mapToInt(entry -> entry.getValue().getAsInt()).sum();
            JsonObject animation = resource("assets/murim/player_animations/junk_" + id + ".json")
                    .getAsJsonObject("animations").getAsJsonObject("junk_" + id);
            assertEquals(total / 20.0D, animation.get("animation_length").getAsDouble(), 1E-9);
            String contact = id.equals("taiyi_sword") ? "0.6" : "0.2";
            assertEquals(phases.get("windup").getAsInt() / 20.0D, Double.parseDouble(contact), 1E-9);
            assertTrue(animation.getAsJsonObject("bones").getAsJsonObject("right_arm")
                    .getAsJsonObject("rotation").has(contact));
            assertTrue(data.get("cooldown_ticks").getAsInt() >= total);
            assertTrue(data.getAsJsonObject("behavior").get("damage").getAsDouble() <= 2);
            for (String locale : new String[]{"en_us", "ru_ru"}) {
                JsonObject lang = resource("assets/murim/lang/" + locale + ".json");
                for (String key : new String[]{"technique.murim." + id, "art.murim." + id,
                        "book.murim." + id, "technique.murim." + id + ".description", data.get("weakness").getAsString()}) {
                    assertTrue(lang.has(key) && !lang.get(key).getAsString().isBlank(), locale + ": " + key);
                }
                String suffix = locale.equals("en_us") ? "(copy)" : "(копия)";
                for (String key : new String[]{"item.murim.technique_manual", "item.murim.technique_manual.named",
                        "item.murim.method_scroll", "item.murim.method_scroll.named", "item.murim.manual_copy"}) {
                    assertTrue(lang.get(key).getAsString().endsWith(suffix), locale + ": " + key);
                }
            }
        }
    }
}
