package io.github.verycooltimo.murim.mastery;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.cultivation.RankRequirements;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * hua-main-1810 D-4: the player sees the requirement of a breakthrough only in words (tablet, mentor, the wall hint), never
 * as a number of stars; both languages carry the new texts of Track A and no leftover of the decay of the session.
 */
class RequirementWordsTest {

    private static JsonObject lang(String name) throws Exception {
        try (var stream = RequirementWordsTest.class.getResourceAsStream("/assets/murim/lang/" + name + ".json")) {
            assertNotNull(stream, name);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static final List<String> NEW_KEYS = List.of(
            "murim.meditation.activity.cleanse", "murim.meditation.activity.echo", "murim.meditation.echo.done",
            "murim.mastery.slept", "murim.page.limit", "murim.sect.merit.limit", "murim.mastery.requirement");

    @Test
    @DisplayName("Слова требований: у каждой ступени таблицы есть текст на обоих языках, и в нём нет цифр")
    void everyGateHasWordsAndNoNumbers() throws Exception {
        for (String name : List.of("en_us", "ru_ru")) {
            JsonObject lang = lang(name);
            for (RankRequirements.Gate gate : RankRequirements.Gate.values()) {
                assertTrue(lang.has(gate.wordKey()), name + " lacks " + gate.wordKey());
                String text = lang.get(gate.wordKey()).getAsString();
                assertFalse(text.isBlank(), gate.wordKey());
                assertFalse(text.matches(".*\\d.*"), name + " " + gate.wordKey() + " shows a number: " + text);
                assertFalse(text.contains("★") || text.contains("%"), name + " " + gate.wordKey() + " shows a star: " + text);
            }
            // The line that joins the art and the words has exactly two slots and no star number.
            String line = lang.get("murim.mastery.requirement").getAsString();
            assertEquals(2, line.split("%s", -1).length - 1, name + " requirement line: " + line);
            assertFalse(line.contains("%d") || line.matches(".*\\d.*"), name + " requirement line has a number: " + line);
        }
    }

    @Test
    @DisplayName("Новые тексты Track A есть в en_us и ru_ru; следов затухания сессии («ум устаёт») не осталось")
    void bothLanguagesCarryTheNewTexts() throws Exception {
        JsonObject en = lang("en_us");
        JsonObject ru = lang("ru_ru");
        for (String key : NEW_KEYS) {
            assertTrue(en.has(key), "en_us lacks " + key);
            assertTrue(ru.has(key), "ru_ru lacks " + key);
            assertEquals(en.get(key).getAsString().split("%s", -1).length, ru.get(key).getAsString().split("%s", -1).length,
                    "placeholders differ in " + key);
        }
        for (String gone : List.of("murim.meditation.seeded.tired", "murim.meditation.seeded.pondering", "murim.meditation.reason.tired")) {
            assertFalse(en.has(gone) || ru.has(gone), gone + " belongs to the decay and the old queue of the double");
        }
        // Every key of one language exists in the other (no raw keys in either).
        for (String key : en.keySet()) {
            assertTrue(ru.has(key), "ru_ru lacks " + key);
        }
        for (String key : ru.keySet()) {
            assertTrue(en.has(key), "en_us lacks " + key);
        }
    }
}
