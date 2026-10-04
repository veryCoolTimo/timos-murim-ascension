package io.github.verycooltimo.murim.library;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The junk-book factory: same seed — same book, both languages carry every key it can produce (docs/design/25 §2). */
class JunkTextTest {

    private static JsonObject lang(String name) throws IOException {
        // Gradle runs unit tests from build/ subfolders; DialogueDataTest uses the same fallback.
        Path p = Path.of("src/main/resources/assets/murim/lang/" + name + ".json");
        if (!Files.exists(p)) {
            p = Path.of("../../src/main/resources/assets/murim/lang/" + name + ".json");
        }
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void keys(Object o, Set<String> out) {
        if (o instanceof Component c) {
            if (c.getContents() instanceof TranslatableContents t) {
                out.add(t.getKey());
                for (Object arg : t.getArgs()) {
                    keys(arg, out);
                }
            }
            for (Component sibling : c.getSiblings()) {
                keys(sibling, out);
            }
        }
    }

    @Test
    @DisplayName("Один и тот же сид даёт одну и ту же книгу; разные сиды — разные названия")
    void deterministic() {
        for (JunkKind kind : JunkKind.values()) {
            Set<String> titles = new HashSet<>();
            for (long seed = 0; seed < 300; seed++) {
                JunkBook book = new JunkBook(kind, seed * 7919L + 13L, kind == JunkKind.CLUE ? 19 : -1, false);
                JunkText.Text a = JunkText.generate(book);
                JunkText.Text b = JunkText.generate(new JunkBook(kind, book.seed(), book.hint(), true));
                assertEquals(a.title(), b.title(), kind + " title, seed " + seed);
                assertEquals(a.paras(), b.paras(), kind + " text, seed " + seed);
                titles.add(a.title().toString() + a.paras());
            }
            assertTrue(titles.size() >= 10, kind + ": only " + titles.size() + " different books in 300");
        }
        assertNotEquals(JunkText.title(new JunkBook(JunkKind.FAKE_GRAND, 1L)), JunkText.title(new JunkBook(JunkKind.FAKE_GRAND, 2L)));
    }

    @Test
    @DisplayName("Каждый ключ, который может выдать фабрика, есть и в ru_ru, и в en_us; плейсхолдеры совпадают")
    void langCoverage() throws IOException {
        JsonObject ru = lang("ru_ru");
        JsonObject en = lang("en_us");
        Set<String> used = new TreeSet<>();
        for (JunkKind kind : JunkKind.values()) {
            for (long seed = 0; seed < 3000; seed++) {
                for (int hint = -1; hint <= 20; hint += hint < 0 ? 13 : 1) {
                    JunkText.Text text = JunkText.generate(new JunkBook(kind, seed * 104729L + kind.ordinal(), hint, false));
                    keys(text.title(), used);
                    for (JunkText.Para p : text.paras()) {
                        keys(p.text(), used);
                    }
                }
            }
        }
        for (String key : used) {
            assertTrue(ru.has(key), "ru_ru lacks " + key);
            assertTrue(en.has(key), "en_us lacks " + key);
        }
        // Every list entry is reachable and every junk key exists in both files.
        for (Map.Entry<String, Integer> list : JunkLexicon.SIZES.entrySet()) {
            String base = JunkLexicon.PREFIX + list.getKey() + ".";
            for (int i = 0; i < list.getValue(); i++) {
                String plain = base + i;
                String prefix = plain + ".";
                boolean seen = used.contains(plain) || used.stream().anyMatch(k -> k.startsWith(prefix));
                assertTrue(seen || list.getKey().startsWith("t.noun"), "never produced: " + plain);
            }
        }
        Set<String> ruJunk = new TreeSet<>();
        Set<String> enJunk = new TreeSet<>();
        ru.keySet().stream().filter(k -> k.startsWith(JunkLexicon.PREFIX)).forEach(ruJunk::add);
        en.keySet().stream().filter(k -> k.startsWith(JunkLexicon.PREFIX)).forEach(enJunk::add);
        assertEquals(ruJunk, enJunk, "junk keys differ between ru_ru and en_us");
        Pattern ph = Pattern.compile("%(\\d)\\$s");
        for (String k : ruJunk) {
            assertEquals(placeholders(ph, ru.get(k).getAsString()), placeholders(ph, en.get(k).getAsString()), "placeholders of " + k);
        }
        for (String item : new String[]{"item.murim.junk_manual", "item.murim.junk_manual_blue", "item.murim.junk_manual_red",
                "item.murim.junk_letters", "block.murim.propped_shelf"}) {
            assertTrue(ru.has(item) && en.has(item), item);
        }
    }

    private static Set<String> placeholders(Pattern p, String s) {
        Set<String> out = new TreeSet<>();
        Matcher m = p.matcher(s);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    @DisplayName("Размокший текст детерминирован, длина и пробелы сохраняются, часть слов читается")
    void smudge() {
        String text = "The point follows the breath. Take a small step left, lower the shoulder and bring the blade upward";
        String a = JunkText.smudge(text, 42L);
        assertEquals(a, JunkText.smudge(text, 42L));
        assertEquals(text.length(), a.length());
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == ' ') {
                assertEquals(' ', a.charAt(i));
            }
        }
        long kept = 0;
        for (int i = 0; i < text.length(); i++) {
            kept += text.charAt(i) != ' ' && text.charAt(i) == a.charAt(i) ? 1 : 0;
        }
        assertTrue(kept > 5 && kept < text.replace(" ", "").length() * 0.8, "kept " + kept);
    }

    @Test
    @DisplayName("Клоны: обложка следует за сидом, письма — всегда связка")
    void covers() {
        int[] counts = new int[3];
        for (long seed = 0; seed < 2000; seed++) {
            counts[JunkFactory.Cover.of(seed * 2654435761L).ordinal()]++;
        }
        assertTrue(counts[0] > counts[1] && counts[1] > counts[2] && counts[2] > 100, java.util.Arrays.toString(counts));
        assertTrue(JunkKind.LOVE.letters());
        for (JunkKind k : JunkKind.values()) {
            assertEquals(k == JunkKind.LOVE, k.letters());
        }
    }
}
