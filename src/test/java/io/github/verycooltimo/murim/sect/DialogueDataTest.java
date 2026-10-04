package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Диалоги секты как данные и положение игрока в секте: формат, ссылки, ключи ru+en, миграция. */
class DialogueDataTest {

    private static Path res(String rel) {
        Path p = Path.of("src/main/resources/" + rel);
        return Files.exists(p) ? p : Path.of("../../src/main/resources/" + rel);
    }

    private static Dialogue load(String id) throws IOException {
        JsonElement json = JsonParser.parseString(Files.readString(res("data/murim/murim_dialogues/" + id + ".json")));
        return Dialogue.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
    }

    @Test
    @DisplayName("Каждый диалог читается, ссылки узлов целы, не больше 4 видимых ответов на узел")
    void dialoguesParseAndLink() throws IOException {
        for (SectRole role : SectRole.values()) {
            Dialogue d = load(role.dialogue().getPath());
            assertEquals(List.of(), DialogueService.validate(d), role.id());
            // Автор 04.10: на гору игрок поднимается сам — телепорта в диалогах нет.
            d.nodes().values().forEach(n -> n.options().forEach(o -> o.actions().forEach(a ->
                    assertFalse("teleport".equals(a.type()), role.id() + ": teleport"))));
            d.nodes().forEach((id, n) -> {
                // Варианты с взаимоисключающими условиями могут быть больше четырёх, но без условий — не больше.
                long always = n.options().stream().filter(o -> o.when().isEmpty()).count();
                assertTrue(always <= Dialogue.MAX_OPTIONS, role.id() + "/" + id);
            });
        }
    }

    @Test
    @DisplayName("Все строки диалогов есть и в ru_ru, и в en_us")
    void everyKeyTranslated() throws IOException {
        JsonObject ru = JsonParser.parseString(Files.readString(res("assets/murim/lang/ru_ru.json"))).getAsJsonObject();
        JsonObject en = JsonParser.parseString(Files.readString(res("assets/murim/lang/en_us.json"))).getAsJsonObject();
        Set<String> keys = new HashSet<>();
        for (SectRole role : SectRole.values()) {
            keys.add(role.nameKey());
            Dialogue d = load(role.dialogue().getPath());
            if (!d.title().isEmpty()) {
                keys.add(d.title());
            }
            d.nodes().values().forEach(n -> {
                if (!n.line().isEmpty()) {
                    keys.add(n.line());
                }
                keys.addAll(n.random());
                n.options().forEach(o -> keys.add(o.text()));
            });
        }
        for (String k : keys) {
            assertTrue(ru.has(k), "ru_ru: " + k);
            assertTrue(en.has(k), "en_us: " + k);
        }
    }

    @Test
    @DisplayName("Положение в секте: старое сохранение без версии поднимается, флаги переживают сохранение")
    void sectStateCodec() {
        JsonObject old = new JsonObject();
        old.addProperty("member", true);
        SectState migrated = SectState.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();
        assertEquals(SectState.VERSION, migrated.version());
        assertEquals(SectState.CHEON, migrated.generation());

        SectState s = SectState.NONE.with("lesson.six").with("met_mentor").joined();
        JsonElement saved = SectState.CODEC.encodeStart(JsonOps.INSTANCE, s).getOrThrow();
        SectState back = SectState.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(s, back);
        assertTrue(back.has("lesson.six"));
        assertFalse(back.without("lesson.six").has("lesson.six"));
    }
}
