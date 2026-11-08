package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Props fixes 22.10: the wayfinding plaque keys read in both languages, and the money rooms hold no firewood or blank paper. */
class SectPropsTest {
    private static final Pattern CYRILLIC = Pattern.compile("[\\u0400-\\u04FF]");

    private static JsonObject json(String resource) throws IOException {
        try (var in = SectPropsTest.class.getResourceAsStream("/" + resource)) {
            assertTrue(in != null, "missing resource " + resource);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void plaqueKeysExistInBothLanguagesAndEnglishIsEnglish() throws IOException {
        JsonObject en = json("assets/murim/lang/en_us.json");
        JsonObject ru = json("assets/murim/lang/ru_ru.json");
        for (String key : en.keySet()) {
            if (!key.startsWith("murim.sect.way.") && !key.startsWith("murim.loot.") && !key.equals("murim.sect.name_plate.title")) continue;
            assertTrue(ru.has(key), "ru_ru lacks " + key);
            assertFalse(CYRILLIC.matcher(en.get(key).getAsString()).find(), "en_us text of " + key + " is not English");
        }
        for (String key : Set.of("murim.sect.way.dorm2", "murim.sect.way.dorm3", "murim.sect.way.room", "murim.sect.way.floor_stairs", "murim.sect.name_plate.title")) {
            assertTrue(en.has(key) && ru.has(key), key);
        }
    }

    @Test
    void moneyRoomsHoldNoFirewoodOrBlankPaper() throws IOException {
        for (String kind : new String[] {"vault", "counting", "steward", "elder", "head"}) {
            JsonObject table = json("data/murim/loot_table/chests/sect/" + kind + ".json");
            JsonArray entries = table.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries");
            for (JsonElement e : entries) {
                JsonObject o = e.getAsJsonObject();
                String name = o.get("name").getAsString();
                assertFalse(name.contains("charcoal") || name.contains("coal") || name.equals("minecraft:stick"), kind + " holds " + name);
                if (name.equals("minecraft:paper") && !kind.equals("counting")) {
                    assertTrue(o.toString().contains("murim.loot."), kind + ": paper must be a named receipt or scroll");
                }
            }
        }
    }
}
