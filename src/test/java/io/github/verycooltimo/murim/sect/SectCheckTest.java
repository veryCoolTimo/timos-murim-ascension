package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sect check of 24.10: what the author's playtest of the 23-24.10 jars found, held on the shipped data. */
class SectCheckTest {

    private static JsonObject layoutJson() {
        return JsonParser.parseReader(new InputStreamReader(SectLayoutFile.class.getResourceAsStream(SectLayoutFile.RESOURCE), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    @DisplayName("«лестница в одной комнате от подвала до третьего этажа»: every flight of a dormitory stands in one stairwell, one flight pair per floor")
    void dormitoryHasOneStairwell() {
        JsonObject lay = layoutJson();
        for (String dorm : List.of("dorm_2nd", "dorm_3rd")) {
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE, yLow = Integer.MAX_VALUE, yHigh = Integer.MIN_VALUE;
            int flights = 0;
            for (JsonElement e : lay.getAsJsonArray("stairs")) {
                JsonObject s = e.getAsJsonObject();
                if (s.get("building").isJsonNull() || !dorm.equals(s.get("building").getAsString())) {
                    continue;
                }
                JsonArray b = s.getAsJsonArray("bbox");
                x0 = Math.min(x0, b.get(0).getAsInt());
                z0 = Math.min(z0, b.get(1).getAsInt());
                x1 = Math.max(x1, b.get(2).getAsInt());
                z1 = Math.max(z1, b.get(3).getAsInt());
                yLow = Math.min(yLow, s.get("y_bottom").getAsInt());
                yHigh = Math.max(yHigh, s.get("y_top").getAsInt());
                flights++;
            }
            // the stairwell is one hall: at most 5 x 4 cells (it was two halls, 14 blocks apart, and a flight in each)
            assertTrue(x1 - x0 + 1 <= 5 && z1 - z0 + 1 <= 4, dorm + ": the flights span " + (x1 - x0 + 1) + " x " + (z1 - z0 + 1) + " cells, not one stairwell");
            assertEquals(6, flights, dorm + ": three floors, a pair of runs each");
            assertTrue(yLow <= 208 && yHigh >= 218, dorm + ": the stair does not run from the basement (" + yLow + ") to the third floor (" + yHigh + ")");
        }
    }

    @Test
    @DisplayName("From the basement door to the third-floor door of a dormitory the route climbs without a step down, at every rotation")
    void routeFromBasementToThirdFloorOnlyGoesUp() {
        for (int rotation = 0; rotation < 4; rotation++) {
            final int rot = rotation;
            SectLayoutFile f = SectLayoutFile.parse(new InputStreamReader(SectLayoutFile.class.getResourceAsStream(SectLayoutFile.RESOURCE), StandardCharsets.UTF_8),
                    frame -> new SectLayoutFile.Anchor(frame, frame.centerX(), frame.centerZ(), frame.baseY(), rot));
            for (String[] pair : new String[][] {{"door.08.a", "door.27.a"}, {"door.39.a", "door.59.a"}}) {
                SectLayoutFile.Route route = f.route(pair[0], pair[1]);
                assertNotNull(route, pair[0] + " -> " + pair[1] + " rot " + rot);
                int last = f.waypoint(route.nodes().get(0)).pos().getY();
                for (String id : route.nodes()) {
                    int y = f.waypoint(id).pos().getY();
                    assertTrue(y >= last, "rot " + rot + ": " + pair[0] + " -> " + pair[1] + " goes down at " + id + " (" + y + " after " + last + ")");
                    last = y;
                }
                assertTrue(route.nodes().stream().filter(id -> id.startsWith("door.")).count() == 2, "the way passes a door that is not the two ends: " + route.nodes());
            }
        }
    }

    @Test
    @DisplayName("«глава финансов не на своём стуле»: his armchair in the office is a reachable seat of his own role")
    void financierArmchairIsReachable() {
        SectLayoutFile f = SectLayoutFile.shipped();
        String office = SectPlaces.roomId(f, SectPlaces.Landmark.TREASURY_OFFICE);
        assertNotNull(office, "no office of the financier");
        List<SectLayoutFile.Spot> arm = f.spotsIn(office).stream().filter(s -> "seat_armchair".equals(s.kind()) && s.roles().contains("elder_treasury")).toList();
        assertEquals(1, arm.size(), "his armchair");
        assertEquals("ok", arm.get(0).reach(), "the armchair is walled in by the furniture (extractor: enclosed), so he takes a visitor's chair");
        assertEquals(Set.of("elder_treasury"), Set.copyOf(arm.get(0).roles()), "the armchair belongs to him alone");
    }

    @Test
    @DisplayName("«на собрании старейшин просто уходят»: an intruder of the council is taken down the stair, out of the whole storey of the council")
    void councilExitIsOutsideTheStorey() {
        SectLayoutFile f = SectLayoutFile.shipped();
        SectPlaces.Pick door = SectPlaces.councilDoor(f);
        SectPlaces.Pick exit = SectPlaces.councilExit(f);
        assertNotNull(door);
        assertNotNull(exit);
        SectLayoutFile.Room hall = f.room(SectPlaces.roomId(f, SectPlaces.Landmark.COUNCIL_HALL));
        assertTrue(exit.y() < hall.y() - 1, "the exit (y " + exit.y() + ") is still on the storey of the council (y " + hall.y() + ")");
        for (SectLayoutFile.Room r : f.rooms()) {
            if (hall.building().equals(r.building()) && r.floor() == hall.floor() && r.bounds().contains((int) Math.floor(exit.x()), (int) Math.floor(exit.z()))) {
                assertTrue(exit.y() < r.y() - 1.0D, "the exit is inside " + r.id());
            }
        }
    }

    @Test
    @DisplayName("«двери не закрываются, ворота открыты нараспашку»: the baked sect holds no door, big door or gate that is built open")
    void bakedSectHasNoOpenDoor() throws Exception {
        JsonObject index;
        try (InputStream in = SectCheckTest.class.getResourceAsStream("/data/murim/hua_baked/index.json")) {
            index = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Set<String> doors = Set.of("murim:hall_door", "murim:hall_grand_door", "murim:gate_door");
        List<String> open = new ArrayList<>();
        int files = 0;
        for (JsonElement g : index.getAsJsonArray("groups")) {
            for (JsonElement c : g.getAsJsonObject().getAsJsonArray("cells")) {
                String file = c.getAsJsonObject().get("file").getAsString();
                try (InputStream in = SectCheckTest.class.getResourceAsStream("/data/murim/hua_baked/" + file)) {
                    assertNotNull(in, file);
                    CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
                    ListTag palette = tag.getList("palette", 10);
                    for (int i = 0; i < palette.size(); i++) {
                        CompoundTag p = palette.getCompound(i);
                        if (doors.contains(p.getString("Name")) && "true".equals(p.getCompound("Properties").getString("open"))) {
                            open.add(file + " " + p.getString("Name"));
                        }
                    }
                    files++;
                }
            }
        }
        assertTrue(files > 200, "baked cells read: " + files);
        assertFalse(!open.isEmpty(), "doors built open in " + open.size() + " cells, e.g. " + open.subList(0, Math.min(5, open.size())));
    }

    @Test
    @DisplayName("«в главном зале нет охраны», «и там тоже нет охраны»: the day guards of the hall and of the treasury stand at their doors most of the day")
    void doorGuardsAreAtTheirDoorsMostOfTheDay() {
        for (int post = 0; post < 2; post++) {
            String place = post == 0 ? "main_hall" : "treasury";
            int at = 0;
            int all = 0;
            for (int t = 2000; t < 23000; t += 50) {
                all++;
                if (place.equals(SectRota.stopZone(new SectRota.Duty(post, false), t))) {
                    at++;
                }
            }
            assertTrue(at * 100 / all >= 60, "the guard of " + place + " is at his door " + at * 100 / all + " % of the day");
        }
    }

    @Test
    @DisplayName("A person on the foot of a bed must be able to walk on: no wall scroll hangs over the foot of a baked bed (the pathfinder counts it as a wall)")
    void noScrollOverTheFootOfABed() throws Exception {
        JsonObject index;
        try (InputStream in = SectCheckTest.class.getResourceAsStream("/data/murim/hua_baked/index.json")) {
            index = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> bad = new ArrayList<>();
        int beds = 0;
        for (JsonElement g : index.getAsJsonArray("groups")) {
            for (JsonElement c : g.getAsJsonObject().getAsJsonArray("cells")) {
                String file = c.getAsJsonObject().get("file").getAsString();
                try (InputStream in = SectCheckTest.class.getResourceAsStream("/data/murim/hua_baked/" + file)) {
                    CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
                    ListTag palette = tag.getList("palette", 10);
                    ListTag blocks = tag.getList("blocks", 10);
                    java.util.Map<Long, String> at = new java.util.HashMap<>();
                    for (int i = 0; i < blocks.size(); i++) {
                        CompoundTag b = blocks.getCompound(i);
                        ListTag pos = b.getList("pos", 3);
                        at.put(((long) pos.getInt(0) << 40) ^ ((long) pos.getInt(1) << 20) ^ pos.getInt(2), palette.getCompound(b.getInt("state")).getString("Name")
                                + "|" + palette.getCompound(b.getInt("state")).getCompound("Properties").getString("part"));
                    }
                    for (int i = 0; i < blocks.size(); i++) {
                        CompoundTag b = blocks.getCompound(i);
                        CompoundTag st = palette.getCompound(b.getInt("state"));
                        if (st.getString("Name").startsWith("murim:sto_bed") && "foot".equals(st.getCompound("Properties").getString("part"))) {
                            beds++;
                            ListTag pos = b.getList("pos", 3);
                            String above = at.get(((long) pos.getInt(0) << 40) ^ ((long) (pos.getInt(1) + 1) << 20) ^ pos.getInt(2));
                            if (above != null && above.startsWith("murim:shr_scroll")) {
                                bad.add(file + " " + pos.getInt(0) + "," + pos.getInt(1) + "," + pos.getInt(2));
                            }
                        }
                    }
                }
            }
        }
        assertTrue(beds > 30, "bed feet read: " + beds);
        assertTrue(bad.isEmpty(), "scrolls over the foot of a bed: " + bad);
    }
}
