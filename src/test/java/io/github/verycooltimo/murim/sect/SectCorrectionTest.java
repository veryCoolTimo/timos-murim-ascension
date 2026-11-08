package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The master's correction (Track C, docs/design/research/hua-main-1810.md section 6, T2): the rules and the words. */
class SectCorrectionTest {

    /** A log of a quiet, clean round: nothing to name. */
    private static SectCorrection.Log clean() {
        return new SectCorrection.Log(1, 4, 3.0D, 0.9D, 0.8D, 6, 2, 3, 0, 0, 0, 400L);
    }

    private static SectCorrection.Log with(java.util.function.UnaryOperator<LogBuilder> f) {
        return f.apply(new LogBuilder()).build();
    }

    /** A mutable view of a log for the cases. */
    private static final class LogBuilder {
        int taken = 1, dealt = 4, swings = 6, most = 2, unlocked = 3, windup, punished, out;
        double mean = 3.0D, qiStart = 0.9D, qiLow = 0.8D;
        long fight = 400L;

        SectCorrection.Log build() {
            return new SectCorrection.Log(taken, dealt, mean, qiStart, qiLow, swings, most, unlocked, windup, punished, out, fight);
        }
    }

    @Test
    @DisplayName("A clean round names nothing, and a real round counts as substantial")
    void aCleanRoundNamesNothing() {
        assertNull(SectCorrection.pick(clean()));
        assertTrue(SectCorrection.substantial(clean()));
        assertFalse(SectCorrection.substantial(new SectCorrection.Log(0, 1, 3.0D, 1.0D, 1.0D, 1, 1, 3, 0, 0, 0, 40L)),
                "two seconds and one blow is not a round");
    }

    @Test
    @DisplayName("Every mistake is named from its own signs in the log")
    void everyMistakeHasItsSigns() {
        assertEquals(SectCorrection.Mistake.WINDUP_HIT, SectCorrection.pick(with(b -> {
            b.windup = 2;
            return b;
        })));
        assertEquals(SectCorrection.Mistake.PUNISHED, SectCorrection.pick(with(b -> {
            b.punished = 2;
            return b;
        })));
        assertEquals(SectCorrection.Mistake.WHIFF, SectCorrection.pick(with(b -> {
            b.swings = 10;
            b.dealt = 3;
            b.most = 4;
            return b;
        })));
        assertEquals(SectCorrection.Mistake.ONE_FORM, SectCorrection.pick(with(b -> {
            b.swings = 8;
            b.most = 8;
            b.dealt = 6;
            return b;
        })));
        assertEquals(SectCorrection.Mistake.EDGE, SectCorrection.pick(with(b -> {
            b.out = 30;
            return b;
        })));
        assertEquals(SectCorrection.Mistake.QI, SectCorrection.pick(with(b -> {
            b.qiLow = 0.05D;
            return b;
        })));
        assertEquals(SectCorrection.Mistake.CLOSE, SectCorrection.pick(with(b -> {
            b.mean = 1.0D;
            b.taken = 3;
            b.dealt = 3;
            return b;
        })));
        assertEquals(SectCorrection.Mistake.HITS, SectCorrection.pick(with(b -> {
            b.taken = 6;
            b.dealt = 2;
            b.swings = 2;
            return b;
        })));
        assertEquals(SectCorrection.Mistake.FAR, SectCorrection.pick(with(b -> {
            b.mean = 6.0D;
            b.dealt = 1;
            b.taken = 0;
            b.swings = 2;
            return b;
        })));
        assertEquals(SectCorrection.Mistake.PASSIVE, SectCorrection.pick(with(b -> {
            b.dealt = 0;
            b.taken = 1;
            b.swings = 1;
            return b;
        })));
    }

    @Test
    @DisplayName("Only one mistake is named, the most instructive first")
    void oneMistakeTheMostInstructiveFirst() {
        SectCorrection.Log many = with(b -> {
            b.windup = 3;
            b.punished = 3;
            b.out = 60;
            b.qiLow = 0.01D;
            b.taken = 8;
            b.dealt = 1;
            return b;
        });
        assertEquals(SectCorrection.Mistake.WINDUP_HIT, SectCorrection.pick(many));
        // the order of the enum is the order of naming
        SectCorrection.Mistake[] order = SectCorrection.Mistake.values();
        assertEquals(SectCorrection.Mistake.WINDUP_HIT, order[0]);
        assertEquals(SectCorrection.Mistake.PASSIVE, order[order.length - 1]);
    }

    @Test
    @DisplayName("A form cannot be a monotony when the player has only one form open")
    void oneFormNeedsOtherFormsOpen() {
        SectCorrection.Log log = with(b -> {
            b.swings = 8;
            b.most = 8;
            b.unlocked = 1;
            b.dealt = 6;
            return b;
        });
        assertFalse(SectCorrection.present(SectCorrection.Mistake.ONE_FORM, log));
    }

    @Test
    @DisplayName("A mistake is fixed in a real round where it is not to be found; a thin round fixes nothing")
    void fixedNeedsARealRoundWithoutTheMistake() {
        SectCorrection.Log again = with(b -> {
            b.windup = 2;
            return b;
        });
        assertFalse(SectCorrection.fixed(SectCorrection.Mistake.WINDUP_HIT, again), "the same mistake again");
        assertTrue(SectCorrection.fixed(SectCorrection.Mistake.WINDUP_HIT, clean()));
        assertFalse(SectCorrection.fixed(SectCorrection.Mistake.WINDUP_HIT, new SectCorrection.Log(0, 0, 3.0D, 1.0D, 1.0D, 0, 0, 3, 0, 0, 0, 20L)),
                "a round of one second fixes nothing");
        assertFalse(SectCorrection.fixed(null, clean()), "nothing was named");
    }

    @Test
    @DisplayName("The journal value of a mistake is its place plus one, and only valid values are read back")
    void journalValues() {
        for (SectCorrection.Mistake m : SectCorrection.Mistake.values()) {
            assertEquals(m, SectCorrection.Mistake.at(m.ordinal() + 1L));
        }
        assertNull(SectCorrection.Mistake.at(0));
        assertNull(SectCorrection.Mistake.at(SectCorrection.Mistake.values().length + 1L));
        assertNull(SectCorrection.Mistake.at(-3));
    }

    @Test
    @DisplayName("The lesson is ten minutes at one and a half")
    void lessonNumbers() {
        assertEquals(12000, SectCorrection.LESSON_TICKS, "ten minutes");
        assertEquals(1.5D, SectCorrection.LESSON_FACTOR, 0.0D);
    }

    // ------------------------------------------------------------------ the words

    private static JsonObject lang(String code) throws IOException {
        Path p = Path.of("src/main/resources/assets/murim/lang/" + code + ".json");
        if (!Files.exists(p)) {
            p = Path.of("../../src/main/resources/assets/murim/lang/" + code + ".json");
        }
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    @Test
    @DisplayName("At least six variants of the correction, each in the mentor's and in the peer's voice, in both languages and short enough for a bubble")
    void everyMistakeHasItsWordsInBothLanguagesAndBothVoices() throws IOException {
        assertTrue(SectCorrection.Mistake.values().length >= SectLines.MIN_VARIANTS, "fewer than six mistakes to name");
        JsonObject ru = lang("ru_ru");
        JsonObject en = lang("en_us");
        for (SectCorrection.Mistake m : SectCorrection.Mistake.values()) {
            for (String voice : new String[] {"say", "peer"}) {
                String key = "murim.correction." + voice + "." + m.id();
                assertTrue(ru.has(key) && en.has(key), "missing " + key);
                String r = ru.get(key).getAsString();
                String e = en.get(key).getAsString();
                assertFalse(r.isBlank() || e.isBlank(), key);
                assertTrue(r.length() <= 52 && e.length() <= 52, "too long for one bubble: " + key + " (" + r.length() + ", " + e.length() + ")");
                assertEquals(DialogueDataTest.placeholders(e), DialogueDataTest.placeholders(r), "placeholders differ in " + key);
            }
        }
        assertNotNull(ru.get("murim.correction.learned"));
        assertNotNull(en.get("murim.correction.said"));
    }
}
