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

    /** Все файлы диалогов: по ролям и свои у людей секты (murim_dialogues/<ключ>.json). */
    private static List<String> dialogueIds() throws IOException {
        Path dir = res("data/murim/murim_dialogues");
        try (var files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5)).sorted().toList();
        }
    }

    @Test
    @DisplayName("Каждый диалог читается, ссылки узлов целы, не больше 4 видимых ответов на узел")
    void dialoguesParseAndLink() throws IOException {
        Set<String> ids = new HashSet<>(dialogueIds());
        for (SectRole role : SectRole.values()) {
            assertTrue(ids.contains(role.dialogue().getPath()), "нет диалога роли " + role.id());
        }
        for (String id : ids) {
            Dialogue d = load(id);
            assertEquals(List.of(), DialogueService.validate(d), id);
            // Автор 04.10: на гору игрок поднимается сам — телепорта в диалогах нет.
            d.nodes().values().forEach(n -> n.options().forEach(o -> o.actions().forEach(a ->
                    assertFalse("teleport".equals(a.type()), id + ": teleport"))));
            d.nodes().forEach((nid, n) -> {
                // Варианты с взаимоисключающими условиями могут быть больше четырёх, но без условий — не больше.
                long always = n.options().stream().filter(o -> o.when().isEmpty()).count();
                assertTrue(always <= Dialogue.MAX_OPTIONS, id + "/" + nid);
            });
            // Условие части суток — только известные части распорядка.
            d.start().forEach(e -> e.when().forEach(c -> c.period().ifPresent(p -> {
                for (String one : p.split("\\|")) {
                    assertTrue(SectSchedule.Period.of(one).isPresent(), id + ": период " + one);
                }
            })));
        }
    }

    @Test
    @DisplayName("Все строки диалогов и имена людей секты есть и в ru_ru, и в en_us")
    void everyKeyTranslated() throws IOException {
        JsonObject ru = JsonParser.parseString(Files.readString(res("assets/murim/lang/ru_ru.json"))).getAsJsonObject();
        JsonObject en = JsonParser.parseString(Files.readString(res("assets/murim/lang/en_us.json"))).getAsJsonObject();
        Set<String> keys = new HashSet<>();
        for (SectRole role : SectRole.values()) {
            keys.add(role.nameKey());
        }
        for (SectRoster m : SectRoster.ALL) {
            keys.add(m.nameKey());
        }
        for (String id : dialogueIds()) {
            Dialogue d = load(id);
            keys.add(d.name());
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
        keys.add("murim.sect.morning.count");
        keys.add("murim.sect.morning.offbeat");
        keys.add("murim.sect.morning.done");
        for (String k : keys) {
            assertTrue(ru.has(k), "ru_ru: " + k);
            assertTrue(en.has(k), "en_us: " + k);
        }
    }

    @Test
    @DisplayName("У каждого человека секты есть текстура облика")
    void everyLookExists() {
        for (SectRoster m : SectRoster.ALL) {
            assertTrue(Files.exists(res("assets/murim/textures/entity/sect/" + m.look() + ".png")), m.key() + ": " + m.look());
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
