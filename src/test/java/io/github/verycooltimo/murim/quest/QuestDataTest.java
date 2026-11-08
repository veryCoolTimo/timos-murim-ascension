package io.github.verycooltimo.murim.quest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.quest.def.QuestDef;
import io.github.verycooltimo.murim.npc.NpcProfile;
import io.github.verycooltimo.murim.sect.Dialogue;
import io.github.verycooltimo.murim.sect.SectRoster;
import io.github.verycooltimo.murim.sect.SectSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The validator of the quest constructor (docs/design/45-quest-format.md): every quest file parses with the codec, ids are
 * unique, and every reference resolves - people of the sect, dialogue nodes, lang keys in both languages (bubbles at most 40
 * characters), periods, block kinds, handlers, clues, markers. Items are checked in the GameTest {@code questDataResolves}.
 */
class QuestDataTest {

    static final Set<String> KINDS = Set.of("paper", "carved", "lid", "box", "mark", "target");
    static final Set<String> HANDLERS = Set.of("strike_start", "strike_target", "sneak");
    static final Set<String> MARKERS = Set.of("dry_stream_cave", "stele_ledge", "dry_stream_camp", "east_scene", "east_shelter", "gate_cart",
            "gate_stall", "debt_yard", "store_shelf", "parcel_shed");
    static final Set<String> MODELS = Set.of("scrap", "note", "rubbing", "fragment");

    private static Path res(String rel) {
        Path p = Path.of("src/main/resources/" + rel);
        return Files.exists(p) ? p : Path.of("../../src/main/resources/" + rel);
    }

    private static JsonObject lang(String code) throws IOException {
        return JsonParser.parseString(Files.readString(res("assets/murim/lang/" + code + ".json"))).getAsJsonObject();
    }

    static List<QuestDef> load() throws IOException {
        List<QuestDef> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(res("data/murim/murim_quests"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
                QuestDef q = QuestDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(Files.readString(f)))
                        .getOrThrow(e -> new AssertionError(f.getFileName() + ": " + e));
                assertEquals(f.getFileName().toString().replace(".json", ""), q.id(), "file name and id");
                out.add(q);
            }
        }
        return out;
    }

    @Test
    @DisplayName("Every quest file parses, step ids are unique, and every reference resolves")
    void everyQuestResolves() throws IOException {
        JsonObject en = lang("en_us"), ru = lang("ru_ru");
        List<QuestDef> quests = load();
        assertTrue(quests.size() >= 7, "quests: " + quests.size());
        Set<String> seen = new HashSet<>();
        Set<String> clues = new HashSet<>();
        quests.forEach(q -> q.steps().stream().filter(s -> s instanceof QuestDef.ClueStep).forEach(s -> clues.add(s.id())));
        for (QuestDef q : quests) {
            assertTrue(en.has(q.title()) && ru.has(q.title()), q.id() + ": title key " + q.title());
            for (QuestDef.Step s : q.steps()) {
                assertTrue(seen.add(s.type() + ":" + s.id()), "step defined twice: " + s.type() + " " + s.id());
                if (s instanceof QuestDef.Overheard o) {
                    assertTrue(SectRoster.of(o.speaker()).isPresent(), o.id() + ": no person " + o.speaker());
                    o.periods().forEach(p -> assertTrue(SectSchedule.Period.of(p).isPresent(), o.id() + ": period " + p));
                    for (JsonObject l : List.of(en, ru)) {
                        assertTrue(l.has(o.key()), "bubble text " + o.key());
                        assertTrue(l.get(o.key()).getAsString().length() <= 40, "bubble over 40 characters: " + o.key());
                    }
                    flags(o.gate());
                    assertTrue(o.repeats() >= 1 && o.repeatSeconds() > 0 && o.windowSeconds() > 0);
                    assertTrue(en.has("murim.quest.rumour." + o.id()) && ru.has("murim.quest.rumour." + o.id()), "rumour note " + o.id());
                } else if (s instanceof QuestDef.ClueStep c) {
                    assertTrue(MODELS.contains(c.model()), c.id() + ": model " + c.model());
                    for (JsonObject l : List.of(en, ru)) {
                        assertTrue(l.has("murim.clue." + c.id() + ".title") && l.has("murim.clue." + c.id() + ".text"), "clue text " + c.id());
                    }
                } else if (s instanceof QuestDef.Inspect i) {
                    assertTrue(KINDS.contains(i.kind()), i.id() + ": kind " + i.kind());
                    i.handler().ifPresent(h -> assertTrue(HANDLERS.contains(h), i.id() + ": handler " + h));
                    String text = "murim.spot." + i.id();
                    for (JsonObject l : List.of(en, ru)) {
                        assertTrue(l.has(text), "spot text " + text);
                        if (i.reward().isPresent()) assertTrue(l.has(text + ".empty"), "spot text " + text + ".empty");
                    }
                    i.grant().ifPresent(g -> assertTrue(clues.contains(g), i.id() + ": unknown clue " + g));
                    i.reward().ifPresent(r -> r.clues().forEach(g -> assertTrue(clues.contains(g), i.id() + ": unknown reward clue " + g)));
                    i.reward().ifPresent(r -> r.items().forEach(it -> assertTrue(it.matches("[a-z_]+:[a-z0-9_/]+(\\*\\d+)?"), "item spec " + it)));
                    assertTrue(i.copyTicks() >= 0);
                } else if (s instanceof QuestDef.Reach r) {
                    assertTrue(MARKERS.contains(r.marker()), r.id() + ": marker " + r.marker());
                    assertTrue(r.radius() > 0 && r.radius() <= 64);
                    flags(r.gate());
                } else if (s instanceof QuestDef.Talk t) {
                    person(t.id(), t.npc(), t.node());
                    flags(t.requires());
                } else if (s instanceof QuestDef.HandIn h) {
                    person(h.id(), h.npc(), h.node());
                    flags(h.requires());
                    h.takeItems().forEach(it -> assertTrue(it.matches("[a-z_]+:[a-z0-9_/]+(\\*\\d+)?"), "item spec " + it));
                    h.takeClues().forEach(c -> assertTrue(clues.contains(c), h.id() + ": unknown clue " + c));
                    h.reward().ifPresent(r -> {
                        r.items().forEach(it -> assertTrue(it.matches("[a-z_]+:[a-z0-9_/]+(\\*\\d+)?"), "item spec " + it));
                        r.clues().forEach(c -> assertTrue(clues.contains(c), h.id() + ": unknown reward clue " + c));
                        effects(r.effects());
                    });
                } else if (s instanceof QuestDef.Fight f) {
                    assertFalse(f.foes().isEmpty(), f.id() + ": no foes");
                    f.foes().forEach(x -> assertTrue(x.matches("[a-z_]+:[a-z_]+(\\*\\d+)?"), "foe spec " + x));
                    f.foeName().ifPresent(k -> assertTrue(en.has(k) && ru.has(k), "foe name " + k));
                    f.protect().ifPresent(k -> assertTrue(SectRoster.of(k).isPresent(), f.id() + ": no person " + k));
                    f.shelter().ifPresent(m -> assertTrue(MARKERS.contains(m), f.id() + ": marker " + m));
                    f.replace().ifPresent(k -> assertTrue(guest(k), f.id() + ": no guest " + k));
                    assertTrue(f.limitTicks() > 0);
                    flags(f.gate());
                    effects(f.onWin());
                    effects(f.onEscape());
                } else if (s instanceof QuestDef.Escort e) {
                    assertTrue(SectRoster.of(e.npc()).isPresent(), e.id() + ": no person " + e.npc());
                    e.toNpc().ifPresent(k -> assertTrue(SectRoster.of(k).isPresent(), e.id() + ": no person " + k));
                    e.toMarker().ifPresent(m -> assertTrue(MARKERS.contains(m), e.id() + ": marker " + m));
                    assertTrue(e.toNpc().isPresent() || e.toMarker().isPresent(), e.id() + ": no destination");
                    flags(e.gate());
                    effects(e.effects());
                } else if (s instanceof QuestDef.Trial t) {
                    assertTrue(SectRoster.of(t.npc()).isPresent() && SectRoster.of(t.npc()).get().role().spars(), t.id() + ": " + t.npc() + " does not spar");
                    assertTrue(QuestDef.TRIAL_RULES.contains(t.rule()), t.id() + ": rule " + t.rule());
                    assertTrue(!(t.rule().equals("parry") || t.rule().equals("time")) || t.value() > 0, t.id() + ": the rule needs a value");
                    // a bout of the shared ring is judged at 60 s at the longest (SectBouts.FIGHT_LIMIT)
                    assertTrue(!t.rule().equals("time") || t.value() * 20 < 1200, t.id() + ": the time limit is past the bout limit");
                    flags(t.gate());
                    effects(t.onWin());
                    assertTrue(t.onWin().merit() > 0 || t.art().isPresent(), t.id() + ": the trial pays nothing");
                    t.art().ifPresent(a -> assertTrue(a.art().matches("[a-z_]+:[a-z_]+") && a.share() > 0.0D && a.share() <= 0.2D,
                            t.id() + ": art help " + a));
                    for (JsonObject l : List.of(en, ru)) {
                        for (String key : List.of("murim.trial." + t.id() + ".title", "murim.trial." + t.id() + ".praise",
                                "murim.trial.short." + t.rule(), "murim.trial.fail." + t.rule(),
                                "dialogue.murim.trial." + t.id() + ".rule", "dialogue.murim.trial.opt." + t.id())) {
                            assertTrue(l.has(key), "trial text " + key);
                        }
                    }
                    // the dialogue: the menu offers it, its node holds the rule and the action that begins the bout
                    JsonObject nodes = JsonParser.parseString(Files.readString(res("data/murim/murim_dialogues/sect_progression.json")))
                            .getAsJsonObject().getAsJsonObject("nodes");
                    assertTrue(nodes.has("trial_" + t.id()) && nodes.get("trial_" + t.id()).toString().contains("\"quest_trial\""), t.id() + ": no node");
                    assertTrue(nodes.getAsJsonObject("trials").toString().contains("quest:trial:" + t.id()), t.id() + ": not in the menu");
                } else if (s instanceof QuestDef.Observe o) {
                    assertTrue(o.marker().equals("look") || MARKERS.contains(o.marker()) || o.marker().startsWith("zone:"), o.id() + ": marker");
                    assertTrue(o.block().matches("[a-z_]+:[a-z0-9_/]+"), o.id() + ": target block");
                    assertTrue(o.ticks() > 0 && o.range() > 0 && o.range() <= 4);
                    assertTrue(en.has("murim.observation." + o.id()) && ru.has("murim.observation." + o.id()), o.id() + ": note");
                    flags(o.gate());
                    effects(o.effects());
                } else if (s instanceof QuestDef.PersonalChest c) {
                    assertTrue(c.marker().startsWith("zone:") || c.marker().startsWith("door:") || MARKERS.contains(c.marker()), c.id() + ": marker");
                    assertTrue(!c.items().isEmpty() && c.items().size() <= 27, c.id() + ": chest capacity");
                    c.items().forEach(it -> assertTrue(it.matches("[a-z_]+:[a-z0-9_/]+(\\*\\d+)?"), "item spec " + it));
                    for (String item : c.items()) {
                        if (item.startsWith("clue:")) assertTrue(clues.contains(item.substring(5)), "unknown fragment " + item);
                    }
                    flags(c.gate());
                } else if (s instanceof QuestDef.Lesson l) {
                    assertTrue(SectRoster.of(l.npc()).isPresent(), l.id() + ": mentor");
                    assertTrue(l.art().share() > 0 && l.art().share() <= 0.2 && l.art().art().matches("[a-z_]+:[a-z0-9_/]+"));
                    for (String suffix : List.of("advice", "practiced", "reward", "cap")) {
                        String key = "murim.lesson." + l.id() + "." + suffix;
                        assertTrue(en.has(key) && ru.has(key), key);
                    }
                } else if (s instanceof QuestDef.Threshold t) {
                    assertTrue(SectRoster.of(t.npc()).isPresent(), t.id() + ": mentor");
                    for (String suffix : List.of("invitation", "ready", "try", "prepare", "later", "meditated", "advanced")) {
                        String key = "murim.threshold." + t.id() + "." + suffix;
                        assertTrue(en.has(key) && ru.has(key), key);
                    }
                } else if (s instanceof QuestDef.Herb h) {
                    assertTrue(h.minY() <= h.maxY() && h.minLight() <= h.maxLight());
                    assertTrue(h.item().matches("[a-z_]+:[a-z0-9_/]+"), "single sample item " + h.item());
                    String key = "murim.herb." + h.id() + ".sample";
                    assertTrue(en.has(key) && ru.has(key), key);
                } else if (s instanceof QuestDef.Timer tm) {
                    assertTrue(tm.ticks() > 0 && tm.startFlag().matches("[a-z0-9_.]+"), tm.id());
                    flags(tm.gate());
                    effects(tm.effects());
                }
            }
        }
        // the quests of this part
        for (String id : List.of("scrap_room", "cache_box", "drill_start", "drill_target",
                "missing_basket", "rain_wall", "missing_attack", "missing_lead", "missing_done", "debt_bill", "debt_crate",
                "debt_pay", "debt_return", "impostor_receipts", "impostor_chest", "impostor_fight", "impostor_done", "shelf_nail", "sok_parcel", "thief_watch", "thief_self", "thief_report", "thief_quiet",
                "plum_travellers", "plum_archive", "plum_court", "plum_dining")) {
            assertTrue(seen.stream().anyMatch(x -> x.endsWith(":" + id)), "missing " + id);
        }
    }

    private static boolean guest(String id) {
        return Stream.of(NpcProfile.values()).anyMatch(p -> p.id.equals(id));
    }

    private static void person(String step, String npc, String node) throws IOException {
        assertTrue(SectRoster.of(npc).isPresent() || guest(npc), step + ": no person " + npc);
        Set<String> nodes = SectRoster.of(npc).isPresent() ? nodes(npc) : nodesOf(res("data/murim/murim_dialogues/guest_" + npc + ".json"));
        assertTrue(nodes.contains(node), step + ": " + npc + " has no node " + node);
    }

    private static void effects(QuestDef.Effects e) {
        e.flags().forEach(f -> assertTrue(f.matches("[a-z0-9_.]+"), "flag " + f));
        e.how().ifPresent(h -> assertTrue(h.matches("[a-z0-9_]+:[a-z0-9_]+"), "how " + h));
    }

    private static void flags(QuestDef.Gate g) {
        Stream.concat(g.flags().stream(), g.notFlags().stream()).forEach(f -> assertTrue(f.matches("[a-z0-9_.]+"), "flag " + f));
    }

    /** Nodes the person can say: his own dialogue file, and the role's, which his file builds on. */
    private static Set<String> nodes(String npc) throws IOException {
        Set<String> out = new HashSet<>();
        Path own = res("data/murim/murim_dialogues/" + npc + ".json");
        if (Files.exists(own)) out.addAll(nodesOf(own));
        else out.addAll(nodesOf(res("data/murim/murim_dialogues/" + SectRoster.of(npc).orElseThrow().role().dialogue().getPath() + ".json")));
        return out;
    }

    private static Set<String> nodesOf(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("nodes").keySet();
    }

    @Test
    @DisplayName("Parked quests keep their steps and opt into the shared sites gate")
    void parkedQuestsRetainTheirDefinitions() throws IOException {
        Set<String> parked = Set.of("cache", "debt", "fragments", "impostor", "missing", "strike", "thief");
        for (QuestDef quest : load()) {
            assertEquals(parked.contains(quest.id()), quest.requiresSites(), quest.id() + ": sites gate");
            assertFalse(quest.steps().isEmpty(), quest.id() + ": legacy steps were removed");
        }
        JsonObject mentor = JsonParser.parseString(Files.readString(res("data/murim/murim_dialogues/mentor.json")))
                .getAsJsonObject().getAsJsonObject("nodes");
        assertTrue(mentor.has("strike_explain"), "legacy mentor node removed");
        assertTrue(mentor.get("mentor_strike_explain").toString().contains("quest.mentor_strike.ask"), "new drill shares legacy flags");
    }

    @Test
    @DisplayName("A broken quest file is refused by the codec")
    void brokenFilesAreRefused() {
        var bad = JsonParser.parseString("{\"id\":\"x\",\"steps\":[{\"type\":\"nonsense\",\"id\":\"a\"}]}");
        assertTrue(QuestDef.CODEC.parse(JsonOps.INSTANCE, bad).error().isPresent());
        var missing = JsonParser.parseString("{\"id\":\"x\",\"steps\":[{\"type\":\"inspect\"}]}");
        assertTrue(QuestDef.CODEC.parse(JsonOps.INSTANCE, missing).error().isPresent());
        assertFalse(QuestDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"id\":\"x\",\"steps\":[]}")).error().isPresent());
    }
}
