package io.github.verycooltimo.murim.technique;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.mastery.Arts;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Names of arts, forms and methods (author 19.10: «давай по канону всё называть»,
 * docs/design/research/canon-names-1910.md). Every technique, art, style and method has a name in both languages,
 * a Russian name is Russian and an English name is English, and the names retired in favour of the canon ones
 * do not come back into any player-visible text.
 */
class TechniqueNamesTest {
    @Test
    void fortressNotebookContainsCompleteRiteInBothLanguages() throws Exception {
        for (String locale : List.of("en_us", "ru_ru")) {
            var text = lang(locale);
            assertTrue(text.get("murim.clue.fortress_page.text").getAsString()
                    .contains(text.get("murim.clue.vault_verse.text").getAsString()), locale);
        }
    }

    @Test
    void bookInstructionsAcceptReboundKeysInBothLanguages() throws Exception {
        for (String locale : List.of("en_us", "ru_ru")) {
            var text = lang(locale);
            for (String key : List.of("book.murim.style.seven_stars", "book.murim.seven_stars_shift",
                    "book.murim.seven_stars_follow", "book.murim.seven_stars_dipper",
                    "book.murim.twenty_four_plum_full_bloom.full", "technique.murim.bamboo_leaf_hand.description")) {
                String template = text.get(key).getAsString();
                String bound = String.format(template, "Mouse 4", "Mouse 5", "L");
                assertTrue(bound.contains("Mouse 4"), locale + " " + key);
                assertTrue(!bound.contains("%1$s") && !bound.contains("%%"), key);
                assertTrue(!template.matches("(?s).*\\b[RVK]\\b.*"), key);
                if (key.equals("book.murim.style.seven_stars")) {
                    assertTrue(bound.contains("Mouse 5") && bound.contains("L"), locale);
                }
            }
        }
    }

    @Test
    void elixirProvenanceStaysWithinCanonScope() throws Exception {
        Path source = root().resolve("src/main/java/io/github/verycooltimo/murim/cultivation");
        for (String file : List.of("PillKind.java", "GreatElixirs.java")) {
            String text = Files.readString(source.resolve(file));
            assertTrue(text.contains("original game addition"), file);
            assertTrue(!text.contains("Absolute Regression ch. 642") && !text.contains("Absolute Regression 433"), file);
        }
    }

    /** Project root: the test working directory may be a run folder, so walk up to the folder holding src/main. */
    private static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("src/main/resources/data/murim"))) {
            p = p.getParent();
        }
        assertNotNull(p, "project root not found");
        return p;
    }

    private static JsonObject lang(String code) throws Exception {
        try (InputStream in = TechniqueNamesTest.class.getResourceAsStream("/assets/murim/lang/" + code + ".json")) {
            assertNotNull(in, code);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static List<String> ids(String folder) {
        File[] files = root().resolve("src/main/resources/data/murim/" + folder).toFile().listFiles((d, n) -> n.endsWith(".json"));
        assertNotNull(files, folder);
        List<String> out = new ArrayList<>();
        for (File f : files) {
            out.add(f.getName().replace(".json", ""));
        }
        assertTrue(!out.isEmpty(), folder + " is empty");
        return out;
    }

    private static boolean hasCyrillic(String s) {
        return s.chars().anyMatch(c -> c >= 0x0400 && c <= 0x04FF);
    }

    private static void name(JsonObject en, JsonObject ru, String key, List<String> problems) {
        for (var entry : Map.of("en_us", en, "ru_ru", ru).entrySet()) {
            JsonObject lines = entry.getValue();
            if (!lines.has(key) || lines.get(key).getAsString().isBlank()) {
                problems.add(entry.getKey() + ": no name " + key);
            }
        }
        if (en.has(key) && hasCyrillic(en.get(key).getAsString())) {
            problems.add("en_us: Cyrillic in the English name " + key);
        }
        if (ru.has(key) && !hasCyrillic(ru.get(key).getAsString())) {
            problems.add("ru_ru: the Russian name has no Cyrillic " + key + " = " + ru.get(key).getAsString());
        }
    }

    @Test
    @DisplayName("The two language files hold exactly the same keys")
    void sameKeysInBothLanguages() throws Exception {
        Set<String> en = lang("en_us").keySet();
        Set<String> ru = lang("ru_ru").keySet();
        List<String> problems = new ArrayList<>();
        for (String k : en) {
            if (!ru.contains(k)) {
                problems.add("only in en_us: " + k);
            }
        }
        for (String k : ru) {
            if (!en.contains(k)) {
                problems.add("only in ru_ru: " + k);
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    @DisplayName("Every technique has a name and an art name in English and Russian")
    void everyTechniqueHasBothNames() throws Exception {
        JsonObject en = lang("en_us");
        JsonObject ru = lang("ru_ru");
        List<String> problems = new ArrayList<>();
        for (String id : ids("murim_techniques")) {
            name(en, ru, "technique.murim." + id, problems);
            ResourceLocation art = Arts.masteryId(ResourceLocation.fromNamespaceAndPath("murim", id));
            name(en, ru, Arts.nameKey(art), problems);
        }
        assertEquals(List.of(), problems);
    }

    @Test
    @DisplayName("Every cultivation method has a name in English and Russian")
    void everyMethodHasBothNames() throws Exception {
        JsonObject en = lang("en_us");
        JsonObject ru = lang("ru_ru");
        List<String> problems = new ArrayList<>();
        for (String id : ids("murim_methods")) {
            name(en, ru, "method.murim." + id, problems);
        }
        assertEquals(List.of(), problems);
    }

    @Test
    @DisplayName("Method scroll and book speed ranges match the datapack in both languages")
    void methodDescriptionsUseDataSpeedRanges() throws Exception {
        for (String code : List.of("en_us", "ru_ru")) {
            JsonObject lines = lang(code);
            for (String id : ids("murim_methods")) {
                try (var reader = Files.newBufferedReader(
                        root().resolve("src/main/resources/data/murim/murim_methods/" + id + ".json"),
                        StandardCharsets.UTF_8)) {
                    var speed = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("speed");
                    String start = new java.math.BigDecimal(speed.get(0).getAsString()).stripTrailingZeros().toPlainString();
                    String end = new java.math.BigDecimal(speed.get(1).getAsString()).stripTrailingZeros().toPlainString();
                    String expected = code.equals("en_us")
                            ? "Cultivation speed ×" + start + " → ×" + end + ", grows with stars"
                            : "Скорость культивации ×" + start + " → ×" + end + ", растёт со звёздами";
                    assertTrue(lines.get("method.murim." + id + ".cost").getAsString().contains(expected),
                            code + ": incorrect speed description for " + id);
                }
            }
        }
    }

    @Test
    @DisplayName("Every style has a name, and the forms of the book styles have short form names")
    void everyStyleAndFormHasBothNames() throws Exception {
        JsonObject en = lang("en_us");
        JsonObject ru = lang("ru_ru");
        Set<String> bookStyles = Set.of("seven_plum", "twenty_four_plum", "dark_fragrance", "wind_god", "tang_daggers");
        List<String> problems = new ArrayList<>();
        for (Styles.Style style : Styles.ALL) {
            name(en, ru, style.nameKey(), problems);
            if (bookStyles.contains(style.id().getPath())) {
                for (ResourceLocation form : style.forms()) {
                    name(en, ru, "form.murim." + form.getPath(), problems);
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    /** Names that were replaced by canon ones (canon-names-1910.md); none of them may stay in a player-visible line. */
    private static final List<String> RETIRED_EN = List.of("Six Harmonies", "Six Equilibrium", "Six Balances", "Falling Flower",
            "Wind God Steps", "God Wind", "Netherworld King", "Five Successive Lightning", "Life-Taker", "Soul-Stealing Seven Stars",
            "Hidden Explosive Dagger", "Lightning Flash · Blink", "Star Light · Run", "Lightning Step");
    private static final List<String> RETIRED_RU = List.of("Шесть Равновесий", "Шесть Гармоний", "Шести Гармоний", "Шести гармоний",
            "Метод Шести Взаимодействий", "Шаги Бога Ветров", "Шагов Бога Ветров", "Шаги Плавающего", "Шагов Плавающего",
            "Двенадцать Летящих Кинжалов", "Двенадцати Летящих Кинжалов", "Бог Ветра:", "Вспышка молнии · Миг",
            "Король преисподней · Наступление", "Звёздный свет · Бег", "Шаг молнии", "Шаг Молнии");

    @Test
    @DisplayName("Names retired for canon ones do not come back")
    void retiredNamesStayGone() throws Exception {
        List<String> problems = new ArrayList<>();
        for (var entry : Map.of("en_us", RETIRED_EN, "ru_ru", RETIRED_RU).entrySet()) {
            JsonObject lines = lang(entry.getKey());
            for (String key : lines.keySet()) {
                String text = lines.get(key).getAsString();
                for (String retired : entry.getValue()) {
                    if (text.contains(retired)) {
                        problems.add(entry.getKey() + " " + key + ": «" + retired + "»");
                    }
                }
            }
        }
        assertEquals(List.of(), problems);
    }
}
