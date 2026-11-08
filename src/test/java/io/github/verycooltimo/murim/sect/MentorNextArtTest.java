package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.mastery.TechniqueRequirement;
import io.github.verycooltimo.murim.technique.Styles;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MentorNextArtTest {
    private static Path resources() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(
                "src/main/java/io/github/verycooltimo/murim/sect/MentorNextArt.java"))) root = root.getParent();
        assertNotNull(root, "MentorNextArt source tree not found");
        return root.resolve("src/main/resources");
    }

    @Test
    void everyStyleAtEveryStarUsesLoadedRequirementsAndCanonNamesWithoutDigits() throws Exception {
        Map<ResourceLocation, List<TechniqueRequirement>> requirements = new HashMap<>();
        for (var style : Styles.ALL) for (var form : style.forms()) {
            var data = JsonParser.parseString(Files.readString(resources().resolve(
                    "data/murim/murim_techniques/" + form.getPath() + ".json"))).getAsJsonObject();
            List<TechniqueRequirement> req = new ArrayList<>();
            if (data.has("requires")) for (var entry : data.getAsJsonArray("requires")) {
                var r = entry.getAsJsonObject();
                req.add(new TechniqueRequirement(ResourceLocation.parse(r.get("technique").getAsString()),
                        r.has("star") ? r.get("star").getAsInt() : 1));
            }
            requirements.put(form, req);
        }
        for (String locale : List.of("en_us", "ru_ru")) {
            var lang = JsonParser.parseString(Files.readString(resources().resolve("assets/murim/lang/" + locale + ".json"))).getAsJsonObject();
            for (var style : Styles.ALL) for (int star = 0; star <= 12; star++) {
                int current = star;
                var expected = style.forms().stream().filter(f -> requirements.get(f).stream()
                        .anyMatch(r -> r.star() > current)).findFirst();
                var answer = MentorNextArt.next(style, art -> current, requirements::get);
                assertEquals(expected, answer.map(MentorNextArt.Next::form), style.id() + " at " + star);
                String text;
                if (answer.isPresent()) {
                    var n = answer.get();
                    String name = lang.get("form." + n.form().getNamespace() + "." + n.form().getPath()).getAsString();
                    String advice = lang.get("dialogue.murim.mentor.practice." + n.form().getPath()).getAsString();
                    String word = lang.get(MentorNextArt.words(n.requirement().star(), 12)).getAsString();
                    text = lang.get("dialogue.murim.mentor.next.form").getAsString()
                            .formatted(lang.get(MentorNextArt.artKey(n.form())).getAsString(), name, "art", word, advice);
                    assertTrue(text.contains(name));
                    assertTrue(text.contains(word));
                } else {
                    String key = style == Styles.SIX_HARMONIES ? "dialogue.murim.mentor.next.exam"
                            : "dialogue.murim.mentor.next.done." + io.github.verycooltimo.murim.mastery.Arts.masteryId(style.forms().getFirst()).getPath();
                    assertTrue(lang.has(key), locale + " missing mentor advice: " + key);
                    String art = lang.get(MentorNextArt.artKey(style.forms().getFirst())).getAsString();
                    text = lang.get(key).getAsString().formatted(art);
                    if (style == Styles.SEVEN_STARS) {
                        assertEquals(locale.equals("en_us") ? "Seven Stars Steps" : "Шаги Семи Звёзд", art);
                        assertTrue(text.contains(art));
                    }
                }
                assertFalse(text.matches("(?s).*[0-9★].*"), text);
            }
        }
    }
}
