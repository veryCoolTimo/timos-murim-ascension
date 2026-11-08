package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The volume of the sect's own lines (docs/design/39-player-path-v2.md, «Реплики: объём и частота»): every pool the player
 * hears more than about once per sect day has at least {@link SectLines#MIN_VARIANTS} lines in both languages, a pool of a
 * code constant is not longer or shorter than its lang keys, bubbles fit {@link SectBubbles#MAX_CHARS}, and a numbered pool
 * nobody registered cannot slip in under the minimum.
 */
class SectLinePoolsTest {

    private static JsonObject lang(String code) throws IOException {
        Path p = Path.of("src/main/resources/assets/murim/lang/" + code + ".json");
        if (!Files.exists(p)) {
            p = Path.of("../../src/main/resources/assets/murim/lang/" + code + ".json");
        }
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    /** A pool of the code: lang stem, how many lines the code draws from, how many it must have, and whether it is a bubble. */
    record Pool(String stem, int count, int min, boolean bubble) {
    }

    private static final int MIN = SectLines.MIN_VARIANTS;

    /** Every pool the code draws from, by the constants the code itself uses. */
    static Map<String, Pool> declared() {
        Map<String, Pool> out = new LinkedHashMap<>();
        // The traveller has full dialogue pools in both languages, too.
        for (String node : List.of("greet", "who", "point", "bye", "member")) {
            add(out, "dialogue.murim.seo_han." + node, 6, MIN, false);
        }
        // bubbles over the head
        for (SectBubbles.Group g : SectBubbles.Group.values()) {
            add(out, g.stem(), g.count(), MIN, true);
        }
        for (SectTalk.Trait t : SectTalk.Trait.values()) {
            add(out, "murim.bubble.trait." + t.id(), SectBubbles.TRAIT_LINES, MIN, true);
            add(out, SectBubbles.watchStem(t), SectBubbles.WATCH_TRAIT_LINES, MIN, true);
            add(out, "dialogue.murim.trait." + t.id(), SectTalk.TRAIT_LINES, MIN, false);
        }
        for (String g : SectSocial.BUBBLE_GROUPS) {
            add(out, "murim.bubble.social." + g, SectSocial.VARIANTS, MIN, true);
        }
        // the greeting in a conversation: every voice in every situation, and the tail of the time of day
        for (String voice : SectSocial.VOICES) {
            for (String situation : SectSocial.SITUATIONS) {
                add(out, "dialogue.murim.social." + voice + "." + situation, SectSocial.VARIANTS, MIN, false);
            }
        }
        for (String period : List.of("morning", "day", "evening", "night")) {
            add(out, "dialogue.murim.social.time." + period, SectSocial.VARIANTS, MIN, false);
        }
        // walk-up approaches: by kind and voice, and the bubble each one brings
        for (SectApproach.Kind kind : SectApproach.Kind.values()) {
            // TEACHER reuses the lesson/office bubbles, not a new matrix of voice pools.
            if (kind == SectApproach.Kind.TEACHER) continue;
            if (kind == SectApproach.Kind.INVITE) {
                // an invitation of the office has a bubble pool of its own for each event (docs/design/38)
                for (SectOffices.Event e : SectOffices.events()) {
                    add(out, "murim.invite.bubble." + e.id(), SectApproach.VARIANTS, MIN, true);
                }
                continue;
            }
            add(out, "murim.approach.bubble." + kind.id(), SectApproach.VARIANTS, MIN, true);
            if (kind == SectApproach.Kind.HELP || kind == SectApproach.Kind.RUMOUR) {
                continue;
            }
            for (SectVoice.Voice voice : SectVoice.Voice.values()) {
                boolean spar = kind == SectApproach.Kind.SPAR;
                if (spar && !voice.temperament()) {
                    continue;
                }
                add(out, SectApproach.stem(kind, voice, false), SectApproach.VARIANTS, MIN, false);
                if (kind == SectApproach.Kind.GREET) {
                    add(out, SectApproach.stem(kind, voice, true), SectApproach.VARIANTS, MIN, false);
                }
            }
        }
        // the same, said to an elder or a first disciple
        for (String office : List.of("elder", "first")) {
            for (String side : List.of("up", "peer")) {
                List<String> kinds = new ArrayList<>(List.of("greet", "table", "congrats", "console"));
                if (side.equals("up")) kinds.add("spar");
                if (office.equals("first")) kinds.add("formation");
                for (String kind : kinds) {
                    add(out, "murim.approach.office." + office + "." + side + "." + kind, SectApproach.VARIANTS, MIN, false);
                }
            }
        }
        for (SectApproach.Kind kind : List.of(SectApproach.Kind.GREET, SectApproach.Kind.RUMOUR, SectApproach.Kind.SPAR,
                SectApproach.Kind.FORMATION, SectApproach.Kind.TABLE, SectApproach.Kind.CONGRATS, SectApproach.Kind.CONSOLE)) {
            add(out, "murim.approach.bubble.office." + kind.id(), SectApproach.VARIANTS, MIN, true);
        }
        // bouts and contracts
        for (String stem : List.of("murim.bubble.bout.loser", "murim.bubble.bout.respect", "murim.bubble.bout.respect_office",
                "murim.bubble.bout.rumour")) {
            // spoken in a bubble, but longer than the 40 characters of the ambient chatter (they carry names and a verdict)
            add(out, stem, 6, MIN, false);
        }
        add(out, "dialogue.murim.bout.rumour", 6, MIN, false);
        // the mentor's word that a mistake is fixed (SectCorrection): a bubble
        add(out, "murim.bubble.lesson", SectCorrection.LEARNED_VARIANTS, MIN, true);
        // the guardian who takes the first blow of a breakthrough (BreakthroughWorld): a bubble over his head
        add(out, "murim.bubble.breakthrough.guard", io.github.verycooltimo.murim.cultivation.BreakthroughWorld.GUARD_LINES, MIN, true);
        // the escort merchant's words on the way (SectContracts): at the start, at the talking points, at the end
        for (String stem : List.of(SectContracts.ESCORT_START, SectContracts.ESCORT_WAY, SectContracts.ESCORT_END)) {
            add(out, stem, SectLines.MIN_VARIANTS, MIN, true);
        }
        for (String giver : SectContracts.THANKS_GIVERS) {
            add(out, "murim.bubble.contract.done." + giver, SectLines.MIN_VARIANTS, MIN, true);
        }
        add(out, "murim.bubble.contract.done.any", SectLines.MIN_VARIANTS, MIN, true);
        // reactions to events: at least three lines everywhere, six where it can happen several times a day
        for (SectEvents.Event e : SectEvents.Event.values()) {
            if (e == SectEvents.Event.THUNDER) {
                continue;
            }
            for (SectVoice.Register r : SectVoice.Register.values()) {
                add(out, e.pool(r), e.variants(), Math.max(3, e.variants() >= MIN ? MIN : 4), true);
            }
        }
        // the way out of a talk (the player's closing words fit a button, the person's farewell is a bubble), by tone, and the
        // general answers every person gives
        for (String tone : SectGeneralTalk.TONES) {
            add(out, SectGeneralTalk.CLOSE_STEM + tone, SectGeneralTalk.VARIANTS, MIN, true);
            add(out, SectGeneralTalk.BUBBLE_STEM + tone, SectGeneralTalk.VARIANTS, MIN, true);
            add(out, SectGeneralTalk.SELF_STEM + tone, SectGeneralTalk.VARIANTS, MIN, false);
        }
        add(out, SectGeneralTalk.RUMOUR_STEM, SectGeneralTalk.VARIANTS, MIN, false);
        // absence (once per return from a day or more away) is not a pool the player hears daily: four lines are enough
        for (int tier : new int[] {1, 3, 7}) {
            for (SectRank rank : List.of(SectRank.TRUSTED, SectRank.ELDER, SectRank.FIRST)) {
                add(out, SectAbsence.stem(rank, tier), SectAbsence.VARIANTS, 4, false);
            }
        }
        return out;
    }

    private static void add(Map<String, Pool> out, String stem, int count, int min, boolean bubble) {
        Pool previous = out.put(stem, new Pool(stem, count, min, bubble));
        assertTrue(previous == null || previous.count() == count, "two different sizes for " + stem);
    }

    @Test
    @DisplayName("Every pool of the sect's own lines has enough variants, all of them in ru_ru and en_us, none beyond the code's count")
    void everyPoolIsFullInBothLanguages() throws IOException {
        JsonObject ru = lang("ru_ru");
        JsonObject en = lang("en_us");
        Map<String, Pool> pools = declared();
        assertTrue(pools.size() > 250, "too few pools found: " + pools.size());
        for (Pool pool : pools.values()) {
            assertTrue(pool.count() >= pool.min(), pool.stem() + " has " + pool.count() + " lines in the code, needs " + pool.min());
            Set<String> seen = new HashSet<>();
            for (int i = 1; i <= pool.count(); i++) {
                String key = pool.stem() + "." + i;
                if (SectSpeechGuard.RETIRED.contains(key)) {
                    assertFalse(ru.has(key), "retired ru key: " + key);
                    assertFalse(en.has(key), "retired en key: " + key);
                    continue;
                }
                assertTrue(ru.has(key), "ru_ru lacks " + key);
                assertTrue(en.has(key), "en_us lacks " + key);
                String r = ru.get(key).getAsString();
                String e = en.get(key).getAsString();
                assertFalse(r.isBlank() || e.isBlank(), "blank line " + key);
                assertTrue(seen.add(r), "the same Russian line twice in " + pool.stem() + ": " + r);
                assertEquals(DialogueDataTest.placeholders(e), DialogueDataTest.placeholders(r), "%s in ru/en: " + key);
                assertEquals(positional(e), positional(r), "%n$s in ru/en: " + key);
                if (pool.bubble()) {
                    assertTrue(r.length() <= SectBubbles.MAX_CHARS, "bubble too long (ru, " + r.length() + "): " + key + " " + r);
                    assertTrue(e.length() <= SectBubbles.MAX_CHARS, "bubble too long (en, " + e.length() + "): " + key + " " + e);
                }
            }
            assertFalse(ru.has(pool.stem() + "." + (pool.count() + 1)), pool.stem() + " has a line the code does not draw from");
            assertFalse(en.has(pool.stem() + "." + (pool.count() + 1)), pool.stem() + " has an English line the code does not draw from");
        }
    }

    private static final Pattern POSITIONAL = Pattern.compile("%(\\d+)\\$s");

    private static List<String> positional(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = POSITIONAL.matcher(s);
        while (m.find()) {
            out.add(m.group(1));
        }
        out.sort(String::compareTo);
        return out;
    }

    /** Numbered pools that are NOT drawn as a pool of their own, with the reason (the extras are added to a declared pool). */
    private static final List<String> NOT_POOLS = List.of(
            // extras added to the voice pool of the speaker (SectApproach#keys): two lines each
            "murim.approach.greet.person.", "murim.approach.trait.",
            // state-gated, once per request or office offer (dialogue.murim.social.requests / .request.* / .offer.*)
            "dialogue.murim.social.request", "dialogue.murim.social.offer.",
            // the travelling merchant: a rare visitor
            "murim.peddler.",
            // the mentor scolds a missed formation: at most once per day
            "murim.sect.mentor.scold",
            // the tour of a newcomer: once
            "murim.sect.tour.",
            // the lines of a scene of an office event are an ordered script (docs/design/38), not a pool
            "murim.office.");

    @Test
    @DisplayName("A numbered pool of lines that nobody registered cannot be smaller than the minimum")
    void noSmallPoolHidesInTheLangFiles() throws IOException {
        Pattern numbered = Pattern.compile("^(.*)\\.(\\d+)$");
        List<String> watched = List.of("murim.approach.", "murim.bubble.", "murim.react.", "murim.sect.absence.", "dialogue.murim.social.",
                "dialogue.murim.trait.", "dialogue.murim.bout.", "murim.sect.mentor.", "murim.peddler.", "murim.sect.tour.");
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (String key : lang("ru_ru").keySet()) {
            Matcher m = numbered.matcher(key);
            if (m.matches() && watched.stream().anyMatch(key::startsWith)) {
                sizes.merge(m.group(1), 1, Integer::sum);
            }
        }
        Map<String, Pool> declared = declared();
        List<String> loose = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sizes.entrySet()) {
            String stem = e.getKey();
            if (declared.containsKey(stem) || NOT_POOLS.stream().anyMatch(stem::startsWith) || e.getValue() < 2) {
                continue;
            }
            loose.add(stem + " (" + e.getValue() + ")");
        }
        assertTrue(loose.isEmpty(), "numbered pools nobody registered in SectLinesTest#declared: " + loose);
        // the extras of the approaches are two lines each
        for (String stem : sizes.keySet()) {
            if (stem.startsWith("murim.approach.greet.person.") || stem.startsWith("murim.approach.trait.")) {
                assertEquals(2, sizes.get(stem), stem);
            }
        }
    }

    @Test
    @DisplayName("Pools that exist for the data (talk, bubbles) are covered by the existing key lists too")
    void keyListsCoverThePools() {
        List<String> all = new ArrayList<>(SectBubbles.allKeys());
        all.addAll(SectEvents.allKeys());
        for (SectBubbles.Group g : SectBubbles.Group.values()) {
            for (String key : SectLines.keys(g.stem(), g.count())) assertTrue(all.contains(key), key);
        }
        for (SectVoice.Register r : SectVoice.Register.values()) {
            assertTrue(all.contains(SectEvents.Event.BOUT_WON.pool(r) + ".6"), r.id());
        }
    }

    @Test
    @DisplayName("The general questions, their answers for every hour of the day and the closing option exist in both languages")
    void generalQuestionsHaveWords() throws IOException {
        List<String> keys = new ArrayList<>(List.of("dialogue.murim.general.opt.more", "dialogue.murim.general.where.found",
                "dialogue.murim.general.where.at", "dialogue.murim.general.where.none"));
        for (String q : SectGeneralTalk.QUESTIONS) {
            keys.add("dialogue.murim.general.opt." + q);
        }
        for (SectSchedule.Period period : SectSchedule.Period.values()) {
            keys.add("dialogue.murim.general.today." + period.id());
        }
        for (String lang : List.of("ru_ru", "en_us")) {
            JsonObject json = lang(lang);
            for (String key : keys) {
                assertTrue(json.has(key), lang + " lacks " + key);
            }
        }
        assertTrue(SectGeneralTalk.QUESTIONS.size() >= 4 && SectGeneralTalk.TONES.size() == 2);
    }
}
