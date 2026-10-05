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

    /** Диалог как его видит игра: личный — поверх базы (диалога роли), как в {@link DialogueLoader}. */
    private static Dialogue load(String id) throws IOException {
        Dialogue d = raw(id);
        return d.base().isPresent() ? d.over(raw(d.base().get().getPath())) : d;
    }

    private static Dialogue raw(String id) throws IOException {
        JsonElement json = JsonParser.parseString(Files.readString(res("data/murim/murim_dialogues/" + id + ".json")));
        return Dialogue.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
    }

    private static JsonObject lang(String code) throws IOException {
        return JsonParser.parseString(Files.readString(res("assets/murim/lang/" + code + ".json"))).getAsJsonObject();
    }

    /** Число подстановок {@code %s} в строке. */
    static int placeholders(String s) {
        int n = 0;
        for (int i = s.indexOf("%s"); i >= 0; i = s.indexOf("%s", i + 2)) {
            n++;
        }
        return n;
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
            // Перехват: диалог перехвата есть, узел «занят» есть, положения в условиях — известные.
            d.audience().ifPresent(a -> {
                a.intercept().ifPresent(x -> assertTrue(ids.contains(x.getPath()), id + ": нет диалога перехвата " + x));
                a.busy().ifPresent(b -> assertTrue(d.nodes().containsKey(b), id + ": нет узла " + b));
                assertFalse(a.allow().isEmpty(), id + ": пустой allow — не заговорит никто");
            });
            d.nodes().values().forEach(n -> n.options().forEach(o -> o.when().forEach(c -> {
                c.standing().minStanding().ifPresent(x -> assertTrue(SectStanding.parse(x).isPresent(), id + ": " + x));
                c.standing().belowStanding().ifPresent(x -> assertTrue(SectStanding.parse(x).isPresent(), id + ": " + x));
            })));
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
                // Особые строки (@trait, @personal, @rumour) — не ключи: их реплики проверяются ниже (SectTalk).
                n.random().stream().filter(k -> !k.startsWith("@")).forEach(keys::add);
                n.options().forEach(o -> keys.add(o.text()));
            });
        }
        keys.add("murim.sect.morning.count");
        keys.add("murim.sect.morning.offbeat");
        keys.add("murim.sect.morning.done");
        // Иерархия (С3, часть 2): положение, места, охрана, заслуги.
        for (SectStanding st : SectStanding.values()) {
            keys.add(st.nameKey());
        }
        for (SectAccess.Rule r : SectAccess.RULES) {
            keys.add(r.nameKey());
        }
        for (String k : List.of("warn", "warn_outsider", "block", "night", "challenge", "escort", "expel")) {
            keys.add("murim.sect.guard." + k);
        }
        keys.addAll(List.of("murim.sect.standing.up", "murim.sect.contribution.gain", "murim.sect.contribution.loss", "murim.sect.donate.none"));
        // Личные реплики учеников (SectTalk): черты, слухи, личная фраза каждого ученика.
        keys.addAll(SectTalk.allKeys());
        for (SectRoster m : SectRoster.ALL) {
            if (m.disciple()) {
                keys.add(SectTalk.personalKey(m.key()));
            }
        }
        for (String k : keys) {
            assertTrue(ru.has(k), "ru_ru: " + k);
            assertTrue(en.has(k), "en_us: " + k);
            // Подстановки совпадают: иначе в одном из языков пропадёт имя или число.
            assertEquals(placeholders(en.get(k).getAsString()), placeholders(ru.get(k).getAsString()), "%s в ru/en: " + k);
        }
    }

    @Test
    @DisplayName("Каждый человек горы находит свой диалог (личный или роли), и у личного диалога есть база")
    void everyPersonResolvesDialogue() throws IOException {
        Set<String> ids = new HashSet<>(dialogueIds());
        for (SectRoster m : SectRoster.ALL) {
            String id = ids.contains(m.key()) ? m.key() : m.role().dialogue().getPath();
            assertTrue(ids.contains(id), m.key() + ": нет диалога " + id);
            Dialogue d = load(id);
            assertEquals(List.of(), DialogueService.validate(d), m.key() + " → " + id);
            Dialogue own = raw(id);
            own.base().ifPresent(b -> assertTrue(ids.contains(b.getPath()), id + ": нет базы " + b));
        }
        // Каноничные люди (задача 05.10) говорят своими словами, а не репликами роли.
        for (String key : List.of("baek_cheon", "yu_iseol", "yoon_jong", "jo_gol", "baek_sang", "baek_ho", "hyun_seong")) {
            assertTrue(ids.contains(key), "нет личного диалога " + key);
            assertTrue(raw(key).base().isPresent(), key + ": личный диалог без базы роли");
        }
        // Старейшина по роли — не заглушка из одной реплики.
        assertTrue(raw("elder").nodes().size() >= 4, "elder.json — заглушка");
    }

    @Test
    @DisplayName("Реплики SectTalk: подстановок ровно столько, сколько аргументов")
    void talkArgsMatchLang() throws IOException {
        JsonObject en = lang("en_us");
        JsonObject ru = lang("ru_ru");
        SectTalk.Context[] contexts = {
                new SectTalk.Context("", "Tester", false, false, 3),
                new SectTalk.Context("cheong_jin", "Tester", true, true, 0),
                new SectTalk.Context("@Tester", "Tester", true, false, 6),
                new SectTalk.Context("@Other", "Tester", false, true, 2),
        };
        for (SectRoster m : SectRoster.ALL) {
            for (long day = 0; day < 14; day++) {
                for (SectTalk.Context ctx : contexts) {
                    for (String token : List.of(SectTalk.TRAIT, SectTalk.PERSONAL, SectTalk.RUMOUR)) {
                        for (int roll = 0; roll < 12; roll++) {
                            SectTalk.Line l = SectTalk.line(token, m.key(), day, roll % 2 == 0, roll, ctx);
                            assertTrue(en.has(l.key()), m.key() + ": " + l.key());
                            assertEquals(l.args().size(), placeholders(en.get(l.key()).getAsString()), l.key());
                            assertEquals(l.args().size(), placeholders(ru.get(l.key()).getAsString()), l.key());
                            // Говорящий не пересказывает слух о себе в третьем лице.
                            assertFalse(l.args().contains("npc:" + m.key()), m.key() + " о себе: " + l.key());
                        }
                    }
                }
            }
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
        // Версия 1 (без заслуг) читается с нулём заслуг.
        JsonObject v1 = new JsonObject();
        v1.addProperty("version", 1);
        v1.addProperty("member", true);
        assertEquals(0, SectState.CODEC.parse(JsonOps.INSTANCE, v1).getOrThrow().contribution());
        SectState rich = s.contribute(12);
        assertEquals(12, SectState.CODEC.parse(JsonOps.INSTANCE, SectState.CODEC.encodeStart(JsonOps.INSTANCE, rich).getOrThrow())
                .getOrThrow().contribution());
        assertFalse(back.without("lesson.six").has("lesson.six"));
    }
}
