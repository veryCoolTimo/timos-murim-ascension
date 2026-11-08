package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 18.10 review of the sect code: rooms named by what they are, the formation walk at every rotation, stair directions. */
class SectReviewFixesTest {

    private static final SectLayoutFile FILE = SectLayoutFile.shipped();

    // ------------------------------------------------------------------ rooms named by what they are

    @Test
    @DisplayName("Every landmark room the day names resolves in the shipped layout, to the room it was written for")
    void landmarksResolveInTheShippedLayout() {
        Map<SectPlaces.Landmark, String> expected = Map.of(
                SectPlaces.Landmark.LIBRARY, "scriptures.f0.r1",
                SectPlaces.Landmark.AUDIENCE_HALL, "main_hall.f0.r1",
                SectPlaces.Landmark.DAIS, "main_hall.f0.r7",
                SectPlaces.Landmark.STUDY, "main_hall.f3.r3",
                SectPlaces.Landmark.STEWARD_DESK_ROOM, "dorm_3rd.f1.r2",
                SectPlaces.Landmark.HEALER_LOUNGE, "dorm_3rd.f2.r3",
                SectPlaces.Landmark.TREASURY_STORE, "treasury.f0.r2",
                SectPlaces.Landmark.COUNCIL_HALL, "main_hall.f2.r2",
                SectPlaces.Landmark.COUNCIL_DAIS, "main_hall.f2.r3",
                SectPlaces.Landmark.TREASURY_OFFICE, "treasury.f2.r1");
        for (SectPlaces.Landmark l : SectPlaces.Landmark.values()) {
            String id = SectPlaces.roomId(FILE, l);
            assertNotNull(id, l + " does not resolve");
            assertNotNull(FILE.room(id), l + " resolves to a room that does not exist: " + id);
            assertEquals(expected.get(l), id, l.name());
        }
        assertEquals(expected.size(), SectPlaces.Landmark.values().length, "a landmark without an expected room");
    }

    /** The shipped file with some room ids exchanged for others, everywhere they are written. */
    private static SectLayoutFile renumbered(Map<String, String> swap) throws IOException {
        String json;
        try (var in = SectLayoutFile.class.getResourceAsStream(SectLayoutFile.RESOURCE)) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> temp = new HashMap<>();
        int i = 0;
        for (String id : swap.keySet()) {
            temp.put(id, "\"@@" + i++ + "@@\"");
            json = json.replace("\"" + id + "\"", temp.get(id));
        }
        for (var e : swap.entrySet()) {
            json = json.replace(temp.get(e.getKey()), "\"" + e.getValue() + "\"");
        }
        return SectLayoutFile.parse(new StringReader(json), SectLayoutFile.Anchor::identity);
    }

    @Test
    @DisplayName("A building that changes renumbers its rooms: the landmarks are found again by building, function and furniture")
    void landmarksFollowARenumbering() throws IOException {
        Map<String, String> swap = new HashMap<>();
        swap.put("main_hall.f0.r1", "main_hall.f0.r7");    // the audience hall and the dais exchange numbers
        swap.put("main_hall.f0.r7", "main_hall.f0.r1");
        swap.put("dorm_3rd.f1.r2", "dorm_3rd.f3.r6");      // the steward's room and the single room of the top floor
        swap.put("dorm_3rd.f3.r6", "dorm_3rd.f1.r2");
        swap.put("scriptures.f0.r1", "scriptures.f0.r9");
        SectLayoutFile moved = renumbered(swap);
        assertEquals("main_hall.f0.r7", SectPlaces.roomId(moved, SectPlaces.Landmark.AUDIENCE_HALL));
        assertEquals("main_hall.f0.r1", SectPlaces.roomId(moved, SectPlaces.Landmark.DAIS));
        assertEquals("dorm_3rd.f3.r6", SectPlaces.roomId(moved, SectPlaces.Landmark.STEWARD_DESK_ROOM));
        assertEquals("scriptures.f0.r9", SectPlaces.roomId(moved, SectPlaces.Landmark.LIBRARY));
        // the leader's audience goes to the moved dais: its spots are in the room that now has the other number
        SectRoster leader = SectRoster.of("tae_hwi").orElseThrow();
        int audiences = 0;
        for (long time = 0; time < 24000; time += 100) {
            SectSchedule.Task t = SectPlaces.adjust(leader, SectSchedule.task(leader, SectPlaces.time(moved, leader, time)), time);
            if (t.kind() != SectSchedule.Kind.WATCH || SectSchedule.at(time) != SectSchedule.Period.TRAINING) {
                continue;
            }
            // the head receives on the steps now; the dais is still found for the seat he takes in the other hours
            t = new SectSchedule.Task(SectSchedule.Kind.RECEIVE, "main_hall", 0.0D, 4.0D, 0.0D, -1.0D);
            SectPlaces.Pick p = SectPlaces.pick(moved, leader, t, time, new SectSpots(), 0, null);
            assertNotNull(p, "no place for the audience at " + time);
            assertEquals("main_hall.f0.r1", p.room(), "the audience is not on the dais");
            audiences++;
        }
        assertTrue(audiences > 0, "the leader never receives in the training hours");
    }

    @Test
    @DisplayName("A layout without the landmark has none: nothing is picked in a room that does not exist")
    void noLandmarkInAThinLayout() {
        SectLayoutFile thin = SectLayoutFile.parse(new StringReader("{\"frame\": {\"center_x\": 0, \"center_z\": 0, \"base_y\": 0, \"rotation\": 0}}"),
                SectLayoutFile.Anchor::identity);
        for (SectPlaces.Landmark l : SectPlaces.Landmark.values()) {
            assertNull(SectPlaces.roomId(thin, l), l.name());
        }
    }

    // ------------------------------------------------------------------ the mentor's walk between the formation rows

    private static final int FORM_X = -1066;
    private static final int FORM_Z = 4222;

    private static String formationJson() {
        StringBuilder sb = new StringBuilder("{\"frame\": {\"center_x\": 0, \"center_z\": 0, \"base_y\": 0, \"rotation\": 0}, \"spots\": [");
        int n = 0;
        for (int row = 0; row < 7; row++) {
            for (int col = 0; col < 5; col++) {
                if (n++ > 0) {
                    sb.append(',');
                }
                sb.append("{\"id\": \"spot.formation.").append(n).append("\", \"kind\": \"formation\", \"posture\": \"stand\", \"pos\": [")
                        .append(FORM_X - 6 + col * 3).append(", 226, ").append(FORM_Z - 9 + row * 3).append("], \"row\": ").append(row)
                        .append(", \"col\": ").append(col).append(", \"roles\": [\"disciple_3rd\"], \"reach\": \"ok\"}");
            }
        }
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("The mentor's inspection points are worked out in the frame of the formation: the site may stand at any rotation")
    void inspectPointsFollowTheFormationAtEveryRotation() {
        SectLayoutFile base = SectLayoutFile.parse(new StringReader(formationJson()), SectLayoutFile.Anchor::identity);
        // the untouched frame: west end 3 blocks before the first column, east end 3 after the last, rows between the lines
        assertEquals(new BlockPos(FORM_X - 6 - 3, 226, FORM_Z - 9 - 1), SectPlaces.inspectPoint(base, 0, 0));
        assertEquals(new BlockPos(FORM_X + 6 + 3, 226, FORM_Z - 9 + 3 - 1), SectPlaces.inspectPoint(base, 1, 1));
        assertEquals(new BlockPos(FORM_X - 6 - 3, 226, FORM_Z + 9 + 2), SectPlaces.inspectPoint(base, 7, 0));
        for (int rotation = 0; rotation < 4; rotation++) {
            int turn = rotation;
            SectLayoutFile.Frame[] frame = new SectLayoutFile.Frame[1];
            SectLayoutFile site = SectLayoutFile.parse(new StringReader(formationJson()), f -> {
                frame[0] = f;
                return new SectLayoutFile.Anchor(f, 1200, -800, 70, turn);
            });
            SectLayoutFile.Anchor anchor = new SectLayoutFile.Anchor(frame[0], 1200, -800, 70, turn);
            for (int line = 0; line <= 7; line++) {
                for (int end = 0; end <= 1; end++) {
                    assertEquals(anchor.map(SectPlaces.inspectPoint(base, line, end)), SectPlaces.inspectPoint(site, line, end),
                            "rotation " + rotation + " line " + line + " end " + end);
                }
            }
        }
    }

    // ------------------------------------------------------------------ stairs know which way they go

    private static void checkDirections(SectRouter.Plan plan) {
        Map<Integer, SectRouter.Leg> endOf = new HashMap<>();
        for (SectRouter.Leg l : plan.legs()) {
            if (l.edgeEnd) {
                endOf.put(l.edge, l);
            }
        }
        SectRouter.Leg previous = plan.legs().get(0);
        for (SectRouter.Leg l : plan.legs()) {
            if (l.edgeStart) {
                assertEquals(Integer.signum(endOf.get(l.edge).y - previous.y), l.dir, "direction of edge " + l.edge);
            }
            if (l.edgeEnd) {
                previous = l;
            }
        }
    }

    @Test
    @DisplayName("A stair edge with no cells between its ends still climbs or descends in the walker's direction")
    void stairEdgesWithoutViaKnowTheirDirection() {
        int planned = 0;
        int climbing = 0;
        int descending = 0;
        for (SectLayoutFile.Edge e : FILE.edges()) {
            if (!e.stairs() || !e.via().isEmpty()) {
                continue;
            }
            for (boolean forward : new boolean[] {true, false}) {
                if (e.oneWay() && !forward) {
                    continue;
                }
                BlockPos from = FILE.waypoint(forward ? e.a() : e.b()).pos();
                BlockPos to = FILE.waypoint(forward ? e.b() : e.a()).pos();
                SectRouter.Plan plan = SectRouter.plan(FILE, from, to, Set.of());
                if (plan == null) {
                    continue;
                }
                planned++;
                checkDirections(plan);
                for (SectRouter.Leg l : plan.legs()) {
                    climbing += l.edgeStart && l.dir > 0 ? 1 : 0;
                    descending += l.edgeStart && l.dir < 0 ? 1 : 0;
                }
            }
        }
        assertTrue(planned >= 8, "too few stair edges planned: " + planned);
        assertTrue(climbing > 0 && descending > 0, "no climbing (" + climbing + ") or no descending (" + descending + ") leg");
    }

    @Test
    @DisplayName("Whole routes across the sect: the direction of every edge leg follows the heights of its ends")
    void longRoutesKeepTheirDirections() {
        String[] ids = FILE.waypoints().stream().map(SectLayoutFile.Waypoint::id).sorted().toArray(String[]::new);
        int checked = 0;
        for (int i = 0; i < ids.length; i += 17) {
            for (int j = 5; j < ids.length; j += 29) {
                SectRouter.Plan plan = SectRouter.plan(FILE, FILE.waypoint(ids[i]).pos(), FILE.waypoint(ids[j]).pos(), Set.of());
                if (plan != null) {
                    checkDirections(plan);
                    checked++;
                }
            }
        }
        assertTrue(checked > 20, "too few routes: " + checked);
    }

    // ------------------------------------------------------------------ the layout file belongs to the baked mountain

    @Test
    @DisplayName("An old world without the baked mountain has no built-sect file; the baked one has")
    void layoutFileOnlyOnTheCanonicalMountain() {
        assertNull(SectLayout.hua(new MountHuaSite(100, 100, 70, 0, 20261003L)).file(), "an old world got the baked sect");
        assertNotNull(SectLayout.hua(new MountHuaSite(100, 100, 70, 0, 20261003L, true)).file(), "the baked mountain lost its sect");
    }

    // ------------------------------------------------------------------ beds the layout keeps for somebody

    private record Plain(SectLayoutFile file) implements SectLayout {
        @Override public Vec3 at(String zone, double du, double dv) { return null; }
        @Override public float yaw(double du, double dv) { return 0.0F; }
        @Override public double[] half(String zone) { return null; }
        @Override public double[] local(String zone, Vec3 pos) { return null; }
    }

    @Test
    @DisplayName("A bed with an owner, or of any role but spare, is never handed to a newcomer; a spare one may be")
    void reservedBedsAreNotForNewcomers() {
        SectLayout layout = new Plain(FILE);
        int spare = 0;
        int kept = 0;
        for (SectLayoutFile.Bed b : FILE.beds()) {
            boolean free = "spare".equals(b.role()) && (b.ownerKey() == null || b.ownerKey().isEmpty());
            assertEquals(!free, SectRooms.reserved(layout, b.head()), b.id() + " role " + b.role() + " owner " + b.ownerKey());
            spare += free ? 1 : 0;
            kept += free ? 0 : 1;
        }
        assertTrue(spare > 0 && kept > 0, "the shipped file has no spare or no owned beds");
        assertFalse(SectRooms.reserved(layout, new BlockPos(1, 1, 1)), "a bed the file does not know is nobody's");
        assertFalse(SectRooms.reserved(null, new BlockPos(1, 1, 1)));
    }

    // ------------------------------------------------------------------ the polite refusal at the board

    @Test
    @DisplayName("The refusal for a novice at the contract board exists in English and in Russian")
    void lowStandingLineIsTranslated() throws IOException {
        for (String lang : new String[] {"en_us", "ru_ru"}) {
            try (var in = SectReviewFixesTest.class.getResourceAsStream("/assets/murim/lang/" + lang + ".json")) {
                var json = com.google.gson.JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                assertTrue(json.has("murim.contract.low_standing") && !json.get("murim.contract.low_standing").getAsString().isBlank(), lang);
            }
        }
    }
}
