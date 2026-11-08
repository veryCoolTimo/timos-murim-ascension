package io.github.verycooltimo.murim.sect;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Author 22.10: an acknowledgement reply ("Understood, master") must end the talk, not jump back to a start node of the
 * dialogue. Reads the dialogue JSON and the English texts straight from the resources.
 */
class DialogueAckClosesTest {

    private static final Pattern ACK = Pattern.compile(
            "(?i)^(understood|got it|i see|very well|all right|alright|thank|thanks|clear\\b|okay)");

    /** Acknowledgements that lead on to a real follow-up on purpose (the tour's journey, the basics offer): other tests pin them. */
    private static final Set<String> FOLLOW_UPS = Set.of("mentor.json:six_given", "mentor.json:tour_end", "mentor.json:intro_5");

    @Test
    void acknowledgementRepliesNeverLoopBackToAStartNode() throws Exception {
        // The resources sit in a jar on the test classpath: walk up from the working directory to the source tree.
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null && !new File(dir, "src/main/resources/data/murim/murim_dialogues").isDirectory()) {
            dir = dir.getParentFile();
        }
        assertTrue(dir != null, "source tree not found");
        File root = new File(dir, "src/main/resources");
        JsonObject en;
        try (Reader r = new FileReader(new File(root, "assets/murim/lang/en_us.json"))) {
            en = JsonParser.parseReader(r).getAsJsonObject();
        }
        File[] files = new File(root, "data/murim/murim_dialogues").listFiles((d, n) -> n.endsWith(".json"));
        assertTrue(files != null && files.length > 0, "no dialogue files");
        StringBuilder bad = new StringBuilder();
        for (File f : files) {
            JsonObject d;
            try (Reader r = new FileReader(f)) {
                d = JsonParser.parseReader(r).getAsJsonObject();
            }
            Set<String> starts = new HashSet<>();
            if (d.has("start") && d.get("start").isJsonArray()) {
                for (JsonElement s : d.getAsJsonArray("start")) {
                    if (s.isJsonObject() && s.getAsJsonObject().has("node")) {
                        starts.add(s.getAsJsonObject().get("node").getAsString());
                    }
                }
            }
            if (!d.has("nodes")) {
                continue;
            }
            for (var node : d.getAsJsonObject("nodes").entrySet()) {
                JsonObject n = node.getValue().getAsJsonObject();
                if (!n.has("options")) {
                    continue;
                }
                for (JsonElement oe : n.getAsJsonArray("options")) {
                    JsonObject o = oe.getAsJsonObject();
                    String key = o.get("text").getAsString();
                    String text = en.has(key) ? en.get(key).getAsString() : key;
                    if (ACK.matcher(text).find() && !FOLLOW_UPS.contains(f.getName() + ':' + node.getKey()) && o.has("next") && starts.contains(o.get("next").getAsString())) {
                        bad.append(f.getName()).append(':').append(node.getKey()).append(" \"").append(text).append("\" -> ")
                                .append(o.get("next").getAsString()).append('\n');
                    }
                }
            }
        }
        assertTrue(bad.length() == 0, "acknowledgement replies that jump back to the start:\n" + bad);
    }
}
