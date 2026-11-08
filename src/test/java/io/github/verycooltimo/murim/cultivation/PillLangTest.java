package io.github.verycooltimo.murim.cultivation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The author plays in English (25.10): every text of the pill game has an English entry, no English entry holds a Russian letter, and the
 * two files agree on the placeholders. The keys are the ones the code asks for.
 */
class PillLangTest {

    private static JsonObject load(String name) throws Exception {
        try (InputStream in = PillLangTest.class.getResourceAsStream("/assets/murim/lang/" + name)) {
            assertTrue(in != null, name + " is not on the classpath");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static final String[] KEYS = {
            "murim.pill.great.tooltip", "murim.pill.breakthrough.ready", "murim.pill.assist.need", "murim.pill.assist.ready",
            "murim.pill.absorbing.auto", "murim.pill.stock.exhausted", "dialogue.murim.pill.assist", "murim.vault.fragments.result",
            "murim.pill.tooltip.full", "murim.pill.tooltip.no_dantian", "item.murim.int_pill_box.count", "murim.pill.warn.full", "murim.pill.preview", "murim.pill.preview.torn", "murim.pill.preview.full",
            "murim.pill.warn", "murim.pill.warn.load", "murim.pill.torn", "murim.pill.torn.load", "murim.pill.result",
            "murim.pill.result.timeout", "murim.pill.result.burst", "murim.pill.standing", "murim.pill.wasted", "murim.pill.tolerance",
            "murim.pill.qi", "murim.pill.rank.torn", "murim.pill.load.torn", "murim.pill.rank.hard", "murim.pill.repeat",
            "murim.pill.standup", "murim.pill.standup.torn", "murim.pill.window", "murim.pill.deviation", "murim.pill.burst", "murim.pill.bump",
            "murim.pill.refuse.same_kind", "murim.pill.refuse.full", "murim.pill.refuse.no_dantian", "murim.pill.refuse.absorbing",
            "murim.flow.keys.hold", "murim.flow.keys.fork", "murim.flow.hold", "murim.flow.blocked", "murim.flow.torn",
            "murim.flow.pressure", "murim.flow.dissolve", "murim.flow.hard"};

    @Test
    @DisplayName("Каждый текст игры «Провести поток» есть в en_us и ru_ru; в английском нет русских букв")
    void everyTextIsInBothLanguages() throws Exception {
        JsonObject en = load("en_us.json");
        JsonObject ru = load("ru_ru.json");
        Pattern cyrillic = Pattern.compile("[\\u0400-\\u04FF]");
        Pattern placeholder = Pattern.compile("%(\\d+\\$)?[sd]");
        List<String> keys = new ArrayList<>(List.of(KEYS));
        keys.add("item.murim.pill_plum_blossom.origin");
        keys.add("dialogue.murim.gyeong_cho.plum_blossom");
        for (PillKind k : PillKind.values()) {
            keys.add("item.murim." + k.itemId());
            if (k.utility()) {
                keys.add("item.murim." + k.itemId() + ".desc");
            } else {
                keys.add("murim.pill.rank.name." + k.rank());
            }
        }
        for (String key : keys) {
            assertTrue(en.has(key), "no English text for " + key);
            assertTrue(ru.has(key), "no Russian text for " + key);
            String e = en.get(key).getAsString();
            Pattern cjk = Pattern.compile("[\\p{IsHan}\\p{IsHangul}\\p{IsHiragana}\\p{IsKatakana}]");
            assertFalse(cjk.matcher(e).find(), "unchecked CJK in " + key);
            assertFalse(cjk.matcher(ru.get(key).getAsString()).find(), "unchecked CJK in " + key);
            assertFalse(cyrillic.matcher(e).find(), "Russian letters in the English text of " + key + ": " + e);
            List<String> pe = new ArrayList<>();
            List<String> pr = new ArrayList<>();
            Matcher m = placeholder.matcher(e);
            while (m.find()) {
                pe.add(m.group());
            }
            m = placeholder.matcher(ru.get(key).getAsString());
            while (m.find()) {
                pr.add(m.group());
            }
            java.util.Collections.sort(pe);
            java.util.Collections.sort(pr);
            assertEquals(pe, pr, "the placeholders of " + key + " differ between the languages");
        }
        // the keys of the removed node game are gone from both files
        for (String gone : new String[] {"murim.pill.node_opened", "murim.pill.hud.bypass", "murim.pill.hud.break", "murim.pill.start"}) {
            assertFalse(en.has(gone) || ru.has(gone), gone + " is still in the lang files");
        }
    }

    @Test
    void plumBlossomBookDataMatchesItsUtilityPillAndLocalizedText() throws Exception {
        try (InputStream in = PillLangTest.class.getResourceAsStream("/data/murim/murim_pills/pill_plum_blossom.json")) {
            assertTrue(in != null, "Plum Blossom compendium entry is absent");
            JsonObject entry = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("murim:" + PillKind.PLUM_BLOSSOM.itemId(), entry.get("item").getAsString());
            assertEquals(PillKind.PLUM_BLOSSOM.qi(), entry.get("qi").getAsInt());
            assertEquals("plum", entry.get("school").getAsString());
            assertEquals(1, entry.getAsJsonObject("source").get("merit").getAsInt());
            for (String locale : List.of("en_us.json", "ru_ru.json")) {
                JsonObject lang = load(locale);
                for (String field : List.of("origin", "effect")) assertTrue(lang.has(entry.get(field).getAsString()), field);
            }
            assertEquals("Plum Blossom Pill", load("en_us.json").get("item.murim.pill_plum_blossom").getAsString());
            assertEquals("Пилюля цветка сливы", load("ru_ru.json").get("item.murim.pill_plum_blossom").getAsString());
        }
    }
}
