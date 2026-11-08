package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DialogueVoicePassTest {
    private static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("src/main/resources/data/murim/murim_dialogues"))) p = p.getParent();
        assertNotNull(p, "source tree missing");
        return p;
    }

    private static JsonObject json(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    @Test void personalLinesHaveBothLanguagesAndDistinctWords() throws Exception {
        Path lang = root().resolve("src/main/resources/assets/murim/lang");
        JsonObject ru = json(lang.resolve("ru_ru.json")), en = json(lang.resolve("en_us.json"));
        java.util.Set<String> words = new java.util.HashSet<>();
        for (String npc : List.of("hwang_sol", "jang_cheol", "mok_rin", "gyeong_on", "gyeong_dan", "gyeong_ha",
                "gyeong_ryun", "porter_jang", "porter_oh", "disciple_a", "disciple_b")) {
            for (int i = 1; i <= 6; i++) {
                String key = "dialogue.murim." + npc + ".personal." + i;
                assertTrue(ru.has(key), key); assertTrue(en.has(key), key);
                assertFalse(en.get(key).getAsString().isBlank(), key);
                assertTrue(words.add(ru.get(key).getAsString()), key);
                assertEquals(DialogueDataTest.placeholders(ru.get(key).getAsString()),
                        DialogueDataTest.placeholders(en.get(key).getAsString()), key);
            }
        }
        for (String key : SectSpeechGuard.RETIRED) {
            assertFalse(ru.has(key), key); assertFalse(en.has(key), key);
        }
        for (String key : List.of("murim.bubble.carry.4", "murim.bubble.carry.5", "murim.bubble.cook.3",
                "murim.bubble.cook.5", "murim.bubble.cleaning_cycle.4", "murim.bubble.kitchen_cycle.5",
                "murim.bubble.guard_break.1", "murim.bubble.guard_cover.1", "murim.bubble.guard_night.7",
                "murim.bubble.dispute.1", "murim.bubble.dispute.2", "murim.bubble.drill.8", "murim.bubble.meal.8",
                "murim.bubble.reply.topic.salt", "murim.bubble.lesson_correction.1", "murim.bubble.lesson_correction.5",
                "murim.bubble.repair_crew.3", "murim.bubble.tend.2", "murim.bubble.contract.done.gyeong_cho.2",
                "murim.bubble.quest.thief_hook")) {
            assertTrue(ru.has(key), key); assertTrue(en.has(key), key); assertTrue(SectSpeechGuard.active(key), key);
        }
    }

    @Test void ordinaryServantAndJuniorOpeningsDoNotBow() throws Exception {
        Path dir = root().resolve("src/main/resources/data/murim/murim_dialogues");
        for (String npc : List.of("cook", "porter", "water_carrier", "sweeper", "gardener", "steward", "disciple",
                "second", "senior", "ham_doyun", "bok_manseok", "yul_seok", "yul_jin", "seo_gyu", "seo_ho",
                "seo_rang", "mok_hayeon", "hwang_sol", "jang_cheol", "mok_rin", "seo_han")) {
            JsonObject data = json(dir.resolve(npc + ".json"));
            java.util.Set<String> openings = new java.util.HashSet<>(List.of("greet", "office", "office_first", "office_elder", "member", "night"));
            if (data.has("start")) for (var start : data.getAsJsonArray("start")) openings.add(start.getAsJsonObject().get("node").getAsString());
            JsonObject nodes = data.getAsJsonObject("nodes");
            for (String node : openings) if (nodes.has(node)) {
                JsonObject value = nodes.getAsJsonObject(node);
                if (value.has("gesture")) assertNotEquals("bow", value.get("gesture").getAsString(), npc + "." + node);
            }
        }
    }
}
