package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.sect.SectLayoutFile.Bed;
import io.github.verycooltimo.murim.sect.SectLayoutFile.Spot;
import io.github.verycooltimo.murim.sect.SectSchedule.Kind;
import io.github.verycooltimo.murim.sect.SectSchedule.Period;
import io.github.verycooltimo.murim.sect.SectSchedule.Task;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Role x time of day -> a place in the built sect (docs/design/38, section 5.2). {@link SectSchedule} still says WHAT
 * a person does and WHEN ({@link Kind}, windows); this class says WHERE, from the spots, rooms and beds of
 * {@link SectLayoutFile}. Pure: no level access, so the whole day can be checked in a unit test.
 *
 * <p>A spot is chosen among the free spots of the right kind that the person may use (spot {@code roles} against the
 * person's tags) and that are walkable ({@code reach == ok}); enclosed, one-way and unreachable spots are never
 * chosen. Candidates are tried in a person-specific rotation, so people spread over a room instead of piling on
 * the first bench, and the choice is sticky through {@link SectSpots}. {@code null} from {@link #pick} means "no
 * place in the layout for this task": the caller keeps the plan-zone position (outdoor drills, test yards).
 *
 * <p>Fallbacks the author asked for: the treasurer works at his own house desk and the steward at the desk of the
 * room next to the dorm entrance while the treasury has no walkable route (its spots are {@code one_way_drop}).
 */
public final class SectPlaces {

    /** Floors wake from the top down, this many ticks apart (no meeting on 1-wide dorm stairs). */
    public static final int FLOOR_WAKE_STEP = 80;

    /**
     * A chosen place. {@code x, y, z}: where to walk (y = feet cell); {@code seat}: the seat block for sit spots;
     * {@code pickup}: second end of a carry route; {@code bed}: the bed head for SLEEP.
     */
    public record Pick(String spotId, double x, int y, double z, float yaw, BlockPos seat, String room, BlockPos pickup,
                       BlockPos bed, int bedFloor) {
        public BlockPos cell() {
            return BlockPos.containing(x, y, z);
        }

        Pick at(double nx, int ny, double nz) {
            return new Pick(spotId, nx, ny, nz, yaw, seat, room, pickup, bed, bedFloor);
        }
    }

    private static final List<String> ORDER = SectRoster.ALL.stream().map(SectRoster::key).toList();

    private SectPlaces() {
    }

    // ------------------------------------------------------------------ people

    /** Who the person is for spot role matching. */
    public static Set<String> tags(SectLayoutFile f, SectRoster m) {
        Set<String> t = new HashSet<>();
        t.add("all");
        String role = f.layoutRole(m.key());
        if (role != null) {
            t.add(role);
        }
        switch (m.key()) {
            case "tae_gyun" -> t.add("elder_treasury");
            case "tae_rok" -> t.add("elder_martial");
            case "tae_seong" -> t.add("elder_council");
            case "gyeong_cho" -> t.add("instructor_healer");
            case "gyeong_pil" -> t.add("instructor_mentor");
            case "cook_kim" -> t.add("cook");
            case "water_gu" -> t.add("water");
            case "herbalist_han" -> t.add("servant_herbalist");
            default -> {
            }
        }
        if (m.lay()) {
            t.add("servant");
        }
        if (m.generation() == 2) {
            t.add("guard_day");
            t.add("guard_night");
        }
        String house = house(f, m);
        if (house != null) {
            t.add("elder:" + house);
        }
        return t;
    }

    /** The elder house (building id) the person lives in, or null. */
    public static String house(SectLayoutFile f, SectRoster m) {
        if (m.role() != SectRole.ELDER || m.generation() != 0) {
            return null;
        }
        Bed b = f.bedFor(m.key(), ORDER);
        return b != null && b.building() != null && b.building().startsWith("elder_") ? b.building() : null;
    }

    /** The bed of a person (own named bed, else role and slot), or null. */
    public static Bed bed(SectLayoutFile f, SectRoster m) {
        return f.bedFor(m.key(), ORDER);
    }

    /** Dining wave: the young generation and the staff eat after the leaders, the teachers and the older pupils. */
    public static boolean secondWave(SectRoster m) {
        return m.role() != SectRole.GATEKEEPER && (m.generation() == 3 || m.lay());
    }

    // ------------------------------------------------------------------ intent adjustments

    /**
     * Where a task would be done at the dining table: elders and the first generation's teachers eat with everybody
     * (the old routine kept them at their desks during the meal).
     */
    public static Task adjust(SectRoster m, Task t, long dayTime) {
        Period p = SectSchedule.at(dayTime);
        if (p.meal() && m.role() == SectRole.ELDER && (t.kind() == Kind.WORK || t.kind() == Kind.COUNT || t.kind() == Kind.READ
                || t.kind() == Kind.GRIND)) {
            if (eatsAtDesk(m)) {
                // The elders take their meals in their own halls, at the desk they work at (19.10): the dining hall is 250 steps from
                // the treasury, and the financier spent the day on the road instead of in his office.
                return new Task(Kind.EAT, t.zone(), t.du(), t.dv(), t.faceU(), t.faceV(), DESK + t.kind().name());
            }
            return new Task(Kind.EAT, "dining", 0.0D, 0.0D, 0.0D, 0.0D);
        }
        // elders-2: the head takes his meal at the seat of the main hall, the tray brought to him; no walk to the dining hall.
        if (p.meal() && m.role() == SectRole.LEADER && t.kind() == Kind.RECEIVE) {
            return new Task(Kind.EAT, t.zone(), t.du(), t.dv(), t.faceU(), t.faceV(), DESK + t.kind().name());
        }
        return t;
    }

    /** Marks a meal taken at the desk of the work in {@link Task#partner()}: the name of the kind of that work follows. */
    static final String DESK = "desk:";

    /** The three elders of the Tae line and the healer eat at their own halls; the gate captain eats with the first generation. */
    static boolean eatsAtDesk(SectRoster m) {
        return m.role() == SectRole.ELDER && !"gyeong_tak".equals(m.key());
    }

    /** Length of a meal period in ticks. */
    static int mealLength(Period p) {
        return p == Period.BREAKFAST ? 1000 : 2000;
    }

    /**
     * Schedule time for the person. Two dining waves (the dining hall seats about 27 of 44 people): the first wave
     * (leaders, teachers, older pupils) eats in the first half of the meal and then goes on to the next duty; the
     * second wave (young pupils and staff) keeps its previous duty through the first half and eats in the second.
     * Sleepers on the upper floors wake first, so nobody meets on a one-wide dorm stair.
     */
    public static long time(SectLayoutFile f, SectRoster m, long dayTime) {
        long t = dayTime;
        Period p = SectSchedule.at(t);
        if (p.meal()) {
            int since = SectSchedule.sincePeriodStart(t);
            int len = mealLength(p);
            int half = len / 2;
            // A guard's break meal has its own turn in the period (SectRota#meal): no wave shift for it.
            boolean eats = SectRota.meal(m, t).isEmpty() && adjust(m, SectSchedule.task(m, t), t).kind() == Kind.EAT;
            if (eats && secondWave(m) && since < half) {
                return t - since - 1;
            }
            if (eats && !secondWave(m) && since >= half) {
                return t + (len - since);
            }
        }
        if (p == Period.FORMATION && SectStagger.staggered(m)) {
            Bed b = bed(f, m);
            SectLayoutFile.Room r = b == null ? null : f.room(b.room());
            if (r != null) {
                // floors of the dorms are 0 (cellar) .. 3; wake the top floor first
                t -= (long) Math.max(0, 3 - r.floor()) * FLOOR_WAKE_STEP;
            }
        }
        return t;
    }

    // ------------------------------------------------------------------ spots

    private static List<Spot> spots(SectLayoutFile f, Predicate<Spot> p) {
        List<Spot> out = new ArrayList<>();
        for (Spot s : f.spots()) {
            if ("ok".equals(s.reach()) && p.test(s)) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparing(Spot::id));
        return out;
    }

    private static Predicate<Spot> in(String building) {
        return s -> building.equals(s.building());
    }

    private static Predicate<Spot> room(String room) {
        return s -> room.equals(s.room());
    }

    /** The spots of a landmark room (none at all when the file has no such room). */
    private static Predicate<Spot> at(SectLayoutFile f, Landmark l) {
        String id = roomId(f, l);
        return s -> id != null && id.equals(s.room());
    }

    // ------------------------------------------------------------------ rooms named by what they are

    /**
     * Rooms the day names by what they are. The generated ids ({@code main_hall.f0.r7}) number the rooms of a floor in the
     * order the extractor met them, so they renumber whenever a building changes; a landmark is found again by its
     * building, function and furniture instead (docs/design/38, review of 18.10).
     */
    public enum Landmark {
        /** The ground-floor reading room of the scriptures. */
        LIBRARY,
        /** The Main Hall's big hall on the ground floor. */
        AUDIENCE_HALL,
        /** The mat behind the low desk of the leader, in front of the hall. */
        DAIS,
        /** The leader's study desk. */
        STUDY,
        /** The room by the dorm entrance with a desk, where the steward works while the treasury is cut off. */
        STEWARD_DESK_ROOM,
        /** The meditation lounge of the third-generation dorm, the healer's rest. */
        HEALER_LOUNGE,
        /** The ground-floor room of the treasury that is walkable: the porter's drop. */
        TREASURY_STORE,
        /** The council hall on the second storey of the Main Hall: cushions and low desks down both sides of an aisle. */
        COUNCIL_HALL,
        /** The platform at the head of the council hall with the mats of the chairman. */
        COUNCIL_DAIS,
        /** The financier's own counting room: the counting room of the treasury with a chair, on the top floor. */
        TREASURY_OFFICE
    }

    /** The id of the landmark room in this file, or null when the file has none (a thinner layout than the shipped one). */
    public static String roomId(SectLayoutFile f, Landmark l) {
        return f.derived("landmark:" + l.name(), () -> resolve(f, l));
    }

    private static String resolve(SectLayoutFile f, Landmark l) {
        return switch (l) {
            case LIBRARY -> lowestRoom(f, "scriptures", r -> "library".equals(r.function()));
            case AUDIENCE_HALL -> lowestRoom(f, "main_hall", r -> "audience_hall".equals(r.function()));
            case DAIS -> lowestRoom(f, "main_hall", r -> hasSpot(f, r, s -> "low_desk".equals(s.kind()) && s.roles().contains("leader")));
            case STUDY -> lowestRoom(f, "main_hall", r -> "study".equals(r.function())
                    && hasSpot(f, r, s -> "work_desk".equals(s.kind()) && s.roles().contains("leader")));
            case STEWARD_DESK_ROOM -> lowestRoom(f, "dorm_3rd", r -> "single_room".equals(r.function())
                    && hasSpot(f, r, s -> "work_desk".equals(s.kind()) || "counter".equals(s.kind())));
            case HEALER_LOUNGE -> lowestRoom(f, "dorm_3rd", r -> "meditation_lounge".equals(r.function())
                    && hasSpot(f, r, s -> "cushion".equals(s.kind())));
            case TREASURY_STORE -> lowestRoom(f, "treasury", r -> r.floor() == 0 && !r.portals().isEmpty());
            case COUNCIL_HALL -> lowestRoom(f, "main_hall", r -> r.floor() >= 1 && countSpots(f, r, "cushion") >= 4);
            case COUNCIL_DAIS -> lowestRoom(f, "main_hall", r -> r.floor() >= 1
                    && hasSpot(f, r, s -> "kneel_mat".equals(s.kind()) && s.roles().contains("leader")));
            case TREASURY_OFFICE -> highestRoom(f, "treasury", r -> "counting_room".equals(r.function())
                    && hasSpot(f, r, s -> "seat_chair".equals(s.kind()) || "seat_armchair".equals(s.kind())));
        };
    }

    private static int countSpots(SectLayoutFile f, SectLayoutFile.Room r, String kind) {
        int n = 0;
        for (Spot s : f.spotsIn(r.id())) {
            if ("ok".equals(s.reach()) && kind.equals(s.kind())) {
                n++;
            }
        }
        return n;
    }

    /** The walkable room of the building that fits, on the highest floor; the larger one, then the smaller id, on a tie. */
    private static String highestRoom(SectLayoutFile f, String building, Predicate<SectLayoutFile.Room> fits) {
        SectLayoutFile.Room best = null;
        for (SectLayoutFile.Room r : f.rooms()) {
            if (!building.equals(r.building()) || !"ok".equals(r.reach()) || !fits.test(r)) {
                continue;
            }
            if (best == null || r.floor() > best.floor() || r.floor() == best.floor()
                    && (r.walkable() > best.walkable() || r.walkable() == best.walkable() && r.id().compareTo(best.id()) < 0)) {
                best = r;
            }
        }
        return best == null ? null : best.id();
    }

    private static boolean hasSpot(SectLayoutFile f, SectLayoutFile.Room r, Predicate<Spot> p) {
        for (Spot s : f.spotsIn(r.id())) {
            if ("ok".equals(s.reach()) && p.test(s)) {
                return true;
            }
        }
        return false;
    }

    /** The walkable room of the building that fits, on the lowest floor; the larger one, then the smaller id, on a tie. */
    private static String lowestRoom(SectLayoutFile f, String building, Predicate<SectLayoutFile.Room> fits) {
        SectLayoutFile.Room best = null;
        for (SectLayoutFile.Room r : f.rooms()) {
            if (!building.equals(r.building()) || !"ok".equals(r.reach()) || !fits.test(r)) {
                continue;
            }
            if (best == null || r.floor() < best.floor() || r.floor() == best.floor()
                    && (r.walkable() > best.walkable() || r.walkable() == best.walkable() && r.id().compareTo(best.id()) < 0)) {
                best = r;
            }
        }
        return best == null ? null : best.id();
    }

    private static Predicate<Spot> kind(String... kinds) {
        Set<String> set = Set.of(kinds);
        return s -> set.contains(s.kind());
    }

    private static Predicate<Spot> sitting() {
        return s -> "sit".equals(s.posture());
    }

    private static Predicate<Spot> mine(Set<String> tags) {
        return s -> s.roles().stream().anyMatch(tags::contains);
    }

    @SafeVarargs
    private static List<List<Spot>> groups(List<Spot>... lists) {
        List<List<Spot>> out = new ArrayList<>();
        for (List<Spot> l : lists) {
            if (!l.isEmpty()) {
                out.add(l);
            }
        }
        return out;
    }

    /** Spots of a kind list in a building, best fit for the person first (own roles), then the rest. */
    private static List<Spot> fit(SectLayoutFile f, Predicate<Spot> base, Set<String> tags) {
        return spots(f, base.and(mine(tags)));
    }

    // ------------------------------------------------------------------ choose

    private static boolean banned(Map<String, Long> bans, String id, long now) {
        if (bans == null) {
            return false;
        }
        Long until = bans.get(id);
        return until != null && until > now;
    }

    /**
     * First usable spot of the list for the owner. Sticky (a held spot stays), then the owner's rotation. With
     * {@code spots == null} nothing is claimed (peek).
     */
    static Spot choose(List<Spot> list, String owner, String salt, SectSpots spots, long now, Map<String, Long> bans) {
        int n = list.size();
        if (n == 0) {
            return null;
        }
        if (spots != null) {
            String held = spots.heldBy(owner, now);
            if (held != null) {
                for (Spot s : list) {
                    if (s.id().equals(held) && !banned(bans, s.id(), now) && spots.claim(s.id(), owner, now)) {
                        return s;
                    }
                }
            }
        }
        int start = (int) Math.floor(SectStagger.unit(owner, salt) * n) % n;
        for (int i = 0; i < n; i++) {
            Spot s = list.get((start + i) % n);
            if (banned(bans, s.id(), now)) {
                continue;
            }
            if (spots == null || spots.claim(s.id(), owner, now)) {
                return s;
            }
        }
        return null;
    }

    private static Pick of(Spot s) {
        BlockPos w = s.walkTo();
        return new Pick(s.id(), w.getX() + 0.5D, w.getY(), w.getZ() + 0.5D, s.yaw(), s.seated() && s.approach() != null ? s.pos() : null,
                s.room(), null, null, 0);
    }

    private static Pick first(List<List<Spot>> groups, String owner, String salt, SectSpots spots, long now, Map<String, Long> bans) {
        for (List<Spot> g : groups) {
            Spot s = choose(g, owner, salt, spots, now, bans);
            if (s != null) {
                return of(s);
            }
        }
        return null;
    }

    /** Preserve a diner’s lease, then fill the smallest distance to a room wall before the aisle. */
    static List<List<Spot>> diningSeats(SectLayoutFile f, String owner, SectSpots claims, long now) {
        List<Spot> seats = spots(f, in("dining_hall").and(sitting()));
        List<List<Spot>> groups = new ArrayList<>();
        String held = claims == null ? null : claims.heldBy(owner, now);
        if (held != null) {
            for (Spot s : seats) if (s.id().equals(held)) groups.add(List.of(s));
        }
        java.util.TreeMap<Integer, List<Spot>> byWall = new java.util.TreeMap<>();
        for (Spot s : seats) {
            SectLayoutFile.Room room = s.room() == null ? f.roomAt(s.pos()) : f.room(s.room());
            int distance = Integer.MAX_VALUE;
            if (room != null) {
                var b = room.bounds();
                distance = Math.min(Math.min(s.pos().getX() - b.x0(), b.x1() - s.pos().getX()),
                        Math.min(s.pos().getZ() - b.z0(), b.z1() - s.pos().getZ()));
            }
            byWall.computeIfAbsent(distance, d -> new ArrayList<>()).add(s);
        }
        groups.addAll(byWall.values());
        groups.add(spots(f, in("dining_hall").and(kind("table", "counter"))));
        return groups;
    }

    // ------------------------------------------------------------------ the table

    /**
     * Where {@code m} does {@code t}. Null: not in the layout (keep the plan zone). {@code spots} may be null (peek:
     * nothing is reserved); {@code bans} are spots the person gave up on.
     */
    public static Pick pick(SectLayoutFile f, SectRoster m, Task t, long dayTime, SectSpots spots, long now, Map<String, Long> bans) {
        String owner = m.key();
        Set<String> tags = tags(f, m);
        String salt = t.kind().name() + t.zone();
        String house = house(f, m);
        if ("sect_gate".equals(t.zone()) && (t.kind() == Kind.WORK || t.kind() == Kind.SWEEP || t.kind() == Kind.CHORE)) {
            // The old plan centre is on the road side of the authored gate. Work waits in its actual inner yard.
            var inside = f.anchor("gate_inside").orElse(null);
            if (inside != null) {
                BlockPos p = inside.pos();
                return new Pick("zone:gate_work:" + owner, p.getX() + 0.5D + m.index() % 3 - 1,
                        p.getY(), p.getZ() + 0.5D, gateFallback(f, owner).yaw(), null, null, null, null, 0);
            }
        }
        switch (t.kind()) {
            case SLEEP -> {
                return sleepPick(f, m);
            }
            case EAT -> {
                if (t.partner().startsWith(DESK)) {
                    Task work = new Task(Kind.valueOf(t.partner().substring(DESK.length())), t.zone(), t.du(), t.dv(), t.faceU(), t.faceV());
                    Pick desk = pick(f, m, work, dayTime, spots, now, bans);
                    if (desk != null) {
                        return desk;
                    }
                }
                if (spots != null) {
                    String reserved = spots.mealSeat(owner, now);
                    if (reserved != null) {
                        Spot same = f.spot(reserved);
                        if (same != null && spots.claim(reserved, owner, now)) return of(same);
                        return null;
                    }
                }
                Pick meal = first(diningSeats(f, owner, spots, now), owner, salt, spots, now, java.util.Map.of());
                if (meal != null && spots != null) {
                    int left = SectSchedule.at(dayTime).meal()
                            ? mealLength(SectSchedule.at(dayTime)) - SectSchedule.sincePeriodStart(dayTime) : 2000;
                    spots.reserveMeal(owner, meal.spotId(), now + left + 120);
                }
                return meal;
            }
            case COOK -> {
                // The main kitchen is the north end of the dining hall (author 18.10); the old annex is the storeroom now.
                return first(groups(fit(f, kind("stove").and(in("dining_hall")), tags)), owner, salt, spots, now, bans);
            }
            case SERVE -> {
                return first(servingLine(f), owner, salt, spots, now, bans);
            }
            case TEND -> {
                return first(groups(fit(f, in("alchemy").and(kind("herb_jars")), tags)), owner, salt, spots, now, bans);
            }
            case BREW -> {
                return first(groups(fit(f, in("alchemy").and(kind("counter")), tags), fit(f, in("alchemy").and(kind("herb_jars", "mortar")), tags)),
                        owner, salt, spots, now, bans);
            }
            case GRIND -> {
                return first(groups(fit(f, in("alchemy").and(sitting()), tags), fit(f, in("alchemy").and(kind("mortar")), tags)),
                        owner, salt, spots, now, bans);
            }
            case READ -> {
                return deskSeat(f, first(groups(spots(f, at(f, Landmark.LIBRARY).and(kind("work_desk", "table"))),
                        spots(f, at(f, Landmark.LIBRARY).and(sitting()))), owner, salt, spots, now, bans), spots, owner, now, bans);
            }
            case COUNT -> {
                // His own office first (the counting room with the armchair on the top floor of the Finance Hall), then the other counting
                // rooms of the treasury; the desk of his own house while the treasury has no walkable route.
                String office = roomId(f, Landmark.TREASURY_OFFICE);
                // Sit in his own armchair behind the table, not in the nearest chair: that one is the visitor's, on the far side of his own
                // desk (sect check 24.10: «глава финансов не на своём стуле сидит»). The armchair carries only his role.
                if (office != null) {
                    Pick own = first(groups(spots(f, room(office).and(kind("seat_armchair")).and(mine(tags)))), owner, salt, spots, now, bans);
                    if (own != null) {
                        return own;
                    }
                }
                Predicate<Spot> desks = kind("work_desk", "low_desk", "table", "counter");
                List<Spot> mine = office == null ? List.<Spot>of() : spots(f, room(office).and(desks));
                List<Spot> treasury = spots(f, in("treasury").and(desks).and(s -> countingRoom(f, s)));
                return deskSeat(f, first(groups(mine, treasury, house == null ? List.<Spot>of() : spots(f, in(house).and(kind("work_desk")))),
                        owner, salt, spots, now, bans), spots, owner, now, bans);
            }
            case WORK -> {
                return deskSeat(f, workPick(f, m, t, tags, house, owner, salt, spots, now, bans), spots, owner, now, bans);
            }
            case RECEIVE -> {
                return receive(f, m, t, dayTime, owner, salt, spots, now, bans);
            }
            case MEDITATE, REST -> {
                return restPick(f, m, t, tags, house, owner, salt, spots, now, bans);
            }
            case FORM_ROW -> {
                return formation(f, t, owner, spots, now, bans);
            }
            case SPAR -> {
                return ring(f, t, owner, spots, now, bans);
            }
            case LECTURE -> {
                String group = "lecture:" + (m.key().equals(SectLessons.text("primaryTeacher", "tae_rok")) ? "main" : "poles") + ":teacher";
                return first(groups(spots(f, s -> group.equals(s.group()))), owner, salt, spots, now, bans);
            }
            case POLES -> {
                return first(groups(spots(f, kind("pole_stand"))), owner, salt, spots, now, bans);
            }
            case GUARD -> {
                return guard(f, t, dayTime, owner, salt, spots, now, bans);
            }
            case COUNCIL -> {
                return council(f, m, t, owner, spots, now);
            }
            case TEA -> {
                return teaWait(f);
            }
            case ARGUE -> {
                return argue(f, m, t);
            }
            case REVERE -> {
                return first(groups(spots(f, in("ancestors").and(kind("kneel_mat")))), owner, salt, spots, now, bans);
            }
            case CARRY -> {
                return carry(f, m, t, owner, salt, spots, now, bans);
            }
            case CHORE, SWEEP -> {
                return "camp".equals(t.zone()) || "treasury".equals(t.zone()) ? courtyard(f, owner, t) : null;
            }
            case WATCH -> {
                if ("training".equals(t.zone()) && m.key().equals(SectNightWatch.member(dayTime))) {
                    SectLayoutFile.Zone square = f.zone("training_square");
                    if (square != null) {
                        var r = square.rect();
                        return new Pick("zone:night_watch", (r.x0() + r.x1() + 1) / 2.0D, square.y(),
                                (r.z0() + r.z1() + 1) / 2.0D, 0.0F, null, null, null, null, 0);
                    }
                }
                if (SectSchedule.HALL_STEPS.equals(t.partner())) {
                    return hallSteps(f);
                }
                if ("gyeong_pil".equals(m.key()) && "mentor".equals(t.zone())) {
                    return first(groups(spots(f, kind("mentor_post"))), owner, salt, spots, now, bans);
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * The head on the steps of the Main Hall: between the two door guards, a little further out, looking the way they look. Derived
     * from the guard posts of the layout, so it turns with the site. Null: the layout has no hall posts (the plan offset is used).
     */
    static Pick hallSteps(SectLayoutFile f) {
        List<Spot> posts = spots(f, kind("guard_post").and(s -> "hall_front".equals(s.group())));
        if (posts.isEmpty()) {
            return null;
        }
        double x = 0.0D;
        double z = 0.0D;
        for (Spot s : posts) {
            x += s.pos().getX() + 0.5D;
            z += s.pos().getZ() + 0.5D;
        }
        x /= posts.size();
        z /= posts.size();
        Pick base = of(posts.get(0));
        double rad = Math.toRadians(base.yaw());
        return new Pick("zone:hall_steps", x - Math.sin(rad) * 2.5D, base.y(), z + Math.cos(rad) * 2.5D, base.yaw(), null, null, null, null, 0);
    }

    /**
     * The serving counter of the dining hall first, then the free cells of the same table row (same side of the table) next to it (a second
     * server stands beside the cook). Doc 39 item 6: the cook and the servants hand out the bowls from here.
     */
    static List<List<Spot>> servingLine(SectLayoutFile f) {
        List<Spot> counter = spots(f, in("dining_hall").and(kind("counter")));
        List<Spot> row = new ArrayList<>();
        for (Spot c : counter) {
            for (Spot t : spots(f, in("dining_hall").and(kind("table")))) {
                int dx = Math.abs(t.pos().getX() - c.pos().getX());
                int dz = Math.abs(t.pos().getZ() - c.pos().getZ());
                if (t.pos().getY() == c.pos().getY() && dz == 0 && dx <= 5 && !row.contains(t)) {
                    row.add(t);
                }
            }
        }
        row.sort(Comparator.comparingInt((Spot t) -> {
            Spot c = counter.isEmpty() ? null : counter.get(0);
            return c == null ? 0 : Math.abs(t.pos().getX() - c.pos().getX()) + Math.abs(t.pos().getZ() - c.pos().getZ());
        }).thenComparing(Spot::id));
        return groups(counter, row, spots(f, in("dining_hall").and(kind("counter", "table"))));
    }

    private static boolean countingRoom(SectLayoutFile f, Spot s) {
        SectLayoutFile.Room r = f.room(s.room());
        return r != null && "counting_room".equals(r.function());
    }

    private static Pick sleepPick(SectLayoutFile f, SectRoster m) {
        Bed b = bed(f, m);
        if (b == null) {
            return null;
        }
        SectLayoutFile.Room r = f.room(b.room());
        BlockPos a = b.approach();
        return new Pick(b.id(), a.getX() + 0.5D, a.getY(), a.getZ() + 0.5D, b.yaw(), null, b.room(), null, b.head(), r == null ? 0 : r.floor());
    }

    private static Pick workPick(SectLayoutFile f, SectRoster m, Task t, Set<String> tags, String house, String owner, String salt,
                                 SectSpots spots, long now, Map<String, Long> bans) {
        if (SectSchedule.officeHelper(owner) && "treasury".equals(t.zone())) {
            return first(groups(spots(f, at(f, Landmark.TREASURY_OFFICE).and(kind("seat_chair")))),
                    owner, salt, spots, now, bans);
        }
        if (m.role() == SectRole.STEWARD) {
            if ("dining".equals(t.zone())) {
                return first(servingLine(f), owner, salt, spots, now, bans);
            }
            if ("treasury".equals(t.zone())) {
                // the treasury counting rooms other than the financier's office; the desk of the room by the dorm entrance while the
                // treasury is cut off
                String office = roomId(f, Landmark.TREASURY_OFFICE);
                return first(groups(spots(f, in("treasury").and(kind("counter", "table", "work_desk")).and(s -> countingRoom(f, s))
                                .and(s -> office == null || !office.equals(s.room()))),
                        spots(f, at(f, Landmark.STEWARD_DESK_ROOM).and(kind("work_desk", "counter"))),
                        spots(f, at(f, Landmark.STEWARD_DESK_ROOM).and(sitting()))), owner, salt, spots, now, bans);
            }
            return null;
        }
        if (house != null) {
            return first(groups(spots(f, in(house).and(kind("work_desk"))), spots(f, in(house).and(sitting()))), owner, salt, spots, now, bans);
        }
        return null;
    }

    private static Pick restPick(SectLayoutFile f, SectRoster m, Task t, Set<String> tags, String house, String owner, String salt,
                                 SectSpots spots, long now, Map<String, Long> bans) {
        if (house != null) {
            return first(groups(spots(f, in(house).and(kind("cushion"))), spots(f, in(house).and(sitting()))), owner, salt, spots, now, bans);
        }
        if ("gyeong_cho".equals(m.key())) {
            return first(groups(spots(f, at(f, Landmark.HEALER_LOUNGE).and(kind("cushion")))), owner, salt, spots, now, bans);
        }
        String dorm = m.generation() == 3 || m.lay() ? "dorm_3rd" : "dorm_2nd";
        Predicate<Spot> seat = in(dorm).and(sitting());
        List<Spot> lounge = new ArrayList<>();
        List<Spot> rest = new ArrayList<>();
        for (Spot s : spots(f, seat.and(mine(tags)))) {
            SectLayoutFile.Room r = f.room(s.room());
            if (r != null && "meditation_lounge".equals(r.function())) {
                lounge.add(s);
            } else if (!s.kind().equals("seat_stool") || r == null || !"washroom".equals(r.function())) {
                rest.add(s);
            }
        }
        return first(groups(lounge, rest), owner, salt, spots, now, bans);
    }

    // ------------------------------------------------------------------ outdoor drills on the real square

    /** Formation slot of the plan -> the formation spot with the same row and column. */
    private static Pick formation(SectLayoutFile f, Task t, String owner, SectSpots spots, long now, Map<String, Long> bans) {
        int row = (int) Math.round((SectSchedule.FRONT_ROW - t.dv()) / SectSchedule.SPACING);
        int col = (int) Math.round(t.du() / SectSchedule.SPACING + (SectSchedule.COLUMNS - 1) / 2.0D);
        for (Spot s : spots(f, kind("formation"))) {
            if (s.row() == row && s.col() == col) {
                if (banned(bans, s.id(), now) || spots != null && !spots.claim(s.id(), owner, now)) {
                    return null;
                }
                return facingMentor(f, of(s));
            }
        }
        return null;
    }

    /**
     * The row faces the mentor's stand (the training square side), whatever the rotation of the site: the yaw is derived
     * from the layout markers, not from the stored spot yaw. Without a stand the stored yaw stays.
     */
    static Pick facingMentor(SectLayoutFile f, Pick p) {
        List<Spot> stands = spots(f, kind("mentor_post"));
        if (stands.isEmpty()) {
            return p;
        }
        BlockPos at = stands.get(0).pos();
        float yaw = SectLayout.yawOf(at.getX() + 0.5D - p.x(), at.getZ() + 0.5D - p.z());
        return new Pick(p.spotId(), p.x(), p.y(), p.z(), yaw, p.seat(), p.room(), p.pickup(), p.bed(), p.bedFloor());
    }

    /** Spar ring of the plan -> the spar ring spot; side 0 west, 1 east, {@link SectSchedule#RING_HALF} from the centre. */
    private static Pick ring(SectLayoutFile f, Task t, String owner, SectSpots spots, long now, Map<String, Long> bans) {
        int[] r = ringOf(t);
        if (r == null) {
            return null;
        }
        List<Spot> rings = spots(f, kind("spar_ring"));
        if (rings.isEmpty()) {
            return null;
        }
        // daily rings use the east side of the square first, the showcase rings follow the file order
        int[] eastFirst = {3, 4, 5, 0, 1, 2};
        Spot s = rings.get((r[0] == 0 ? eastFirst[r[1] % 6] : r[1]) % rings.size());
        String id = s.id() + "#" + r[2];
        if (banned(bans, id, now) || spots != null && !spots.claim(id, owner, now)) {
            return null;
        }
        double x = s.pos().getX() + 0.5D + (r[2] == 0 ? -SectSchedule.RING_HALF : SectSchedule.RING_HALF);
        return new Pick(id, x, s.pos().getY(), s.pos().getZ() + 0.5D, r[2] == 0 ? -90.0F : 90.0F, null, null, null, null, 0);
    }

    /** {kind (0 daily, 1 showcase), ring index, side} of a spar task, or null. */
    static int[] ringOf(Task t) {
        for (int kind = 0; kind < 2; kind++) {
            double[][] rings = kind == 0 ? SectSchedule.DAILY_RINGS : SectSchedule.RINGS;
            for (int i = 0; i < rings.length; i++) {
                if (Math.abs(t.dv() - rings[i][1]) > 1.0E-6D) {
                    continue;
                }
                if (Math.abs(t.du() - (rings[i][0] - SectSchedule.RING_HALF)) < 1.0E-6D) {
                    return new int[] {kind, i, 0};
                }
                if (Math.abs(t.du() - (rings[i][0] + SectSchedule.RING_HALF)) < 1.0E-6D) {
                    return new int[] {kind, i, 1};
                }
            }
        }
        return null;
    }

    private static Pick guard(SectLayoutFile f, Task t, long dayTime, String owner, String salt, SectSpots spots, long now, Map<String, Long> bans) {
        if (SectSchedule.COUNCIL_DOOR.equals(t.partner())) {
            // during the council the guard of the hall front stands at the door of the council hall (nobody is let in unasked)
            Pick door = councilDoor(f);
            if (door != null) {
                return door;
            }
        }
        // These stops have no guard_post spot in the extracted layout. Use its real entrance cells,
        // rather than falling back to the old mountain plan's coordinates.
        if ("penance".equals(t.zone()) && f.landmark("penance") != null) {
            var cave = f.landmark("penance");
            BlockPos at = cave.mouth();
            float yaw = SectLayout.yawOf(at.getX() - cave.cell().getX(), at.getZ() - cave.cell().getZ());
            return new Pick("zone:guard_penance", at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, yaw, null, null, null, null, 0);
        }
        if ("elders".equals(t.zone())) {
            for (SectLayoutFile.Door door : f.doors()) {
                boolean a = door.roomA() != null && door.roomA().startsWith("elder_2.");
                boolean b = door.roomB() != null && door.roomB().startsWith("elder_2.");
                if (!(a && door.roomB() == null || b && door.roomA() == null) || !"ok".equals(door.reach())) continue;
                List<BlockPos> out = a ? door.sideB() : door.sideA();
                List<BlockPos> in = a ? door.sideA() : door.sideB();
                if (out.isEmpty() || in.isEmpty()) continue;
                BlockPos at = out.get(out.size() / 2);
                BlockPos inside = in.get(in.size() / 2);
                float yaw = SectLayout.yawOf(at.getX() - inside.getX(), at.getZ() - inside.getZ());
                return new Pick("zone:guard_elders", at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, yaw, null, null, null, null, 0);
            }
        }
        String group = switch (t.zone()) {
            case "sect_gate" -> "gate";
            case "main_hall" -> "hall_front";
            case "treasury" -> "treasury";
            case "ancestors" -> "ancestors";
            case "scriptures" -> "scriptures";
            default -> null;
        };
        if (group == null) {
            return null;
        }
        if ("gate".equals(group) && "gatekeeper".equals(owner)) {
            // the gatekeeper takes the post nearest the opening, so he sees the trail and is seen from it
            var gate = f.anchor("gate_inside").orElse(null);
            List<Spot> posts = new ArrayList<>(spots(f, kind("guard_post").and(s -> "gate".equals(s.group()))));
            if (gate != null) {
                posts.sort(Comparator.comparingDouble(s -> s.pos().distSqr(gate.pos())));
            }
            // Keep the same post for the shift, including when another post nearer the door becomes free.
            String held = spots == null ? null : spots.heldBy(owner, now);
            if (held != null) posts.sort(Comparator.comparingInt(s -> held.equals(s.id()) ? 0 : 1));
            for (Spot post : posts) {
                if (!banned(bans, post.id(), now) && (spots == null || spots.claim(post.id(), owner, now))) return of(post);
            }
            return gateFallback(f, owner);
        }
        // the treasury posts fall back to the quiet halls while the treasury is cut off
        String backup = SectSchedule.at(dayTime) == Period.NIGHT ? "ancestors" : "scriptures";
        Pick picked = first(groups(spots(f, kind("guard_post").and(s -> group.equals(s.group()))),
                "treasury".equals(group) ? spots(f, kind("guard_post").and(s -> backup.equals(s.group()))) : List.<Spot>of()),
                owner, salt, spots, now, bans);
        return picked == null && "gate".equals(group) ? gateFallback(f, owner) : picked;
    }

    /** An unavailable/banned post must never send a guard to the legacy plan point outside the gate. */
    private static Pick gateFallback(SectLayoutFile file, String owner) {
        var inside = file.anchor("gate_inside").orElse(null);
        if (inside == null) return null;
        BlockPos p = inside.pos();
        var trail = file.anchor("trail_approach").orElse(null);
        float yaw = trail == null ? 180.0F : SectLayout.yawOf(trail.pos().getX() - p.getX(), trail.pos().getZ() - p.getZ());
        return new Pick("zone:gate_wait:" + owner, p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D,
                yaw, null, null, null, null, 0);
    }

    /**
     * Desk work is done sitting (author 17.10): from the desk spot a person got, the nearest seat of the same room
     * within three blocks (chair, armchair, stool, bench, cushion, mat), turned to the desk. No seat: the desk spot.
     */
    static Pick deskSeat(SectLayoutFile f, Pick desk, SectSpots spots, String owner, long now, Map<String, Long> bans) {
        if (desk == null || desk.spotId() == null) {
            return desk;
        }
        Spot d = f.spot(desk.spotId());
        if (d == null || d.seated() || d.room() == null) {
            return desk;
        }
        List<Spot> seats = new ArrayList<>();
        for (Spot s : spots(f, room(d.room()).and(x -> x.seated() && x.approach() != null))) {
            if (Math.abs(s.pos().getY() - d.pos().getY()) <= 1 && Math.hypot(s.pos().getX() - d.pos().getX(), s.pos().getZ() - d.pos().getZ()) <= 3.2D) {
                seats.add(s);
            }
        }
        seats.sort(Comparator.comparingDouble(s -> Math.hypot(s.pos().getX() - d.pos().getX(), s.pos().getZ() - d.pos().getZ())));
        for (Spot s : seats) {
            if (banned(bans, s.id(), now) || spots != null && !spots.claim(s.id(), owner, now)) {
                continue;
            }
            Pick p = of(s);
            float yaw = SectLayout.yawOf(d.pos().getX() - s.pos().getX(), d.pos().getZ() - s.pos().getZ());
            return new Pick(p.spotId(), p.x(), p.y(), p.z(), yaw, p.seat(), p.room(), null, null, 0);
        }
        return desk;
    }

    /** The leader sits on the mat behind the low desk of the dais in audience hours, at the study desk otherwise. */
    private static Pick receive(SectLayoutFile f, SectRoster m, Task t, long dayTime, String owner, String salt, SectSpots spots,
                                long now, Map<String, Long> bans) {
        boolean audience = SectSchedule.at(dayTime) == Period.TRAINING;
        Pick p = audience
                ? deskSeat(f, first(groups(spots(f, at(f, Landmark.DAIS).and(kind("low_desk")).and(s -> s.roles().contains("leader")))),
                        owner, salt, spots, now, bans), spots, owner, now, bans)
                : deskSeat(f, first(groups(spots(f, at(f, Landmark.STUDY).and(kind("work_desk")).and(s -> s.roles().contains("leader")).and(s -> !"enclosed".equals(s.reach())))),
                        owner, salt, spots, now, bans), spots, owner, now, bans);
        return p != null ? p : council(f, m, t, owner, spots, now);
    }

    /**
     * A seat at the council. In the built sect it is the council hall on the second storey (docs/design/38, «Совет и
     * приглашения»): the head on the mat of the dais at its head, the elders on the cushions of the two rows, the front row
     * first, so the order of the file is the order at the table; the last cushion is left for an elder who is a player. A world
     * without such a hall keeps the armchairs of the audience hall.
     */
    private static Pick council(SectLayoutFile f, SectRoster m, Task t, String owner, SectSpots spots, long now) {
        List<Spot> cushions = councilCushions(f);
        int seat = Math.max(0, SectCouncil.seat(m.key()));
        if (!cushions.isEmpty()) {
            if (seat == 0) {
                List<Spot> mats = spots(f, at(f, Landmark.COUNCIL_DAIS).and(kind("kneel_mat")).and(s -> s.roles().contains("leader")));
                if (!mats.isEmpty()) {
                    return of(mats.get(0));
                }
            }
            return of(cushions.get(Math.min(Math.max(0, seat - 1), cushions.size() - 1)));
        }
        List<Spot> seats = spots(f, at(f, Landmark.AUDIENCE_HALL).and(kind("seat_armchair", "seat_chair")).and(s -> s.roles().contains("leader")));
        if (seats.isEmpty()) {
            return null;
        }
        Spot s = seats.get(Math.max(0, SectSchedule.COUNCIL.indexOf(m.key())) % seats.size());
        // the leader receiving guests stands beside his chair instead of sitting in it
        return of(s);
    }

    /** The built sect has a council hall (the council of elders meets in a layout that has one). */
    public static boolean hasCouncilHall(SectLayoutFile f) {
        return roomId(f, Landmark.COUNCIL_HALL) != null;
    }

    /** The cushions of the council hall, front row first, west before east: the seat of member i is the (i-1)th. */
    public static List<Spot> councilCushions(SectLayoutFile f) {
        String hall = roomId(f, Landmark.COUNCIL_HALL);
        if (hall == null) {
            return List.of();
        }
        List<Spot> out = spots(f, room(hall).and(kind("cushion")));
        out.sort(Comparator.comparingInt((Spot s) -> s.pos().getZ()).thenComparingInt(s -> s.pos().getX()).thenComparing(Spot::id));
        return out;
    }

    /** The low desks of the council hall in the order of the cushions: where the tea is set down for each member. */
    public static List<Spot> councilDesks(SectLayoutFile f) {
        String hall = roomId(f, Landmark.COUNCIL_HALL);
        if (hall == null) {
            return List.of();
        }
        List<Spot> out = spots(f, room(hall).and(kind("low_desk")));
        out.sort(Comparator.comparingInt((Spot s) -> s.pos().getZ()).thenComparingInt(s -> s.pos().getX()).thenComparing(Spot::id));
        return out;
    }

    /**
     * Where the servant waits with the tray until the tea is due: the aisle of the council hall at its far (south) end, between
     * the two rows. Null: no council hall in the file (the servant keeps the plan spot).
     */
    private static Pick teaWait(SectLayoutFile f) {
        List<Spot> desks = councilDesks(f);
        if (desks.isEmpty()) {
            return null;
        }
        Spot last = desks.get(desks.size() - 1);
        BlockPos w = last.walkTo();
        return new Pick("zone:tea_wait", w.getX() + 0.5D, w.getY(), w.getZ() + 1.5D, 180.0F, null, last.room(), null, null, 0);
    }

    /**
     * The door of the council hall from the landing of the stair, on the landing side: where the guard of the hall stands during
     * the meeting. Looks at the door. Null: no council hall, or no door that leads from a stair.
     */
    public static Pick councilDoor(SectLayoutFile f) {
        String hall = roomId(f, Landmark.COUNCIL_HALL);
        SectLayoutFile.Room room = hall == null ? null : f.room(hall);
        if (room == null) {
            return null;
        }
        SectLayoutFile.Door best = null;
        for (SectLayoutFile.Door d : f.doors()) {
            boolean a = hall.equals(d.roomA());
            if (!a && !hall.equals(d.roomB()) || !"ok".equals(d.reach())) {
                continue;
            }
            SectLayoutFile.Room other = f.room(a ? d.roomB() : d.roomA());
            if (other == null || other.floor() != room.floor() || !stairRoom(f, other)) {
                continue;
            }
            if (best == null || d.id().compareTo(best.id()) < 0) {
                best = d;
            }
        }
        if (best == null) {
            return null;
        }
        boolean hallIsA = hall.equals(best.roomA());
        List<BlockPos> out = hallIsA ? best.sideB() : best.sideA();
        List<BlockPos> in = hallIsA ? best.sideA() : best.sideB();
        if (out.isEmpty() || in.isEmpty()) {
            return null;
        }
        BlockPos cell = out.get(0);
        return new Pick("zone:council_door", cell.getX() + 0.5D, cell.getY(), cell.getZ() + 0.5D,
                SectLayout.yawOf(in.get(0).getX() - cell.getX(), in.get(0).getZ() - cell.getZ()), null, hallIsA ? best.roomB() : best.roomA(),
                null, null, 0);
    }

    /**
     * Where an intruder of the council is taken: the foot of the stair that leads down from the landing of the council storey, which
     * is outside every room of that storey ({@link SectCouncilIntrusion#intruding}). Without such a stair (a thinner layout) the landing
     * outside the door of the council hall. Null: no council hall.
     */
    public static Pick councilExit(SectLayoutFile f) {
        Pick door = councilDoor(f);
        if (door == null) {
            return null;
        }
        SectLayoutFile.Room landing = door.room() == null ? null : f.room(door.room());
        if (landing != null) {
            for (String w : landing.portals()) {
                if (w.startsWith("stair.") && w.endsWith(".top")) {
                    SectLayoutFile.Waypoint bottom = f.waypoint(w.substring(0, w.length() - 4) + ".bottom");
                    if (bottom != null) {
                        BlockPos at = bottom.pos();
                        return new Pick("zone:council_exit", at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, door.yaw(), null, bottom.room(), null, null, 0);
                    }
                }
            }
        }
        return door;
    }

    private static boolean stairRoom(SectLayoutFile f, SectLayoutFile.Room r) {
        for (String w : r.portals()) {
            if (w.startsWith("stair.")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The quarrel of two juniors in the forecourt of the Main Hall (the elder's invitation to judge it): side by side across the
     * middle of the court, turned to each other. Null: the file has no such court.
     */
    private static Pick argue(SectLayoutFile f, SectRoster m, Task t) {
        var court = f.anchor("hall_court_center");
        if (court.isEmpty()) {
            return null;
        }
        BlockPos c = court.get().pos();
        int side = t.du() < 0.0D ? 0 : 1;
        return new Pick("zone:argue." + side, c.getX() + 0.5D + (side == 0 ? -1.0D : 1.0D), c.getY(), c.getZ() + 0.5D,
                side == 0 ? -90.0F : 90.0F, null, null, null, null, 0);
    }

    private static Pick carry(SectLayoutFile f, SectRoster m, Task t, String owner, String salt, SectSpots spots, long now,
                              Map<String, Long> bans) {
        if (m.role() == SectRole.WATER_CARRIER) {
            String drop = "alchemy".equals(t.zone()) ? "alchemy" : "dining_hall";
            List<Spot> vats = spots(f, in(drop).and(kind("water_vat")));
            List<Spot> wells = spots(f, in("dorm_3rd").and(kind("water_vat")));
            if (vats.isEmpty() || wells.isEmpty()) {
                return null;
            }
            Pick p = of(vats.get(0));
            BlockPos from = wells.get(0).walkTo();
            return new Pick(p.spotId(), p.x(), p.y(), p.z(), p.yaw(), null, p.room(), from, null, 0);
        }
        if (m.role() == SectRole.PORTER) {
            int i = "porter_oh".equals(m.key()) ? 1 : 0;
            SectLayoutFile.Room larder = storeroom(f);
            // The second porter fetches from the storeroom and carries to the kitchen at the north end of the dining hall.
            if (i == 1 && larder != null) {
                List<Spot> stoves = fit(f, kind("stove", "counter").and(in("dining_hall")), tags(f, m));
                Spot drop = choose(stoves, owner, salt, spots, now, bans);
                if (drop != null) {
                    Pick p = of(drop);
                    BlockPos from = f.waypoint(larder.portals().get(0)).pos();
                    return new Pick(p.spotId(), p.x(), p.y(), p.z(), p.yaw(), null, p.room(), from, null, 0);
                }
            }
            List<SectLayoutFile.Room> stores = new ArrayList<>();
            String treasuryStore = roomId(f, Landmark.TREASURY_STORE);
            for (SectLayoutFile.Room r : f.rooms()) {
                if ("ok".equals(r.reach()) && !r.portals().isEmpty() && ("storeroom".equals(r.function()) && r.building().startsWith("dorm_")
                        || r.id().equals(treasuryStore))) {
                    stores.add(r);
                }
            }
            // the sacks from the gate go to the storeroom first (the larder), then the treasury, then the dorm storerooms
            stores.sort(Comparator.comparing((SectLayoutFile.Room r) -> r.building().equals("treasury") ? 0 : 1).thenComparing(SectLayoutFile.Room::id));
            if (larder != null) {
                stores.add(0, larder);
            }
            var gate = f.anchor("gate_inside");
            if (stores.isEmpty() || gate.isEmpty()) {
                return null;
            }
            SectLayoutFile.Room r = stores.get(i % stores.size());
            BlockPos in = f.waypoint(r.portals().get(0)).pos();
            return new Pick("store:" + r.id(), in.getX() + 0.5D, in.getY(), in.getZ() + 0.5D, 0.0F, null, r.id(),
                    gate.get().pos().offset(i * 2, 0, 0), null, 0);
        }
        return null;
    }

    /** The storeroom of the sect's food (the old kitchen annex, author 18.10): walkable, with a portal. Null: none built. */
    static SectLayoutFile.Room storeroom(SectLayoutFile f) {
        for (SectLayoutFile.Room r : f.rooms()) {
            if ("storeroom".equals(r.building()) && "ok".equals(r.reach()) && !r.portals().isEmpty()) {
                return r;
            }
        }
        return null;
    }

    private static Pick courtyard(SectLayoutFile f, String owner, Task t) {
        SectLayoutFile.Zone z = f.zone("hall_court");
        if (z == null) {
            return null;
        }
        SectLayoutFile.Rect r = z.rect();
        double cx = (r.x0() + r.x1()) / 2.0D + 0.5D;
        double cz = (r.z0() + r.z1()) / 2.0D + 0.5D;
        double spread = Math.max(1.0D, (r.x1() - r.x0()) / 2.0D - 2.0D);
        double dx = (SectStagger.unit(owner, "court" + t.zone()) * 2.0D - 1.0D) * spread;
        return new Pick("zone:hall_court", cx + dx, z.y(), cz, 0.0F, null, null, null, null, 0);
    }

    // ------------------------------------------------------------------ inspection walk of the mentor

    /**
     * The world axes the formation runs along: {@code [column x, column z, row x, row z]}, unit steps. The rows and columns
     * are numbered in the frame the file was extracted in; the site may be turned a quarter or half turn from it, so the
     * directions are read from the spots themselves. An axis the spots do not show (one column, one row) is the other
     * one turned a quarter, which is how the extraction frame lays them out.
     */
    static int[] formationAxes(List<Spot> formation) {
        Spot origin = null;
        for (Spot s : formation) {
            if (origin == null || s.row() < origin.row() || s.row() == origin.row() && s.col() < origin.col()) {
                origin = s;
            }
        }
        int[] col = null;
        int[] row = null;
        for (Spot s : formation) {
            if (s.row() == origin.row() && s.col() > origin.col() && col == null) {
                col = axis(s.pos().getX() - origin.pos().getX(), s.pos().getZ() - origin.pos().getZ());
            }
            if (s.col() == origin.col() && s.row() > origin.row() && row == null) {
                row = axis(s.pos().getX() - origin.pos().getX(), s.pos().getZ() - origin.pos().getZ());
            }
        }
        if (col == null && row == null) {
            col = new int[] {1, 0};
        }
        if (col == null) {
            col = new int[] {row[1], -row[0]};
        }
        if (row == null) {
            row = new int[] {-col[1], col[0]};
        }
        return new int[] {col[0], col[1], row[0], row[1]};
    }

    private static int[] axis(int dx, int dz) {
        return Math.abs(dx) >= Math.abs(dz) ? new int[] {Integer.signum(dx), 0} : new int[] {0, Integer.signum(dz)};
    }

    /**
     * Mentor's walk between the formation rows: line {@code line} (0..rows) at its west ({@code end} 0) or east end.
     * Worked out in the frame of the formation (columns west to east, rows front to back), so it holds at every rotation of
     * the site. Null: no formation in the layout.
     */
    public static BlockPos inspectPoint(SectLayoutFile f, int line, int end) {
        List<Spot> formation = new ArrayList<>();
        for (Spot s : f.spots()) {
            if ("formation".equals(s.kind())) {
                formation.add(s);
            }
        }
        if (formation.isEmpty()) {
            return null;
        }
        int[] a = formationAxes(formation);
        int minC = Integer.MAX_VALUE;
        int maxC = Integer.MIN_VALUE;
        int minR = Integer.MAX_VALUE;
        int maxR = Integer.MIN_VALUE;
        int y = 0;
        for (Spot s : formation) {
            int c = s.pos().getX() * a[0] + s.pos().getZ() * a[1];
            int r = s.pos().getX() * a[2] + s.pos().getZ() * a[3];
            minC = Math.min(minC, c);
            maxC = Math.max(maxC, c);
            minR = Math.min(minR, r);
            maxR = Math.max(maxR, r);
            y = s.pos().getY();
        }
        int rows = (int) Math.round((maxR - minR) / SectSchedule.SPACING) + 1;
        int r = Math.min(minR + (int) Math.round(Math.floorMod(line, rows + 1) * SectSchedule.SPACING) - 1, maxR + 2);
        int c = end == 0 ? minC - 3 : maxC + 3;
        return new BlockPos(a[0] * c + a[2] * r, y, a[1] * c + a[3] * r);
    }

    /** The bounding box [x0, z0, x1, z1] of the formation spots grown by {@code margin}, or null. */
    public static SectLayoutFile.Rect formationArea(SectLayoutFile f, int margin) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Spot s : f.spots()) {
            if ("formation".equals(s.kind())) {
                minX = Math.min(minX, s.pos().getX());
                maxX = Math.max(maxX, s.pos().getX());
                minZ = Math.min(minZ, s.pos().getZ());
                maxZ = Math.max(maxZ, s.pos().getZ());
            }
        }
        return minX == Integer.MAX_VALUE ? null : new SectLayoutFile.Rect(minX - margin, minZ - margin, maxX + margin, maxZ + margin);
    }
}
