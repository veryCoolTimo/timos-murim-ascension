package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.network.SectTabletView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tablet shows nothing that is not in a lang file and nothing that the game does not define (docs/adr/0016): every text key
 * the screen and the server can name exists in both languages, every picture the tables name exists, and the screen source
 * holds no word of its own.
 */
class SectTabletTextTest {
    private static Path path(String rel) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("src/main/resources/assets/murim"))) {
            root = root.getParent();
        }
        if (root == null) throw new AssertionError("source tree not found above working directory");
        return root.resolve("src/main").resolve(rel);
    }

    private static JsonObject lang(String code) throws IOException {
        return JsonParser.parseString(Files.readString(path("resources/assets/murim/lang/" + code + ".json"))).getAsJsonObject();
    }

    private static String source(String rel) throws IOException {
        return Files.readString(path("java/io/github/verycooltimo/murim/" + rel));
    }

    private static boolean sprite(String name) {
        return Files.exists(path("resources/assets/murim/textures/gui/sprites/tablet/" + name + ".png"));
    }

    private static void both(List<String> keys) throws IOException {
        JsonObject en = lang("en_us");
        JsonObject ru = lang("ru_ru");
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            if (!en.has(key)) missing.add("en_us " + key);
            if (!ru.has(key)) missing.add("ru_ru " + key);
        }
        assertTrue(missing.isEmpty(), "missing text keys: " + missing);
    }

    @Test
    @DisplayName("Both languages hold the same tablet keys")
    void bothLanguagesHoldTheSameKeys() throws IOException {
        TreeSet<String> en = new TreeSet<>();
        TreeSet<String> ru = new TreeSet<>();
        lang("en_us").keySet().stream().filter(k -> k.startsWith("murim.tablet.") || k.startsWith("murim.contract.")).forEach(en::add);
        lang("ru_ru").keySet().stream().filter(k -> k.startsWith("murim.tablet.") || k.startsWith("murim.contract.")).forEach(ru::add);
        TreeSet<String> onlyEn = new TreeSet<>(en);
        onlyEn.removeAll(ru);
        TreeSet<String> onlyRu = new TreeSet<>(ru);
        onlyRu.removeAll(en);
        assertTrue(onlyEn.isEmpty() && onlyRu.isEmpty(), "only in en_us: " + onlyEn + ", only in ru_ru: " + onlyRu);
    }

    @Test
    @DisplayName("Every key the screen and the server name literally exists in both languages")
    void everyNamedKeyExists() throws IOException {
        List<String> keys = new ArrayList<>();
        String screen = source("client/sect/SectTabletScreen.java");
        // t("name") and t("name", args) build murim.tablet.<name>
        Matcher t = Pattern.compile("\\bt\\(\"([a-z0-9_.]+)\"").matcher(screen);
        while (t.find()) {
            // a name ending in a dot is a prefix: the rest comes from a game list and is checked below
            if (!t.group(1).endsWith(".")) keys.add("murim.tablet." + t.group(1));
        }
        for (String file : new String[] {"client/sect/SectTabletScreen.java", "sect/SectTablet.java"}) {
            Matcher k = Pattern.compile("\"(murim\\.(?:tablet|contract|sect)\\.[a-z0-9_.]+)\"").matcher(source(file));
            while (k.find()) {
                String key = k.group(1);
                if (!key.endsWith(".")) keys.add(key);
            }
        }
        assertTrue(keys.size() > 40, "the scan found only " + keys.size() + " keys");
        both(keys);
    }

    @Test
    @DisplayName("Keys built from the game's own lists: marks, periods, ladder, tabs, places, steps, shop rows, contract places")
    void keysBuiltFromGameLists() throws IOException {
        List<String> keys = new ArrayList<>();
        for (SectAttendance.Activity a : List.of(SectAttendance.Activity.FORMATION, SectAttendance.Activity.MEAL, SectAttendance.Activity.LESSON)) {
            keys.add("murim.tablet.mark." + a.id());
        }
        for (SectSchedule.Period p : SectSchedule.Period.values()) {
            keys.add("murim.tablet.period." + p.id());
            keys.add("murim.tablet.now." + p.id());
        }
        for (String rung : SectTabletView.ladder()) keys.add("murim.tablet.rank." + rung);
        for (SectTabletView.Tab tab : SectTabletView.Tab.values()) keys.add("murim.tablet.tab." + tab.id());
        for (String step : SectJourney.TRACK) keys.add("murim.tablet.step." + step);
        for (MeritShop.Offer o : MeritShop.OFFERS) {
            if (o.refill() != null && !o.item().isEmpty()) keys.add("murim.tablet.shop." + o.id());
        }
        for (int i = 0; i < SectContracts.PLACES; i++) keys.add("murim.contract.place." + i);
        for (String dir : SectContracts.DIRECTIONS) keys.add("murim.contract.dir." + dir);
        // JUnit has no datapack reload: combine wild entries with shipped quest herb definitions.
        TreeSet<String> habitats = new TreeSet<>();
        io.github.verycooltimo.murim.world.herb.HerbCatalog.WILD.forEach(e -> habitats.add(e.habitat()));
        try (var files = Files.walk(path("resources/data/murim/murim_quests"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                var quest = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                for (var element : quest.getAsJsonArray("steps")) {
                    var step = element.getAsJsonObject();
                    if (step.get("type").getAsString().equals("herb")) {
                        habitats.add(step.has("habitat") ? step.get("habitat").getAsString() : "forest");
                    }
                }
            }
        }
        for (String habitat : habitats) {
            keys.add("murim.contract.habitat." + habitat);
            keys.add("murim.tablet.habitat." + habitat);
        }
        for (String form : List.of("one", "few", "many")) keys.add("murim.tablet.days." + form);
        for (SectLayoutFile.Building b : SectLayoutFile.shipped().buildings().values()) {
            String id = b.id().matches("elder_\\d+") ? "elders" : b.id();
            keys.add("murim.tablet.place." + id);
        }
        both(keys);
    }

    @Test
    @DisplayName("Every picture the tables name exists as a sprite")
    void everyNamedPictureExists() {
        List<String> missing = new ArrayList<>();
        for (SectSchedule.Period p : SectSchedule.Period.values()) {
            if (!sprite("scene_" + p.id())) missing.add("scene_" + p.id());
            if (!sprite("icon_" + SectTabletView.periodIcon(p))) missing.add("icon_" + SectTabletView.periodIcon(p));
        }
        for (SectTabletView.Tab tab : SectTabletView.Tab.values()) {
            if (!sprite("icon_" + tab.icon())) missing.add("icon_" + tab.icon());
        }
        List<String> ladder = SectTabletView.ladder();
        for (int i = 0; i < ladder.size(); i++) {
            if (SectTabletView.standing(i) && !sprite("emblem_" + ladder.get(i))) missing.add("emblem_" + ladder.get(i));
        }
        for (SectContracts.Kind kind : SectContracts.Kind.values()) {
            if (!sprite(SectTablet.icon(kind))) missing.add(SectTablet.icon(kind));
        }
        assertTrue(missing.isEmpty(), "sprites missing: " + missing);
    }

    @Test
    @DisplayName("The ladder is the standings and the offices, and the held/next rungs follow it")
    void ladderFollowsTheGame() {
        List<String> ladder = SectTabletView.ladder();
        assertEquals(SectTabletView.RUNGS, ladder.size());
        for (SectStanding s : SectStanding.values()) assertEquals(s.id(), ladder.get(s.ordinal()));
        assertEquals(List.of("first", "elder"), ladder.subList(SectStanding.values().length, ladder.size()));
        for (SectStanding s : SectStanding.values()) {
            assertEquals(s.ordinal(), SectTabletView.held(s.ordinal(), 0));
            assertEquals(Math.min(SectTabletView.RUNGS - 1, s.ordinal() + 1), SectTabletView.top(s.ordinal(), 0));
        }
        assertEquals(SectStanding.values().length, SectTabletView.held(4, 1));
        assertEquals(SectTabletView.RUNGS - 1, SectTabletView.held(0, 2));
    }

    @Test
    @DisplayName("The screen holds no word of its own: no literal text, no letters, no fixed ladder or icon table")
    void screenHasNoWordsOfItsOwn() throws IOException {
        String screen = source("client/sect/SectTabletScreen.java");
        // text may come from a name (a player, a member) or be derived from text; a quoted word may not be drawn or wrapped
        Matcher literal = Pattern.compile("Component\\.literal\\(\"").matcher(screen);
        assertFalse(literal.find(), "Component.literal with a word in the screen");
        Matcher drawn = Pattern.compile("drawString\\(font, \"").matcher(screen);
        assertFalse(drawn.find(), "a quoted string drawn directly");
        for (String banned : new String[] {"OFFER_ICONS", "RANK_ICONS", "PERIOD_ICONS", "BAKED_", "tablet_map.png", "SectTabletDemo"}) {
            assertFalse(screen.contains(banned), "the screen still has " + banned);
        }
    }

    @Test
    @DisplayName("The wire format and the screen are written from one list of fields: no leftover of the baked map")
    void noBakedMapLeft() {
        assertFalse(Files.exists(path("resources/assets/murim/textures/gui/tablet_map.png")), "the baked map is back");
    }
}
