package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Реплики над головой (живая гора, автор 05.10): строки есть в ru и en, короткие, группа по делу. */
class SectBubblesTest {

    private static JsonObject lang(String code) throws IOException {
        // Рабочая папка тестов — run/junit (как в DialogueDataTest).
        Path p = Path.of("src/main/resources/assets/murim/lang/" + code + ".json");
        if (!Files.exists(p)) {
            p = Path.of("../../src/main/resources/assets/murim/lang/" + code + ".json");
        }
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    @Test
    @DisplayName("Каждая строка пузыря есть в ru_ru и en_us, не длиннее 40 символов, %s совпадают")
    void keysShort() throws IOException {
        JsonObject ru = lang("ru_ru");
        JsonObject en = lang("en_us");
        for (String k : SectBubbles.allKeys()) {
            assertTrue(ru.has(k), "нет ru: " + k);
            assertTrue(en.has(k), "нет en: " + k);
            String r = ru.get(k).getAsString();
            String e = en.get(k).getAsString();
            assertTrue(r.length() <= SectBubbles.MAX_CHARS, "длинно ru: " + k + " — " + r);
            assertTrue(e.length() <= SectBubbles.MAX_CHARS, "длинно en: " + k + " — " + e);
            assertEquals(r.split("%s", -1).length, e.split("%s", -1).length, "аргументы: " + k);
        }
    }

    @Test
    @DisplayName("Группа по делу: стол, круг, строй наставника, ночная стража, дождь; строй и сон молчат")
    void groups() {
        assertEquals(SectBubbles.Group.MEAL, SectBubbles.forKind(SectSchedule.Kind.EAT, false, false, false, 500));
        assertEquals(SectBubbles.Group.EVENING, SectBubbles.forKind(SectSchedule.Kind.REST, false, false, false, 500));
        assertEquals(SectBubbles.Group.MENTOR_START, SectBubbles.forKind(SectSchedule.Kind.INSPECT, false, true, false, 100));
        assertEquals(SectBubbles.Group.MENTOR, SectBubbles.forKind(SectSchedule.Kind.INSPECT, false, true, false, 900));
        assertEquals(SectBubbles.Group.GUARD_NIGHT, SectBubbles.forKind(SectSchedule.Kind.GUARD, false, false, true, 900));
        assertEquals(SectBubbles.Group.RAIN, SectBubbles.forKind(SectSchedule.Kind.SHELTER, false, false, false, 900));
        assertEquals(SectBubbles.Group.STEWARD, SectBubbles.forKind(SectSchedule.Kind.WORK, true, false, false, 900));
        assertNull(SectBubbles.forKind(SectSchedule.Kind.FORM_ROW, false, false, false, 900));
        assertNull(SectBubbles.forKind(SectSchedule.Kind.SLEEP, false, false, true, 900));
        assertNull(SectBubbles.forKind(SectSchedule.Kind.WORK, false, false, false, 900));
        assertNotNull(SectBubbles.Group.REPLY.pick(-7));
    }
}
