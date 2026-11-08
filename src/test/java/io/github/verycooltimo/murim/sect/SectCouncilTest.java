package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.sect.SectEventsFile.Council;
import io.github.verycooltimo.murim.sect.SectSchedule.Kind;
import io.github.verycooltimo.murim.sect.SectSchedule.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The council of elders and the invitations of the offices (docs/design/38, «Совет и приглашения»): the clock, the places, the table. */
class SectCouncilTest {

    private static final SectLayoutFile FILE = SectLayoutFile.shipped();
    private static final Council C = SectCouncil.config();

    private static SectRoster m(String key) {
        return SectRoster.of(key).orElseThrow();
    }

    /** First sect day of the week on which the council meets, and one on which it does not. */
    private static long councilDay() {
        for (long d = 0; d < SectEventsFile.WEEK; d++) {
            if (C.onDay(d)) {
                return d;
            }
        }
        throw new AssertionError("the council never meets");
    }

    private static long quietDay() {
        for (long d = 0; d < SectEventsFile.WEEK; d++) {
            if (!C.onDay(d) && !SectReview.reviewDay(d)) {
                return d;
            }
        }
        throw new AssertionError("the council meets every day");
    }

    @Test
    @DisplayName("events.json: a few days a week, never on the review day, the clock in order, everybody exists")
    void theFileIsSane() {
        assertTrue(C.days().size() >= 2 && C.days().size() <= 4, "a few times a week: " + C.days());
        for (int d : C.days()) {
            assertTrue(d >= 0 && d < SectEventsFile.WEEK, "day of the week " + d);
            assertFalse(SectReview.reviewDay(d), "the review of the juniors is on day " + d);
        }
        assertTrue(C.call() < C.gather(), "the elder is called before anybody sets out");
        assertTrue(C.gather() < C.from() && C.from() < C.tea() && C.tea() < C.leave() && C.leave() < C.to(), "the clock: " + C);
        assertTrue(C.to() < 9000, "the council is over before dinner");
        assertTrue(C.leave() - C.tea() >= 1000, "the tea round has time to be served (a round is about 600 ticks)");
        assertEquals(6, C.members().size());
        assertEquals("tae_hwi", C.members().get(0), "the head sits first");
        for (String key : C.members()) {
            assertTrue(SectRoster.of(key).isPresent(), key);
        }
        assertTrue(SectRoster.of(C.servant()).orElseThrow().lay(), "the tea is poured by a servant");
        for (var inv : SectEventsFile.shipped().invitations()) {
            assertTrue(inv.office().equals("elder") || inv.office().equals("first"), inv.id());
            assertFalse(inv.days().isEmpty(), inv.id());
            assertTrue(inv.hold() >= 0 && inv.contribution() > 0, inv.id());
            if (!inv.host().isEmpty()) {
                assertTrue(SectRoster.of(inv.host()).isPresent(), inv.id() + " host " + inv.host());
            }
        }
    }

    @Test
    @DisplayName("One elder keeps his post: the gate elder never sets out for the council, so contracts can still be handed in")
    void oneElderKeepsHisPost() {
        long day = councilDay() * SectSchedule.DAY;
        assertTrue(SectCouncil.stays("gyeong_tak"));
        assertFalse(SectCouncil.stays("tae_gyun"));
        assertFalse(SectCouncil.goes("gyeong_tak", day + C.from()));
        assertTrue(SectCouncil.goes("tae_hwi", day + C.from()));
        for (long t = C.gather(); t < C.to(); t += 100) {
            assertNotEquals(Kind.COUNCIL, SectSchedule.task(m("gyeong_tak"), day + t).kind());
        }
    }

    @Test
    @DisplayName("The farthest set out first: the financier from the Finance Hall long before the head from the hall above")
    void eachSetsOutInHisOwnTime() {
        assertTrue(C.leadOf("tae_gyun") > C.leadOf("tae_hwi"), "the treasury is farther than the head's own hall");
        long day = councilDay() * SectSchedule.DAY;
        assertFalse(SectCouncil.goes("tae_gyun", day + C.from() - C.leadOf("tae_gyun") - 1));
        assertTrue(SectCouncil.goes("tae_gyun", day + C.from() - C.leadOf("tae_gyun")));
        assertFalse(SectCouncil.goes("tae_hwi", day + C.from() - C.leadOf("tae_gyun")));
        assertTrue(SectCouncil.goes("tae_hwi", day + C.from() - C.leadOf("tae_hwi")));
        assertFalse(SectCouncil.goes("tae_hwi", day + C.to()));
        long quiet = quietDay() * SectSchedule.DAY;
        for (String key : C.members()) {
            assertFalse(SectCouncil.goes(key, quiet + C.from()), key);
        }
        assertEquals(SectCouncil.Phase.NONE, SectCouncil.phase(quiet + C.from()));
        assertEquals(SectCouncil.Phase.GATHER, SectCouncil.phase(day + C.from() - 10));
        assertEquals(SectCouncil.Phase.SESSION, SectCouncil.phase(day + C.from() + 10));
        assertEquals(SectCouncil.Phase.LEAVE, SectCouncil.phase(day + C.leave() + 10));
        assertEquals(SectCouncil.Phase.NONE, SectCouncil.phase(day + C.to()));
        assertFalse(SectCouncil.teaServed(day + C.tea() - 10));
        assertTrue(SectCouncil.teaServed(day + C.tea() + 10));
    }

    @Test
    @DisplayName("On a council day the members sit, the servant brings the tea, the front guard stands at the door; the day after is ordinary")
    void theScheduleOfTheDay() {
        long day = councilDay() * SectSchedule.DAY;
        long mid = day + (C.tea() + C.leave()) / 2;
        for (String key : C.members()) {
            Task t = SectSchedule.task(m(key), mid);
            if (SectCouncil.stays(key)) {
                assertNotEquals(Kind.COUNCIL, t.kind(), key + " keeps his post during the council");
                continue;
            }
            assertEquals(Kind.COUNCIL, t.kind(), key);
        }
        assertTrue(C.members().contains(SectCouncil.POST), "the elder who stays is a member of the council");
        assertEquals(Kind.TEA, SectSchedule.task(m(C.servant()), mid).kind());
        boolean door = false;
        for (SectRoster r : SectRoster.ALL) {
            Task t = SectSchedule.task(r, mid);
            if (t.kind() == Kind.GUARD && SectSchedule.COUNCIL_DOOR.equals(t.partner())) {
                door = true;
                assertEquals(2, r.generation(), "a second-generation guard keeps the door");
            }
        }
        assertTrue(door, "nobody keeps the door of the council hall");
        // after the end everybody is back at the day's business
        long after = day + C.to() + 5;
        for (String key : C.members()) {
            assertNotEquals(Kind.COUNCIL, SectSchedule.task(m(key), after).kind(), key);
        }
        assertNotEquals(Kind.TEA, SectSchedule.task(m(C.servant()), after).kind());
        // an ordinary day: nobody sits at the council
        long quiet = quietDay() * SectSchedule.DAY + mid % SectSchedule.DAY;
        for (SectRoster r : SectRoster.ALL) {
            Kind k = SectSchedule.task(r, quiet).kind();
            assertTrue(k != Kind.COUNCIL && k != Kind.TEA, r.key() + " " + k);
        }
    }

    @Test
    @DisplayName("The financier works in his office all the working day and eats at his desk: he never walks to the dining hall or to the head")
    void theFinancierStaysInHisOffice() {
        SectRoster gyun = m("tae_gyun");
        long day = quietDay() * SectSchedule.DAY;
        Set<String> places = new HashSet<>();
        for (int tick = 0; tick < 9000; tick += 100) {
            long time = day + tick;
            long t = SectPlaces.time(FILE, gyun, time);
            Task task = SectPlaces.adjust(gyun, SectSchedule.task(gyun, t), time);
            assertTrue(task.kind() == Kind.COUNT || task.kind() == Kind.EAT, "tick " + tick + ": " + task.kind());
            SectPlaces.Pick p = SectPlaces.pick(FILE, gyun, task, time, new SectSpots(), time, null);
            assertNotNull(p, "tick " + tick);
            assertEquals("treasury.f2.r1", p.room(), "the financier's office at tick " + tick + " (" + task.kind() + ")");
            places.add(p.spotId());
        }
        assertEquals(1, places.size(), "he keeps his own desk and does not wander between spots: " + places);
    }

    @Test
    @DisplayName("The council hall: the head on the mat of the dais, four elders on four cushions, the sixth cushion free for a player")
    void theSeatsAtTheTable() {
        assertEquals("main_hall.f2.r2", SectPlaces.roomId(FILE, SectPlaces.Landmark.COUNCIL_HALL));
        long day = councilDay() * SectSchedule.DAY;
        long mid = day + (C.tea() + C.leave()) / 2;
        Set<String> seats = new HashSet<>();
        for (String key : C.members()) {
            if (SectCouncil.stays(key)) continue;
            Task t = SectSchedule.task(m(key), mid);
            SectPlaces.Pick p = SectPlaces.pick(FILE, m(key), t, mid, new SectSpots(), mid, null);
            assertNotNull(p, key);
            assertTrue(seats.add(p.spotId()), "two at one seat: " + key);
            SectLayoutFile.Spot s = FILE.spot(p.spotId());
            assertNotNull(s, p.spotId());
            if (key.equals("tae_hwi")) {
                assertEquals("kneel_mat", s.kind(), "the head sits on the mat of the dais");
                assertEquals("main_hall.f2.r3", s.room());
            } else {
                assertEquals("cushion", s.kind(), key);
                assertEquals("main_hall.f2.r2", s.room(), key);
            }
        }
        List<SectLayoutFile.Spot> cushions = SectPlaces.councilCushions(FILE);
        assertEquals(6, cushions.size());
        assertEquals(6, SectPlaces.councilDesks(FILE).size());
        long free = cushions.stream().filter(c -> !seats.contains(c.id())).count();
        assertEquals(2, free, "one cushion is left for an elder who is a player, one for the elder who keeps his post");
        // the front row sits first: the order of the file is the order at the table
        assertTrue(cushions.get(0).pos().getZ() <= cushions.get(2).pos().getZ());
    }

    @Test
    @DisplayName("The servant waits in the aisle, the guard stands at the door from the stair, the quarrel is in the forecourt")
    void theOtherPlaces() {
        long day = councilDay() * SectSchedule.DAY;
        long mid = day + (C.tea() + C.leave()) / 2;
        SectRoster servant = m(C.servant());
        SectPlaces.Pick wait = SectPlaces.pick(FILE, servant, SectSchedule.task(servant, mid), mid, new SectSpots(), mid, null);
        assertNotNull(wait);
        assertEquals("main_hall.f2.r2", wait.room());
        assertEquals(wait.room(), FILE.roomAt(wait.cell()).id(), "the servant's waiting cell is inside the council hall");
        SectPlaces.Pick door = SectPlaces.councilDoor(FILE);
        assertNotNull(door, "the door of the council hall");
        assertEquals("main_hall.f2.r1", door.room(), "the guard stands on the landing of the stair, outside the door");
        // the dispute: the two juniors face each other across the middle of the court, on the day of the invitation
        long dispute = -1;
        for (long d = 0; d < SectEventsFile.WEEK; d++) {
            if (SectEventsFile.shipped().invitation("dispute").onDay(d)) {
                dispute = d;
            }
        }
        assertTrue(dispute >= 0);
        long time = dispute * SectSchedule.DAY + SectEventsFile.shipped().invitation("dispute").from() + 50;
        List<SectRoster> pair = SectOffices.disputePair(dispute);
        assertEquals(2, new HashSet<>(pair).size());
        Task a = SectSchedule.task(pair.get(0), time);
        Task b = SectSchedule.task(pair.get(1), time);
        assertEquals(Kind.ARGUE, a.kind());
        assertEquals(Kind.ARGUE, b.kind());
        assertEquals(b.partner(), pair.get(0).key());
        SectPlaces.Pick pa = SectPlaces.pick(FILE, pair.get(0), a, time, new SectSpots(), time, null);
        SectPlaces.Pick pb = SectPlaces.pick(FILE, pair.get(1), b, time, new SectSpots(), time, null);
        assertNotNull(pa);
        assertNotNull(pb);
        assertEquals(2.0D, Math.abs(pa.x() - pb.x()) + Math.abs(pa.z() - pb.z()), 1e-9, "two blocks apart");
        // no pair of the training is broken: the quarrellers are two who sweep the yards that day
        for (SectRoster r : pair) {
            assertEquals(Kind.CHORE, SectSchedule.task(r, SectSchedule.Period.TRAINING, dispute).kind(), r.key());
        }
        // and nobody else quarrels, nor on other days
        for (SectRoster r : SectRoster.ALL) {
            if (!pair.contains(r)) {
                assertNull(SectOffices.disputeTask(r, time), r.key());
            }
        }
        assertNull(SectOffices.disputeTask(pair.get(0), (dispute + 1) * SectSchedule.DAY + SectEventsFile.shipped().invitation("dispute").from()));
    }

    @Test
    @DisplayName("Two scribes copy scrolls in the Hall of Scriptures every working day; all of them find a place")
    void scribesInTheLibrary() {
        for (long day = 0; day < 14; day++) {
            if (SectReview.reviewDay(day) || C.onDay(day)) {
                continue;
            }
            int readers = 0;
            SectSpots spots = new SectSpots();
            long time = day * SectSchedule.DAY + 5400;
            for (SectRoster r : SectRoster.ALL) {
                Task t = SectPlaces.adjust(r, SectSchedule.task(r, SectPlaces.time(FILE, r, time)), time);
                if (t.kind() == Kind.READ) {
                    readers++;
                    SectPlaces.Pick p = SectPlaces.pick(FILE, r, t, time, spots, time, null);
                    assertNotNull(p, r.key() + " on day " + day);
                    assertEquals("scriptures.f0.r1", p.room(), r.key());
                }
            }
            assertEquals(3, readers, "the scripture elder and two scribes on day " + day);
        }
    }

    @Test
    @DisplayName("The invitations: an elder is called to the council, a first disciple to the line; nobody else; answered once; nothing is forced")
    void theInvitations() {
        SectState elder = SectState.NONE.joined().with(SectPositions.ELDER);
        SectState first = SectState.NONE.joined().with(SectPositions.FIRST);
        SectState plain = SectState.NONE.joined();
        long day = councilDay() * SectSchedule.DAY;
        assertTrue(SectOffices.out(elder, day + C.call() - 1).stream().noneMatch(e -> e.id().equals("council")), "not called yet");
        assertTrue(SectOffices.out(elder, day + C.call() + 5).stream().anyMatch(e -> e.id().equals("council")));
        assertTrue(SectOffices.out(elder, day + C.leave() + 5).stream().noneMatch(e -> e.id().equals("council")), "the window closed: no invitation, no penalty");
        assertTrue(SectOffices.out(plain, day + C.call() + 5).isEmpty(), "a plain disciple is not invited to the council");
        assertTrue(SectOffices.out(first, day + C.call() + 5).stream().noneMatch(e -> e.id().equals("council")));
        // the first disciple leads the line on its days, in the dawn (the window runs through midnight)
        SectEventsFile.Invitation lead = SectEventsFile.shipped().invitation("lead_formation");
        long dawnDay = lead.days().iterator().next();
        assertTrue(SectOffices.out(first, (dawnDay - 1) * SectSchedule.DAY + lead.from() + 50).stream().anyMatch(e -> e.id().equals("lead_formation")),
                "the line of the sect day starts at the bell the evening before");
        assertTrue(SectOffices.out(first, dawnDay * SectSchedule.DAY + 300).stream().anyMatch(e -> e.id().equals("lead_formation")));
        assertTrue(SectOffices.out(first, dawnDay * SectSchedule.DAY + lead.to() + 5).stream().noneMatch(e -> e.id().equals("lead_formation")));
        assertTrue(SectOffices.out(elder, dawnDay * SectSchedule.DAY + 300).stream().noneMatch(e -> e.id().equals("lead_formation")), "the elder does not lead the line");
        // a disciplined member gets none
        assertTrue(SectOffices.out(elder.with(io.github.verycooltimo.murim.sect.seal.PenanceService.SENTENCED), day + C.call() + 5).isEmpty());
        // answered today: not out again; tomorrow is another day
        long sectDay = SectSchedule.day(day + C.call() + 5);
        SectState done = elder.record("office.done.council", sectDay);
        assertTrue(SectOffices.out(done, day + C.call() + 5).stream().noneMatch(e -> e.id().equals("council")));
    }

    @Test
    @DisplayName("A thin layout without a council hall falls back to the audience hall; nothing throws")
    void withoutACouncilHall() {
        SectLayoutFile thin = SectLayoutFile.parse(new StringReader("{\"frame\": {\"center_x\": 0, \"center_z\": 0, \"base_y\": 0, \"rotation\": 0}}"),
                SectLayoutFile.Anchor::identity);
        long day = councilDay() * SectSchedule.DAY;
        long mid = day + (C.tea() + C.leave()) / 2;
        for (String key : C.members()) {
            assertNull(SectPlaces.pick(thin, m(key), SectSchedule.task(m(key), mid), mid, new SectSpots(), mid, null), key);
        }
        assertNull(SectPlaces.pick(thin, m(C.servant()), SectSchedule.task(m(C.servant()), mid), mid, new SectSpots(), mid, null));
        assertNull(SectPlaces.councilDoor(thin));
        assertTrue(SectPlaces.councilCushions(thin).isEmpty());
    }
}
