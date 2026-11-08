package io.github.verycooltimo.murim.world.fortress;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Green Forest Order (author 21.10): the camp leader's map tells in words where the fortress is. */
class FortressOrderTest {

    private static String read(String path) throws IOException {
        try (java.io.InputStream in = FortressOrderTest.class.getResourceAsStream("/" + path)) {
            return new String(java.util.Objects.requireNonNull(in, path).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Compass sector and rounded distance, no coordinates")
    void directionAndDistance() {
        assertEquals("north", FortressOrderFunction.direction(0, -300));
        assertEquals("east", FortressOrderFunction.direction(300, 0));
        assertEquals("south_west", FortressOrderFunction.direction(-200, 200));
        assertEquals("north_west", FortressOrderFunction.direction(-100, -100));
        assertEquals(300, FortressOrderFunction.roundedDistance(0, -310));
        assertEquals(50, FortressOrderFunction.roundedDistance(3, 4));
    }

    @Test
    @DisplayName("The chief drops the order through the loot function; every text exists in en and ru")
    void lootAndTexts() throws IOException {
        assertTrue(read("data/murim/loot_modifiers/fortress_order.json").contains("murim:entities/bandit_chief"));
        assertTrue(read("data/murim/loot_table/entities/fortress_order.json").contains("murim:fortress_order"));
        for (String lang : new String[] {"en_us", "ru_ru"}) {
            String json = read("assets/murim/lang/" + lang + ".json");
            for (String key : new String[] {"item.murim.fortress_order", "item.murim.fortress_order.text",
                    "item.murim.fortress_order.where", "item.murim.fortress_order.lost"}) {
                assertTrue(json.contains("\"" + key + "\""), lang + " lacks " + key);
            }
            for (String dir : FortressOrderFunction.DIRECTIONS) {
                assertTrue(json.contains("\"murim.fortress.order.dir." + dir + "\""), lang + " lacks direction " + dir);
            }
            assertTrue(json.contains("\"murim.sect.journey.blocker.boss\": ") && json.contains("\"murim.rank.wall.boss\": "));
        }
        // Check the progression hint itself, allowing sentence capitalization to change.
        JsonObject en = JsonParser.parseString(read("assets/murim/lang/en_us.json")).getAsJsonObject();
        JsonObject ru = JsonParser.parseString(read("assets/murim/lang/ru_ru.json")).getAsJsonObject();
        String englishHint = en.get("murim.rank.wall.boss").getAsString().toLowerCase(Locale.ROOT);
        String russianHint = ru.get("murim.rank.wall.boss").getAsString().toLowerCase(Locale.ROOT);
        assertTrue(englishHint.contains("clear a bandit camp") && englishHint.contains("green forest order"),
                "English fortress hint must explain the camp and its order");
        assertTrue(russianHint.contains("зачисти лагерь бандитов") && russianHint.contains("приказ зелёного леса"),
                "Russian fortress hint must explain the camp and its order");
    }
}
