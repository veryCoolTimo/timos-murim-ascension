package io.github.verycooltimo.murim.sect.repair;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The treasury table (docs/design/39-player-path-v2.md, «Ремонт секты и казна»): what is repaired, what it costs, what the sect earns. */
class RepairCostsTest {

    private static JsonObject lang(String code) throws IOException {
        Path p = Path.of("src/main/resources/assets/murim/lang/" + code + ".json");
        if (!Files.exists(p)) {
            p = Path.of("../../src/main/resources/assets/murim/lang/" + code + ".json");
        }
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    @Test
    @DisplayName("Architecture is priced by material group; furniture, plants, ores and containers are in no group")
    void groups() {
        RepairCosts c = RepairCosts.shipped();
        assertEquals("roof", c.groupOf("murim:roof_tiles").id());
        assertEquals("roof", c.groupOf("murim:roof_tile_stairs").id());
        assertEquals("fine", c.groupOf("murim:lattice_window_paper").id());
        assertEquals("fine", c.groupOf("murim:hall_door").id());
        assertEquals("timber", c.groupOf("murim:red_pillar").id());
        assertEquals("timber", c.groupOf("murim:thick_red_pillar").id());
        assertEquals("timber", c.groupOf("minecraft:spruce_planks").id());
        assertEquals("timber", c.groupOf("minecraft:spruce_fence").id());
        assertEquals("masonry", c.groupOf("murim:hua_granite_dark").id());
        assertEquals("masonry", c.groupOf("murim:polished_hua_granite_stairs").id());
        assertEquals("masonry", c.groupOf("murim:grey_bricks").id());
        assertEquals("masonry", c.groupOf("murim:thin_wall_plaster_cap").id());
        assertEquals("masonry", c.groupOf("murim:floor_tiles").id());
        assertEquals("masonry", c.groupOf("minecraft:stone_bricks").id());
        assertEquals("earth", c.groupOf("minecraft:stone").id());
        assertEquals("earth", c.groupOf("minecraft:dirt").id());
        // never re-created: containers, beds, furniture, shrines, plaques, plants, leaves, ores, climbing aids
        for (String id : List.of("minecraft:chest", "minecraft:barrel", "minecraft:white_bed", "minecraft:crafting_table", "murim:sto_chest",
                "murim:sto_cabinet", "murim:sto_bed", "murim:int_bench", "murim:int_alchemy_furnace", "murim:shr_ancestor_altar",
                "murim:sword_stand", "murim:sect_mission_board", "murim:anc_plaque_ancestors", "murim:sect_bell", "murim:book_stack",
                "murim:gate_door", "murim:hall_grand_door", "murim:climbing_moss", "murim:rock_handhold", "murim:old_rope",
                "murim:plum_blossom", "minecraft:spruce_leaves", "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:short_grass",
                "minecraft:moss_block", "minecraft:water", "minecraft:fire")) {
            assertNull(c.groupOf(id), id + " must not be repaired");
        }
    }

    @Test
    @DisplayName("Prices: the cheapest block is earth, the roof costs more than the wall, all positive")
    void prices() {
        RepairCosts c = RepairCosts.shipped();
        assertEquals(1, c.cheapest());
        assertTrue(c.groupOf("murim:roof_tiles").wen() > c.groupOf("murim:grey_bricks").wen());
        assertTrue(c.groupOf("murim:lattice_window").wen() > c.groupOf("murim:red_pillar").wen());
        for (RepairCosts.Group g : c.groups()) {
            assertTrue(g.wen() > 0, g.id());
            assertTrue(!g.match().isEmpty(), g.id());
        }
    }

    @Test
    @DisplayName("Start sum, daily income, fees: simple numbers in wen (9 wen = 1 liang)")
    void income() {
        RepairCosts c = RepairCosts.shipped();
        assertEquals(300 * 9, c.startWen());
        assertEquals(18 * 9, c.dailyWen());
        assertEquals(4 * 9, c.peddlerWen());
        assertEquals(2, c.peddlerMax());
        assertEquals(6 * 9, c.contractWen("camp"));
        assertEquals(6 * 9, c.contractWen("CAMP"));
        assertEquals(5 * 9, c.contractWen("escort"));
        assertEquals(3 * 9, c.contractWen("herbs"));
        assertEquals(0, c.contractWen("unknown"));
        assertEquals(8, c.bigBlocks());
        // a week of income repairs a good deal of wall; the start sum is not a bottomless purse
        assertTrue(c.dailyWen() * 7L < c.startWen());
        assertEquals(33, RepairCosts.liang(300));
        assertEquals(0, RepairCosts.liang(8));
        assertEquals(0, RepairCosts.liang(-5));
    }

    @Test
    @DisplayName("A table parses from text: globs match the whole id, the first group wins")
    void parse() {
        RepairCosts c = RepairCosts.parse(new StringReader("""
                {"start_liang":1,"daily_liang":2,"peddler_liang":3,"peddler_max":1,"groups":[
                  {"id":"a","wen":5,"match":["x:foo*"]},{"id":"b","wen":7,"match":["x:*bar","x:foobar"]}]}"""));
        assertEquals("a", c.groupOf("x:foobar").id());
        assertEquals("b", c.groupOf("x:rebar").id());
        assertNull(c.groupOf("y:foo"));
        assertNull(c.groupOf("x:barber"));
        assertEquals(9, c.startWen());
        assertEquals(0, c.contractWen("camp"));
        assertEquals(8, c.bigBlocks());
    }

    @Test
    @DisplayName("Every new line of the repairs is in ru_ru and en_us with the same arguments")
    void lang() throws IOException {
        JsonObject ru = lang("ru_ru");
        JsonObject en = lang("en_us");
        for (String k : List.of("murim.tablet.standing.treasury", "murim.tablet.standing.treasury_log", "murim.tablet.standing.repair_last", "murim.tablet.chronicle.repair",
                "command.murim.sect.repairs.status", "command.murim.sect.repairs.off", "command.murim.sect.repairs.aged",
                "command.murim.sect.repairs.treasury", "command.murim.sect.repairs.forgot", "command.murim.dev.help.repairs")) {
            assertNotNull(ru.get(k), "no ru: " + k);
            assertNotNull(en.get(k), "no en: " + k);
            assertEquals(args(ru.get(k).getAsString()), args(en.get(k).getAsString()), "arguments: " + k);
        }
        // a chronicle line says where: every place the sect layout can name has a line (the lang test of the dialogue places covers the rest)
        assertTrue(en.get("murim.tablet.chronicle.repair").getAsString().contains("%1$s"));
    }

    private static List<String> args(String text) {
        List<String> out = new java.util.ArrayList<>();
        var m = java.util.regex.Pattern.compile("%(\\d\\$)?s").matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        java.util.Collections.sort(out);
        return out;
    }
}
