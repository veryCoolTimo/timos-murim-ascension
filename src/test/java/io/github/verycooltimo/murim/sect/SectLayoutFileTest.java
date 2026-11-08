package io.github.verycooltimo.murim.sect;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Docs/design/38 section 5.8, test 1: the shipped layout.json loads, cross-references hold, anchoring works. */
class SectLayoutFileTest {

    private static final List<String> ORDER = SectRoster.ALL.stream().map(SectRoster::key).toList();

    @Test
    @DisplayName("The shipped file loads and the counts match the extraction")
    void loads() {
        SectLayoutFile f = SectLayoutFile.shipped();
        assertNotNull(f, "layout.json must be on the classpath");
        assertEquals(16, f.buildings().size());
        assertEquals(105, f.rooms().size());
        assertEquals(87, f.doors().size(), "door.87 (treasury.f2.r1 <-> r2) was built in int12");
        assertEquals(52, f.beds().size());
        assertEquals(300, f.waypoints().size(), "232 + the foot of the completed stair.07 (int14) + the corridor waypoints of the completed graph (19.10), without the three moss ones and without the two cliff-top nodes path.041 / path.048 (23.10); 24.10: the dorm stairwells in one hall each (twelve runs instead of six)");
        assertEquals(422, f.edges().size(), "401 minus the 18 edges of the two cliff-top nodes and the copies the extractor wrote twice; 24.10: +39 for the dorm stairwells");
    }

    @Test
    @DisplayName("int14: the penance cave is a zone and a landmark, the kitchen annex is the storeroom, stair.07 has a foot")
    void int14Places() {
        SectLayoutFile f = SectLayoutFile.shipped();
        assertNotNull(f.landmark("penance"), "landmarks.penance");
        assertNotNull(f.landmark("penance").gate());
        assertNotNull(f.landmark("penance").mouth());
        assertNotNull(f.zone("penance"), "zone penance");
        assertTrue(f.zone("penance").rect().contains(f.landmark("penance").cell().getX(), f.landmark("penance").cell().getZ()));
        assertNotNull(f.building("storeroom"), "building storeroom");
        assertNull(f.building("kitchen"), "the kitchen is the north end of the dining hall now");
        assertNotNull(f.waypoint("stair.07.bottom"), "the foot of stair.07");
    }

    @Test
    @DisplayName("The stored frame is the live Mount Hua site of the world (17.10: a frame 1 block and 3 levels off shifted every waypoint)")
    void frameIsTheSiteOfTheWorld() {
        SectLayoutFile.Frame frame = SectLayoutFile.shipped().frame();
        assertEquals(-1071, frame.centerX());
        assertEquals(4216, frame.centerZ());
        assertEquals(67, frame.baseY());
    }

    @Test
    @DisplayName("Cross references: beds, spots, doors, portals and edges point at things that exist")
    void crossReferences() {
        SectLayoutFile f = SectLayoutFile.shipped();
        for (SectLayoutFile.Bed b : f.beds()) {
            assertNotNull(f.room(b.room()), b.id() + " room");
            assertNotNull(b.approach(), b.id() + " approach");
        }
        for (SectLayoutFile.Spot s : f.spots()) {
            if (s.room() != null) {
                assertNotNull(f.room(s.room()), s.id() + " room " + s.room());
            }
        }
        for (SectLayoutFile.Room r : f.rooms()) {
            for (String p : r.portals()) {
                assertNotNull(f.waypoint(p), r.id() + " portal " + p);
            }
            for (String d : r.doors()) {
                assertNotNull(f.door(d), r.id() + " door " + d);
            }
        }
        for (SectLayoutFile.Edge e : f.edges()) {
            assertNotNull(f.waypoint(e.a()), "edge a " + e.a());
            assertNotNull(f.waypoint(e.b()), "edge b " + e.b());
            for (String c : e.crosses()) {
                assertNotNull(f.door(c), "edge crosses " + c);
            }
        }
    }

    @Test
    @DisplayName("No way is written twice: the stuck recovery bans an edge by index, and a copy left the dead edge open (steward, 5 of 5 worlds)")
    void noEdgeIsTwice() {
        SectLayoutFile f = SectLayoutFile.shipped();
        for (SectLayoutFile.Edge a : f.edges()) {
            for (SectLayoutFile.Edge b : f.edges()) {
                assertFalse(a != b && a.sameWay(b), "edge " + a.a() + " - " + a.b() + " exists twice");
            }
        }
    }

    @Test
    @DisplayName("A way that is not a stair never climbs more than three cells in one hop, but for two slopes checked in the generated mountain (path.048 > door.75.a climbed five up a retaining wall)")
    void noCliffOnAPlainWay() {
        SectLayoutFile f = SectLayoutFile.shipped();
        assertNull(f.waypoint("path.041"), "path.041 stands on top of the retaining wall below the mentor dais");
        assertNull(f.waypoint("path.048"), "path.048 leads only to that wall");
        for (SectLayoutFile.Edge e : f.edges()) {
            if (e.stairs()) {
                continue;
            }
            List<BlockPos> pts = new java.util.ArrayList<>();
            pts.add(f.waypoint(e.a()).pos());
            pts.addAll(e.via());
            pts.add(f.waypoint(e.b()).pos());
            for (int i = 0; i + 1 < pts.size(); i++) {
                int dy = Math.abs(pts.get(i + 1).getY() - pts.get(i).getY());
                // path.049 - path.050 (5 over 13 blocks) and path.055 - path.056 (4 over 6) were walked cell by cell in the generated mountain
                // (seed 424242, rotation 0): 13 and 7 steps. A new steep hop has to be walked the same way before it joins this list.
                boolean checked = e.a().equals("path.049") && e.b().equals("path.050") || e.a().equals("path.055") && e.b().equals("path.056");
                assertTrue(dy <= 3 || checked, "edge " + e.a() + " - " + e.b() + " hop " + (i + 1) + " climbs " + dy + " cells without being a stair");
            }
        }
    }

    @Test
    @DisplayName("Every roster member gets a different bed of the right room kind; the gatekeeper has an exclusive bed")
    void everyRoleHasABed() {
        SectLayoutFile f = SectLayoutFile.shipped();
        Set<String> used = new HashSet<>();
        for (SectRoster m : SectRoster.ALL) {
            SectLayoutFile.Bed b = f.bedFor(m.key(), ORDER);
            assertNotNull(b, m.key() + " has no bed");
            assertTrue(used.add(b.id()), m.key() + " shares " + b.id());
            assertEquals(f.layoutRole(m.key()), b.role(), m.key());
        }
        assertEquals(44, used.size());
        assertTrue(f.spareBed().isPresent());
        assertEquals("bed.39", f.bedFor("gatekeeper", ORDER).id());
        assertTrue(f.beds().stream().filter(b -> "dorm_2nd".equals(b.building()) && "spare".equals(b.role())).count() >= 2);
        // named beds go to the named people
        // (the 52-bed layout renumbered the beds: the treasurer sleeps in the first elder house, bed.49 at int8+)
        assertEquals("tae_gyun", f.bedFor("tae_gyun", ORDER).ownerKey());
        assertEquals("elder_1.f0.r1", f.bedFor("tae_gyun", ORDER).room());
        assertEquals("dorm_2nd.f1.r5", f.bedFor("gyeong_pil", ORDER).room());
    }

    @Test
    @DisplayName("Anchoring shifts every coordinate and a quarter turn keeps door sides next to their doors")
    void anchoring() {
        SectLayoutFile base = SectLayoutFile.shipped();
        Map<String, BlockPos> before = new HashMap<>();
        base.beds().forEach(b -> before.put(b.id(), b.head()));
        SectLayoutFile moved = parse(frame -> new SectLayoutFile.Anchor(frame, frame.centerX() + 100, frame.centerZ() - 30,
                frame.baseY() + 7, frame.rotation()));
        for (SectLayoutFile.Bed b : moved.beds()) {
            assertEquals(before.get(b.id()).offset(100, 7, -30), b.head(), b.id());
        }
        SectLayoutFile turned = parse(frame -> new SectLayoutFile.Anchor(frame, frame.centerX(), frame.centerZ(), frame.baseY(), 1));
        for (SectLayoutFile.Door d : turned.doors()) {
            if (d.sideA().isEmpty()) {
                continue;
            }
            BlockPos side = d.sideA().get(0);
            assertTrue(Math.abs(side.getX() - d.min().getX()) <= 8 && Math.abs(side.getZ() - d.min().getZ()) <= 8
                    && side.getY() == d.min().getY(), d.id() + " side drifted from the door after a quarter turn");
        }
        // rotation turns the yaw by 90 degrees
        float yaw = base.beds().iterator().next().yaw();
        assertEquals(yaw + 90.0F, turned.beds().iterator().next().yaw(), 0.001F);
    }

    @Test
    @DisplayName("The graph connects the two dormitories with the dining hall")
    void routes() {
        SectLayoutFile f = SectLayoutFile.shipped();
        SectLayoutFile.Bed bed = f.bedFor("yul_jin", ORDER);
        SectLayoutFile.Room dining = f.rooms().stream().filter(r -> "dining_hall".equals(r.function())).findFirst().orElseThrow();
        var route = f.route(f.linksFor(bed.approach(), bed.room()), f.linksFor(f.spotsIn(dining.id()).get(0).walkTo(), dining.id()), null);
        assertNotNull(route, "no route from a dormitory bed to the dining hall");
        assertTrue(route.cost() > 10 && route.cost() < 400, "route cost " + route.cost());
    }

    private static SectLayoutFile parse(java.util.function.Function<SectLayoutFile.Frame, SectLayoutFile.Anchor> anchor) {
        return SectLayoutFile.parse(new InputStreamReader(SectLayoutFile.class.getResourceAsStream(SectLayoutFile.RESOURCE),
                StandardCharsets.UTF_8), anchor);
    }
}
