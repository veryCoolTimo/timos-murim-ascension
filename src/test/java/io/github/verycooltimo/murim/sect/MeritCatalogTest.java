package io.github.verycooltimo.murim.sect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The merit shop catalog in data/murim/sect/merit_shop.json: the author's prices and stocks of 19.10 (docs/adr/0012). */
class MeritCatalogTest {
    private static MeritShop.Offer offer(String id) {
        return MeritShop.offer(id).orElseThrow(() -> new AssertionError("no offer " + id));
    }

    @Test
    @DisplayName("The shipped catalog holds the agreed prices, stocks and standings")
    void shippedCatalog() {
        MeritCatalog c = MeritCatalog.shipped();
        assertFalse(c.offers().isEmpty(), "the catalog did not load");
        Set<String> ids = new HashSet<>();
        c.offers().forEach(o -> assertTrue(ids.add(o.id()), "duplicate " + o.id()));

        assertEquals(20, offer("sword").price());
        assertEquals(new MeritShop.Refill(1, 20), offer("sword").refill(), "the sword: one per 20 minutes of play");
        assertEquals(12, offer("scriptures").price());
        assertEquals("pass.scriptures", offer("scriptures").extra());
        assertEquals("", offer("scriptures").item(), "the hall pass is a flag, bought once");
        assertNull(offer("scriptures").refill());
        assertEquals(6, offer("snow_plum").price());
        assertEquals(new MeritShop.Refill(2, 30), offer("snow_plum").refill());
        assertEquals(24, offer("origin_energy").price());
        assertEquals(new MeritShop.Refill(1, 60), offer("origin_energy").refill());
        assertEquals(SectStanding.GRADUATE, offer("origin_energy").need());
        assertEquals(8, offer("quick_recovery").price());
        assertEquals(new MeritShop.Refill(3, 20), offer("quick_recovery").refill());
        assertEquals(30, offer("formula_snow_plum").price());
        assertEquals(60, offer("formula_origin_energy").price());
        assertEquals("refining", offer("formula_snow_plum").gate());
        assertEquals("refining", offer("formula_origin_energy").gate());
        // Technique pages keep the price of ADR 0010 (8 + 2 s; the row holds the first star's price).
        assertEquals(10, offer("page").price());
    }

    @Test
    @DisplayName("The refining formulas wait behind the refining feature, which is off in the shipped file")
    void formulasAreGated() {
        MeritCatalog c = MeritCatalog.shipped();
        assertFalse(c.feature("refining"), "the refining station is not in the game yet");
        assertTrue(c.with("refining", true).feature("refining"));
        assertFalse(c.feature("no_such_feature"));
    }

    @Test
    @DisplayName("There is no furniture for merit (author 19.10)")
    void noFurniture() {
        for (MeritShop.Offer o : MeritCatalog.shipped().offers()) {
            String what = (o.item() + o.extra() + o.id()).toLowerCase();
            for (String bad : new String[] {"furniture", "table", "chair", "bed", "chest", "lantern", "decor"}) {
                assertFalse(what.contains(bad), o.id() + " looks like furniture: " + what);
            }
        }
    }

    @Test
    @DisplayName("Every row has its words in English and Russian: option, received line for a flag, short name for the tablet's stock")
    void everyRowHasItsWords() throws Exception {
        for (String lang : new String[] {"en_us", "ru_ru"}) {
            com.google.gson.JsonObject words;
            try (java.io.InputStream in = MeritCatalogTest.class.getResourceAsStream("/assets/murim/lang/" + lang + ".json")) {
                words = new com.google.gson.Gson().fromJson(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
                        com.google.gson.JsonObject.class);
            }
            for (MeritShop.Offer o : MeritCatalog.shipped().offers()) {
                assertTrue(words.has("dialogue.murim.merit." + o.id()), lang + ": option of " + o.id());
                if (o.item().isEmpty()) {
                    assertTrue(words.has("dialogue.murim.merit.got." + o.id()), lang + ": received line of " + o.id());
                }
                if (o.refill() != null) {
                    assertTrue(words.has("murim.tablet.shop." + o.id()), lang + ": tablet name of " + o.id());
                }
            }
            for (String key : new String[] {"murim.sect.merit.stock", "murim.sect.merit.stock.none", "murim.sect.merit.stock.left",
                    "murim.tablet.standing.stock.full", "murim.tablet.standing.stock.wait",
                    "murim.tablet.standing.tip.title", "murim.tablet.standing.tip.balance", "murim.tablet.standing.tip.total",
                    "murim.tablet.standing.tip.total2", "murim.tablet.standing.tip.ready", "murim.tablet.standing.tip.full",
                    "murim.tablet.standing.tip.some", "murim.tablet.standing.tip.none", "murim.tablet.standing.tip.refill"}) {
                assertTrue(words.has(key), lang + ": " + key);
            }
        }
    }

    @Test
    @DisplayName("A broken catalog is refused")
    void brokenFiles() {
        String head = "{\"features\":{\"refining\":false},\"offers\":[";
        String ok = "{\"id\":\"a\",\"item\":\"murim:x\",\"price\":3,\"need\":\"novice\",\"seller\":\"tae_gyun\"}";
        assertEquals(1, MeritCatalog.parse(new StringReader(head + ok + "]}")).offers().size());
        assertThrows(IllegalArgumentException.class, () -> MeritCatalog.parse(new StringReader(head + ok + "," + ok + "]}")), "duplicate id");
        assertThrows(IllegalArgumentException.class, () -> MeritCatalog.parse(new StringReader(head
                + "{\"id\":\"a\",\"price\":3,\"need\":\"novice\"}]}")), "neither item nor flag");
        assertThrows(IllegalArgumentException.class, () -> MeritCatalog.parse(new StringReader(head
                + "{\"id\":\"a\",\"item\":\"murim:x\",\"price\":0,\"need\":\"novice\"}]}")), "zero price");
        assertThrows(IllegalArgumentException.class, () -> MeritCatalog.parse(new StringReader(head
                + "{\"id\":\"a\",\"item\":\"murim:x\",\"price\":3,\"need\":\"nobody\"}]}")), "unknown standing");
        assertThrows(IllegalArgumentException.class, () -> MeritCatalog.parse(new StringReader(head
                + "{\"id\":\"a\",\"item\":\"murim:x\",\"price\":3,\"need\":\"novice\",\"stock\":{\"max\":0,\"minutes\":5}}]}")), "empty stock");
        assertThrows(IllegalArgumentException.class, () -> MeritCatalog.parse(new StringReader(head
                + "{\"id\":\"a\",\"item\":\"murim:x\",\"price\":3,\"need\":\"novice\",\"gate\":\"nothing\"}]}")), "unknown feature");
        MeritShop.Offer fine = MeritCatalog.parse(new StringReader(head + ok + "]}")).offers().get(0);
        assertEquals(1, fine.count());
        assertNotNull(fine.seller());
    }
}
