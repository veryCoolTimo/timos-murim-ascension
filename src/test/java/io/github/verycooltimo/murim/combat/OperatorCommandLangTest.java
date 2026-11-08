package io.github.verycooltimo.murim.combat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class OperatorCommandLangTest {
    private JsonObject language(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/assets/murim/lang/" + name + ".json")) {
            assertNotNull(in);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    @Test void operatorFeedbackHasBothLanguagesAndMatchingArguments() throws Exception {
        var en = language("en_us");
        var ru = language("ru_ru");
        var args = Pattern.compile("%(?:\\d+\\$)?[sd]");
        for (String suffix : new String[] {"loaded", "not_loaded", "not_started", "method_missing", "seed",
                "art_missing", "qi_full", "wall", "cooldown", "profile_reset", "dummy", "awakened", "no_target",
                "body_level", "body_reset", "body_summary", "body_sources", "body_best", "body_now", "body_climb"}) {
            String key = "command.murim.dev." + suffix;
            assertTrue(en.has(key), key); assertTrue(ru.has(key), key);
            assertFalse(Pattern.compile("[\\u0400-\\u04ff]").matcher(en.get(key).getAsString()).find(), key);
            assertEquals(args.matcher(en.get(key).getAsString()).results().map(m -> m.group()).toList(),
                    args.matcher(ru.get(key).getAsString()).results().map(m -> m.group()).toList(), key);
        }
    }
}
