package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The conversation nodes the player meets in daily talk have at least six lines (docs/design/39, «Реплики: объём и частота»). */
class SectDailyNodesTest {
    private static final int MIN = SectLines.MIN_VARIANTS;

    private static JsonObject lang(String code) throws IOException {
        Path p = Path.of("src/main/resources/assets/murim/lang/" + code + ".json");
        if (!Files.exists(p)) {
            p = Path.of("../../src/main/resources/assets/murim/lang/" + code + ".json");
        }
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    /** Nodes the player meets in daily talk: servants, and the lines of the formation / bout nodes shared by dozens of disciples. */
    private static final Map<String, List<String>> DAILY_NODES = Map.ofEntries(
            Map.entry("cook", List.of("greet", "serving", "office", "office_serving")),
            Map.entry("water_carrier", List.of("greet", "morning", "office")),
            Map.entry("porter", List.of("greet", "hauling", "office")),
            Map.entry("gardener", List.of("greet", "garden", "office")),
            Map.entry("sweeper", List.of("greet", "sweeping", "office")),
            Map.entry("steward", List.of("greet", "accounts", "office")),
            Map.entry("disciple", List.of("formation", "busy", "doing")),
            Map.entry("second", List.of("busy", "formation")),
            Map.entry("senior", List.of("busy", "formation", "refused")),
            Map.entry("first", List.of("busy", "formation", "advice")),
            Map.entry("guard", List.of("greet", "night")),
            Map.entry("gatekeeper", List.of("member_night")));

    @Test
    @DisplayName("The conversation nodes of servants and the shared role nodes have at least six lines, all in ru_ru and en_us")
    void dailyConversationNodesAreFull() throws IOException {
        JsonObject ru = lang("ru_ru");
        JsonObject en = lang("en_us");
        for (Map.Entry<String, List<String>> e : DAILY_NODES.entrySet()) {
            Path p = Path.of("src/main/resources/data/murim/murim_dialogues/" + e.getKey() + ".json");
            if (!Files.exists(p)) {
                p = Path.of("../../src/main/resources/data/murim/murim_dialogues/" + e.getKey() + ".json");
            }
            JsonObject nodes = JsonParser.parseString(Files.readString(p)).getAsJsonObject().getAsJsonObject("nodes");
            for (String node : e.getValue()) {
                var random = nodes.getAsJsonObject(node).getAsJsonArray("random");
                assertTrue(random.size() >= MIN, e.getKey() + "/" + node + " has " + random.size() + " lines");
                Set<String> seen = new HashSet<>();
                for (var el : random) {
                    String key = el.getAsString();
                    assertTrue(seen.add(key), e.getKey() + "/" + node + " lists " + key + " twice");
                    assertTrue(ru.has(key) && en.has(key), "lang lacks " + key);
                    assertEquals(DialogueDataTest.placeholders(en.get(key).getAsString()), DialogueDataTest.placeholders(ru.get(key).getAsString()), "%s in " + key);
                }
            }
        }
    }
}
