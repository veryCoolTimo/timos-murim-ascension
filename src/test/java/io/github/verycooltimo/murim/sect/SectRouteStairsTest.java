package io.github.verycooltimo.murim.sect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stair choice of the router (docs/adr/0017, outside playtest 19.10: "they take the wrong stairs"). The router must send a
 * person down or up exactly the stairs of the floors between him and the door, and no others; the graph must not offer a stair end
 * that no walk can leave. Every check runs on the shipped sect at the four rotations a site can have.
 */
class SectRouteStairsTest {

    private static final List<String> ORDER = SectRoster.ALL.stream().map(SectRoster::key).toList();

    private static SectLayoutFile at(int rotation) {
        return SectLayoutFile.parse(new InputStreamReader(SectLayoutFile.class.getResourceAsStream(SectLayoutFile.RESOURCE),
                StandardCharsets.UTF_8), frame -> new SectLayoutFile.Anchor(frame, frame.centerX(), frame.centerZ(), frame.baseY(), rotation));
    }

    /** The y of the waypoints along a route, in order. */
    private static List<Integer> heights(SectLayoutFile f, SectLayoutFile.Route route) {
        return route.nodes().stream().map(id -> f.waypoint(id).pos().getY()).toList();
    }

    /** Sum of the rises and falls along a heights profile. */
    private static int variation(List<Integer> ys) {
        int sum = 0;
        for (int i = 0; i + 1 < ys.size(); i++) {
            sum += Math.abs(ys.get(i + 1) - ys.get(i));
        }
        return sum;
    }

    @Test
    @DisplayName("From every dormitory bed to the dining hall and back: the way goes straight down (or up) the floors between, never up and down again")
    void dormitoryStairs() {
        for (int rotation = 0; rotation < 4; rotation++) {
            SectLayoutFile f = at(rotation);
            SectLayoutFile.Room dining = f.rooms().stream().filter(r -> "dining_hall".equals(r.function())).findFirst().orElseThrow();
            var seat = f.spotsIn(dining.id()).get(0);
            int checked = 0;
            for (SectLayoutFile.Bed bed : f.beds()) {
                if (bed.building() == null || !bed.building().startsWith("dorm_")) {
                    continue;
                }
                var out = f.route(f.linksFor(bed.approach(), bed.room()), f.linksFor(seat.walkTo(), dining.id()), null);
                var back = f.route(f.linksFor(seat.walkTo(), dining.id()), f.linksFor(bed.approach(), bed.room()), null);
                assertNotNull(out, "r" + rotation + " no route from " + bed.id());
                assertNotNull(back, "r" + rotation + " no route to " + bed.id());
                for (var route : List.of(out, back)) {
                    List<Integer> ys = heights(f, route);
                    int net = Math.abs(ys.get(ys.size() - 1) - ys.get(0));
                    // a wrong stair costs a whole flight (3 to 4 levels) up and the same down; a few levels of slope are the yard
                    assertTrue(variation(ys) <= net + 2, "r" + rotation + " " + bed.id() + " climbs and falls " + variation(ys) + " levels for a net "
                            + net + ": " + route.nodes());
                    // it leaves by the stair of its own house and no other
                    for (int i = 0; i < route.edges().size(); i++) {
                        var e = route.edges().get(i);
                        var from = f.waypoint(route.nodes().get(i));
                        var to = f.waypoint(route.nodes().get(i + 1));
                        if (e.stairs() && from.ref() != null && from.ref().startsWith("stair.") && to.kind().startsWith("stair")) {
                            var stair = f.stair(from.ref());
                            assertTrue(stair.building() == null || stair.building().equals(bed.building()),
                                    "r" + rotation + " " + bed.id() + " takes " + stair.id() + " of " + stair.building());
                        }
                    }
                }
                checked++;
            }
            assertTrue(checked >= 40, "r" + rotation + " beds checked: " + checked);
        }
    }

    @Test
    @DisplayName("No route inside a house goes up and then down (or down and then up) before it leaves the house")
    void noReversalInsideAHouse() {
        for (int rotation = 0; rotation < 4; rotation++) {
            SectLayoutFile f = at(rotation);
            List<SectLayoutFile.Spot> spots = f.spots().stream().filter(s -> "ok".equals(s.reach()) && s.room() != null).toList();
            int step = Math.max(1, spots.size() / 70);
            int routes = 0;
            for (int i = 0; i < spots.size(); i += step) {
                for (int j = 3; j < spots.size(); j += step * 2 + 1) {
                    var a = spots.get(i);
                    var b = spots.get(j);
                    var route = f.route(f.linksFor(a.walkTo(), a.room()), f.linksFor(b.walkTo(), b.room()), null);
                    if (route == null) {
                        continue;
                    }
                    routes++;
                    String house = null;
                    int lastDir = 0;
                    for (int k = 0; k < route.edges().size(); k++) {
                        var from = f.waypoint(route.nodes().get(k));
                        var to = f.waypoint(route.nodes().get(k + 1));
                        String bf = from.room() == null || f.room(from.room()) == null ? null : f.room(from.room()).building();
                        String bt = to.room() == null || f.room(to.room()) == null ? null : f.room(to.room()).building();
                        int dir = Integer.signum(to.pos().getY() - from.pos().getY());
                        if (bf == null || !bf.equals(bt) || !route.edges().get(k).stairs() || dir == 0) {
                            if (bf == null || !bf.equals(house)) {
                                house = null;
                                lastDir = 0;
                            }
                            continue;
                        }
                        if (bf.equals(house) && lastDir != 0 && dir != lastDir) {
                            throw new AssertionError("r" + rotation + " " + a.id() + " > " + b.id() + " turns back inside " + bf + ": " + route.nodes());
                        }
                        house = bf;
                        lastDir = dir;
                    }
                }
            }
            assertTrue(routes > 500, "routes checked: " + routes);
        }
    }

    @Test
    @DisplayName("Every flight has both ends in the graph with the flight between them and a way on from each end")
    void noDeadFlights() {
        for (int rotation = 0; rotation < 4; rotation++) {
            SectLayoutFile f = at(rotation);
            for (SectLayoutFile.Stair s : f.stairs()) {
                var bottom = f.waypoint(s.id() + ".bottom");
                var top = f.waypoint(s.id() + ".top");
                assertNotNull(bottom, "r" + rotation + " " + s.id() + " has no bottom waypoint");
                assertNotNull(top, "r" + rotation + " " + s.id() + " has no top waypoint");
                assertTrue(bottom.pos().getY() < top.pos().getY(), "r" + rotation + " " + s.id() + " bottom is not below its top");
                // the two ends are joined, by the flight itself or by a way round it
                var join = f.route(s.id() + ".bottom", s.id() + ".top");
                assertNotNull(join, "r" + rotation + " " + s.id() + ": the ends are not joined");
            }
        }
    }
}
