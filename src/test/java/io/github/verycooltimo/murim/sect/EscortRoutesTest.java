package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.training.RouteRun;
import io.github.verycooltimo.murim.training.Routes;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three escort routes (Track C, docs/design/research/hua-main-1810.md section 8) hold to their geometry on the canonical
 * mountain: they start at the sect gate, the trail routes run over the very points of the trail route tool
 * ({@link Routes#trail}), the inner route is the walkers' own plan over the checked layout graph, no leg is a leap, and the
 * board hands them out in turn.
 */
class EscortRoutesTest {

    private static MountHuaSite site;
    private static SectLayoutFile file;
    private static BlockPos stall;

    @BeforeAll
    static void mountain() {
        // The canonical mountain of the built sect (the world the layout was extracted from).
        site = new MountHuaSite(-1071, 4216, 67, 0, 1L, true);
        file = SectLayoutFile.anchored(site);
        int[] xz = io.github.verycooltimo.murim.trade.PeddlerSpawns.stallXZ(site);
        stall = new BlockPos(xz[0], 70, xz[1]);
    }

    private static List<EscortRoutes.Route> routes() {
        return EscortRoutes.all(site, file, stall);
    }

    @Test
    @DisplayName("There are three routes, in the order of the ids, from the short walk to the long one")
    void threeRoutesShortToLong() {
        List<EscortRoutes.Route> all = routes();
        assertEquals(3, all.size(), "a route could not be made");
        assertEquals(EscortRoutes.IDS, all.stream().map(EscortRoutes.Route::id).toList());
        double treasury = all.get(0).length();
        double north = all.get(1).length();
        double foot = all.get(2).length();
        assertTrue(treasury > 15.0D && treasury < north && north < foot, treasury + " < " + north + " < " + foot);
        assertTrue(foot > 300.0D, "the foot route is the whole trail: " + foot);
    }

    @Test
    @DisplayName("Every route starts at the sect gate")
    void everyRouteStartsAtTheGate() {
        int[] gate = SectService.zone(site, "sect_gate");
        assertNotNull(gate);
        for (EscortRoutes.Route r : routes()) {
            Vec3 start = r.points().get(0);
            // the trail routes begin on the trail's last point, the inner one on the inner side of the gate: both beside it
            assertTrue(Math.hypot(start.x - gate[0], start.z - gate[2]) < 20.0D, r.id() + " starts " + start + " far from the gate");
        }
    }

    @Test
    @DisplayName("The trail routes run over the points of the trail route tool, in the order down the mountain")
    void trailRoutesAreTheTrailToolsPoints() {
        List<RouteRun.Point> sprint = Routes.trail(site);
        EscortRoutes.Route north = routes().get(1);
        EscortRoutes.Route foot = routes().get(2);
        int count = sprint.size();
        assertEquals(count - EscortRoutes.NORTH_END, north.points().size(), "north route: one point for each trail point from the gate to the shoulder");
        for (int i = 0; i < north.points().size(); i++) {
            RouteRun.Point p = sprint.get(count - 1 - i);
            assertEquals(p.x(), north.points().get(i).x, 1.0E-6, "north point " + i);
            assertEquals(p.z(), north.points().get(i).z, 1.0E-6, "north point " + i);
        }
        // the foot route is the whole trail and then the stall
        assertEquals(count + 1, foot.points().size());
        for (int i = 0; i < count; i++) {
            RouteRun.Point p = sprint.get(count - 1 - i);
            assertEquals(p.x(), foot.points().get(i).x, 1.0E-6, "foot point " + i);
            assertEquals(p.z(), foot.points().get(i).z, 1.0E-6, "foot point " + i);
        }
        Vec3 end = foot.end();
        assertEquals(stall.getX() + 0.5D, end.x, 1.0E-6);
        assertEquals(stall.getZ() + 0.5D, end.z, 1.0E-6);
    }

    @Test
    @DisplayName("No leg of a route is a leap: the steps are short, and a climb is never steeper than a stair")
    void noLegIsALeap() {
        for (EscortRoutes.Route r : routes()) {
            List<Vec3> pts = r.points();
            for (int i = 1; i < pts.size(); i++) {
                double flat = EscortRoutes.flat(pts.get(i - 1), pts.get(i));
                double rise = Math.abs(pts.get(i).y - pts.get(i - 1).y);
                boolean inner = r.id().equals(EscortRoutes.TREASURY);
                assertTrue(flat <= (inner ? 40.0D : 60.0D), r.id() + " leg " + i + " is " + flat + " blocks");
                if (inner) {
                    // the walkers' graph is checked cell by cell (step of one block, stairs one block per block)
                    assertTrue(rise <= flat + 1.0D, r.id() + " leg " + i + " climbs " + rise + " over " + flat);
                }
            }
        }
    }

    @Test
    @DisplayName("The inner route ends in the treasury yard")
    void theInnerRouteEndsInTheYard() {
        EscortRoutes.Route inner = routes().get(0);
        SectLayoutFile.Zone yard = file.zone("treasury_yard");
        assertNotNull(yard);
        Vec3 end = inner.end();
        assertTrue(yard.rect().contains((int) Math.floor(end.x), (int) Math.floor(end.z)), "the end " + end + " is not in the yard " + yard.rect());
        assertTrue(Math.abs(end.y - yard.y()) < 1.5D, "the end is not at the floor of the yard");
    }

    @Test
    @DisplayName("The merchant talks at points that are far apart, never at the gate or the end")
    void talkingPoints() {
        for (EscortRoutes.Route r : routes()) {
            List<Vec3> talk = r.talkingPoints();
            Vec3 last = r.points().get(0);
            for (Vec3 p : talk) {
                assertTrue(EscortRoutes.flat(last, p) >= EscortRoutes.TALK_SPACING - 1.0E-6, r.id() + ": two talking points too near");
                assertTrue(!p.equals(r.end()) && !p.equals(r.points().get(0)), r.id() + ": a line at the gate or at the end");
                last = p;
            }
        }
        assertTrue(routes().get(2).talkingPoints().size() >= 5, "the long walk has lines on the way");
    }

    // ------------------------------------------------------------------ the board hands them out in turn

    @Test
    @DisplayName("Three sect days, three different escorts: every route comes round, and a day's offer does not change")
    void theBoardTakesTheRoutesInTurn() {
        List<SectContracts.RouteRef> refs = routes().stream().map(r -> new SectContracts.RouteRef(r.id(), r.endCell())).toList();
        BlockPos gate = new BlockPos(0, 70, 0);
        for (long seed : new long[] {1L, 7L, -42L, 20261004L}) {
            Set<String> seen = new HashSet<>();
            for (long day = 10; day < 13; day++) {
                SectContracts.Offer escort = SectContracts.generate(seed, day, List.of(), refs, gate).get(1);
                assertEquals(SectContracts.Kind.ESCORT, escort.kind());
                seen.add(escort.itemA());
                assertEquals(escort, SectContracts.generate(seed, day, List.of(), refs, gate).get(1), "the offer of a day must not change");
                SectContracts.Offer saved = SectContracts.Offer.load(escort.save());
                assertEquals(escort, saved, "the route is lost in the save");
                assertEquals(refs.stream().filter(x -> x.id().equals(escort.itemA())).findFirst().orElseThrow().end(), escort.site(),
                        "the offer ends where its route ends");
            }
            assertEquals(Set.copyOf(EscortRoutes.IDS), seen, "seed " + seed + ": not every route came round in three days");
        }
    }

    @Test
    @DisplayName("Every route has its words in both languages: title, where, status, arrival")
    void everyRouteHasItsWordsInBothLanguages() throws java.io.IOException {
        for (String code : new String[] {"en_us", "ru_ru"}) {
            java.nio.file.Path path = java.nio.file.Path.of("src/main/resources/assets/murim/lang/" + code + ".json");
            if (!java.nio.file.Files.exists(path)) {
                path = java.nio.file.Path.of("../../src/main/resources/assets/murim/lang/" + code + ".json");
            }
            com.google.gson.JsonObject lang = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
            for (String id : EscortRoutes.IDS) {
                for (String stem : new String[] {"murim.contract.kind.escort.", "murim.tablet.contract.escort.where.",
                        "murim.contract.status.walking.", "murim.contract.arrived."}) {
                    assertTrue(lang.has(stem + id) && !lang.get(stem + id).getAsString().isBlank(), code + " lacks " + stem + id);
                }
            }
        }
    }

    @Test
    @DisplayName("The single-route form keeps the contract as it was: the stall, the foot route")
    void theOldFormIsTheFootRoute() {
        BlockPos end = new BlockPos(5, 70, 9);
        SectContracts.Offer escort = SectContracts.generate(3L, 4L, List.of(), end, new BlockPos(0, 70, 0)).get(1);
        assertEquals(end, escort.site());
        assertEquals(EscortRoutes.FOOT, escort.itemA());
    }
}
