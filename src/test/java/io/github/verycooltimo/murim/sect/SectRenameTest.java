package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Переименование людей секты 06.10 (docs/design/31-originality.md): старые миры без дублей и сирот. */
class SectRenameTest {

    @Test
    @DisplayName("Каждый прежний ключ ведёт к живому человеку списка, двое прежних не сливаются в одного")
    void everyOldKeyHasOnePerson() {
        Set<String> targets = new HashSet<>();
        for (Map.Entry<String, String> e : SectRoster.RENAMED.entrySet()) {
            assertTrue(SectRoster.of(e.getValue()).isPresent(), e.getKey() + " → " + e.getValue() + ": нет в списке");
            assertTrue(SectRoster.of(e.getKey()).isEmpty(), e.getKey() + ": прежний ключ остался в списке");
            assertFalse(SectRoster.RETIRED.contains(e.getKey()), e.getKey() + ": и переименован, и ушёл");
            assertTrue(targets.add(e.getValue()), e.getValue() + ": двое прежних стали одним");
            assertEquals(e.getKey(), SectRoster.seed(e.getValue()), "черта считается от прежнего ключа");
        }
        for (String look : SectRoster.RENAMED_LOOKS.values()) {
            assertTrue(SectRoster.ALL.stream().anyMatch(m -> m.look().equals(look)), look);
        }
        assertEquals("tae_hwi", SectRoster.legacy(SectRole.LEADER).orElseThrow().key());
        assertEquals("gyeong_pil", SectRoster.legacy(SectRole.MENTOR).orElseThrow().key());
        assertEquals("seo_rang", SectRoster.legacy(SectRole.SENIOR).orElseThrow().key());
    }

    @Test
    @DisplayName("Флаги старого сохранения (просьбы, вызовы) переходят на новые ключи")
    void oldFlagsMigrate() {
        JsonObject old = new JsonObject();
        old.addProperty("version", 3);
        old.addProperty("member", true);
        old.addProperty("generation", 3);
        JsonArray flags = new JsonArray();
        flags.add("favour.jo_gol");
        flags.add("summon.hyun_sang");
        flags.add("met_mentor");
        old.add("flags", flags);
        SectState s = SectState.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();
        assertEquals(SectState.VERSION, s.version());
        assertEquals(Set.of("favour.bok_manseok", "summon.tae_rok", "met_mentor"), s.flags());
        assertEquals("favour_done", SectRoster.renamedFlag("favour_done"));
    }
}
