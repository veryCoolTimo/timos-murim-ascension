package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Validate raw conditions before the permissive codecs can discard misspelled fields. */
class DialogueConditionKeysTest {
    private static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(
                "src/main/java/io/github/verycooltimo/murim/sect/Dialogue.java"))) root = root.getParent();
        assertNotNull(root, "Dialogue.java source tree not found above the working directory");
        return root;
    }

    @Test
    void everyDialogueConditionUsesCodecFields() throws Exception {
        Path root = root();
        String source = Files.readString(root.resolve("src/main/java/io/github/verycooltimo/murim/sect/Dialogue.java"));
        // Condition includes Standing's flattened MapCodec. Read both declarations so this stays in sync.
        String codecs = source.substring(source.indexOf("public record Condition("), source.indexOf("public record Audience("));
        var matcher = Pattern.compile("(?:optionalFieldOf|fieldOf)\\(\"([^\"]+)\"").matcher(codecs);
        Set<String> allowed = new HashSet<>();
        while (matcher.find()) allowed.add(matcher.group(1));
        assertTrue(allowed.containsAll(Set.of("below_star", "min_star", "social", "shop")));
        Path data = root.resolve("src/main/resources/data");
        try (var paths = Files.walk(data)) {
            var dialogues = paths.filter(p -> p.toString().contains("/murim_dialogues/")
                    && p.toString().endsWith(".json")).toList();
            assertFalse(dialogues.isEmpty(), "No dialogue JSON found");
            for (Path path : dialogues) {
                try (var reader = Files.newBufferedReader(path)) {
                    check(JsonParser.parseReader(reader), allowed, path.toString());
                }
            }
        }
    }

    @Test
    void mentorUsesOnePermanentGeneratedAnswer() throws Exception {
        Path resources = root().resolve("src/main/resources");
        String source = Files.readString(resources.resolve("data/murim/murim_dialogues/mentor.json"));
        var nodes = JsonParser.parseString(source).getAsJsonObject().getAsJsonObject("nodes");
        var options = nodes.getAsJsonObject("again").getAsJsonArray("options");
        int count = 0;
        for (var entry : options) {
            var option = entry.getAsJsonObject();
            if (!option.get("text").getAsString().equals("dialogue.murim.mentor.opt.next_art")) continue;
            count++;
            assertFalse(option.has("when"));
            assertEquals("@next_art", nodes.getAsJsonObject(option.get("next").getAsString()).get("line").getAsString());
        }
        assertEquals(1, count);
        for (String language : new String[]{"en_us", "ru_ru"}) {
            String lang = Files.readString(resources.resolve("assets/murim/lang/" + language + ".json"));
            assertFalse(lang.contains("next_six"));
            assertFalse(lang.contains("cut_locked"));
        }
        assertFalse(source.contains("next_six"));
        assertFalse(source.contains("cut_locked"));
    }

    private static void check(JsonElement json, Set<String> allowed, String path) {
        if (json.isJsonArray()) {
            for (int i = 0; i < json.getAsJsonArray().size(); i++)
                check(json.getAsJsonArray().get(i), allowed, path + "[" + i + "]");
        } else if (json.isJsonObject()) {
            for (var entry : json.getAsJsonObject().entrySet()) {
                String key = entry.getKey();
                if (key.equals("when") || key.equals("allow")) {
                    for (var condition : entry.getValue().getAsJsonArray()) {
                        Set<String> unknown = new HashSet<>(condition.getAsJsonObject().keySet());
                        unknown.removeAll(allowed);
                        assertTrue(unknown.isEmpty(), path + "." + key + " unknown condition keys: " + unknown);
                    }
                }
                check(entry.getValue(), allowed, path + "." + key);
            }
        }
    }
}
