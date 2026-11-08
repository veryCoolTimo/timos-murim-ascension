package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.IntSeating;
import io.github.verycooltimo.murim.decor.SeatEntity;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.sect.SectEventsFile.Council;
import io.github.verycooltimo.murim.sect.SectSchedule.Kind;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The council of elders, the financier's office and the invitations of the offices (docs/design/38, «Совет и приглашения») on a small
 * hand-made layout in the test yard: the council hall with cushions, low desks and the dais, its door from the stair landing, the
 * Finance Hall office, the forecourt. Real schedule, real walking and sitting, real tablet journal; the stage is the file only.
 * Every test has a batch of its own: they set the time of day of the level, and two of them at once would change each other's day.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectCouncilGameTests {

    private static final String YARD = "sect_yard";
    private static final Council C = SectCouncil.config();

    private SectCouncilGameTests() {
    }

    /** A layout whose plan zones do not exist; everything comes from the file. */
    private record Mini(Vec3 origin, SectLayoutFile file) implements SectLayout {
        @Override
        public Vec3 at(String zone, double u, double v) {
            return null;
        }

        @Override
        public float yaw(double u, double v) {
            return SectLayout.yawOf(u, v);
        }

        @Override
        public double[] half(String zone) {
            return null;
        }

        @Override
        public double[] local(String zone, Vec3 pos) {
            return null;
        }
    }

    /** The stage: council hall (x 5..14, z 4..12), its landing and door, the office of the financier, the forecourt. */
    private static final class Stage {
        final GameTestHelper h;
        final List<SectLayoutFile.Room> rooms = new ArrayList<>();
        final List<SectLayoutFile.Door> doors = new ArrayList<>();
        final List<SectLayoutFile.Spot> spots = new ArrayList<>();
        final List<SectLayoutFile.Waypoint> wps = new ArrayList<>();
        final int y;

        Stage(GameTestHelper h) {
            this.h = h;
            this.y = h.absolutePos(new BlockPos(0, 2, 0)).getY();
            // the stair landing with the door to the hall, the hall, the dais, the office
            room("main_hall.f2.r1", "main_hall", 1, 3, 5, 8, "corridor", List.of("stair.t.top"));
            room("main_hall.f2.r2", "main_hall", 5, 14, 4, 12, "lecture_hall", List.of("door.t.b"));
            room("main_hall.f2.r3", "main_hall", 9, 10, 4, 5, "corridor", List.of());
            room("treasury.f2.r1", "treasury", 17, 22, 15, 21, "counting_room", List.of("door.o.a"));
            doors.add(new SectLayoutFile.Door("door.t", "hall_door", "wooden_door", p(4, 6), p(4, 7), List.of(p(3, 6), p(3, 7)),
                    List.of(p(5, 6), p(5, 7)), "main_hall.f2.r1", "main_hall.f2.r2", 2, "ok"));
            for (int z = 6; z <= 8; z++) {
                spot("spot.cw" + z, "cushion", "sit", 6, z, 6, z, -90.0F, "main_hall.f2.r2", List.of("instructor"));
                spot("spot.ce" + z, "cushion", "sit", 13, z, 13, z, 90.0F, "main_hall.f2.r2", List.of("instructor"));
                spot("spot.dw" + z, "low_desk", "stand", 8, z, -1, -1, 90.0F, "main_hall.f2.r2", List.of("instructor"));
                spot("spot.de" + z, "low_desk", "stand", 11, z, -1, -1, -90.0F, "main_hall.f2.r2", List.of("instructor"));
            }
            spot("spot.mat", "kneel_mat", "kneel", 9, 4, 9, 4, 0.0F, "main_hall.f2.r3", List.of("leader", "elder"));
            spot("spot.table", "table", "stand", 21, 18, -1, -1, 90.0F, "treasury.f2.r1", List.of("elder_treasury", "steward"));
            spot("spot.chair", "seat_chair", "sit", 20, 18, 20, 18, -90.0F, "treasury.f2.r1", List.of("elder_treasury", "steward"));
            wps.add(new SectLayoutFile.Waypoint("anchor.hall_court_center", p(12, 16), "anchor", "hall_court_center", null));
        }

        BlockPos p(int x, int z) {
            return h.absolutePos(new BlockPos(x, 2, z));
        }

        void room(String id, String building, int x0, int x1, int z0, int z1, String function, List<String> portals) {
            rooms.add(new SectLayoutFile.Room(id, building, 2, y, new SectLayoutFile.Rect(h.absolutePos(new BlockPos(x0, 2, z0)).getX(),
                    h.absolutePos(new BlockPos(x0, 2, z0)).getZ(), h.absolutePos(new BlockPos(x1, 2, z1)).getX(),
                    h.absolutePos(new BlockPos(x1, 2, z1)).getZ()), function, "ok", portals, List.of(), (x1 - x0 + 1) * (z1 - z0 + 1)));
        }

        void spot(String id, String kind, String posture, int x, int z, int ax, int az, float yaw, String room, List<String> roles) {
            BlockPos at = p(x, z);
            spots.add(new SectLayoutFile.Spot(id, kind, posture, at, ax < 0 ? null : p(ax, az), null, yaw, room, room.substring(0, room.indexOf('.')),
                    null, null, roles, "ok", -1, -1));
        }

        SectLayout layout() {
            // the furniture is real: a person is put ON a seat block (a chair, a cushion, a mat), as he is in the sect
            for (SectLayoutFile.Spot s : spots) {
                net.minecraft.world.level.block.Block seat = switch (s.kind()) {
                    case "seat_chair" -> io.github.verycooltimo.murim.decor.IntSeating.CHAIR.get();
                    case "seat_armchair" -> io.github.verycooltimo.murim.decor.IntSeating.ARMCHAIR.get();
                    case "cushion" -> io.github.verycooltimo.murim.decor.IntSeating.FLOOR_CUSHION.get();
                    case "kneel_mat" -> io.github.verycooltimo.murim.decor.IntSeating.MAT.get();
                    default -> null;
                };
                if (seat != null) {
                    h.getLevel().setBlock(s.pos(), seat.defaultBlockState(), 3);
                }
            }
            SectLayoutFile file = new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), rooms, doors, List.of(),
                    List.of(), spots, wps, List.of(), List.of());
            return new Mini(h.absoluteVec(new Vec3(12.5, 2, 12.5)), file);
        }
    }

    private static SectDisciple npc(GameTestHelper h, SectLayout layout, String key, double x, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setLayout(layout);
        d.setKeepAwake(true);
        Vec3 at = h.absoluteVec(new Vec3(x, 2, z));
        d.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static void time(GameTestHelper h, long day, int tick) {
        h.getLevel().setDayTime(day * SectSchedule.DAY + tick);
    }

    private static long councilDay() {
        for (long d = 0; d < SectEventsFile.WEEK; d++) {
            if (C.onDay(d)) {
                return d;
            }
        }
        throw new AssertionError("no council day");
    }

    private static boolean near(SectDisciple d, BlockPos cell, double range) {
        return Math.hypot(d.getX() - (cell.getX() + 0.5D), d.getZ() - (cell.getZ() + 0.5D)) <= range && Math.abs(d.getY() - cell.getY()) < 1.2D;
    }

    private static ServerPlayer player(GameTestHelper h, SectState state, double x, double z) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(x, 2, z));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setOnGround(true);
        p.setData(ModAttachments.SECT, state);
        return p;
    }

    /** The same player, standing in the level: people of the sect can see him (the guard at the door looks for the nearest player). */
    private static ServerPlayer playerInLevel(GameTestHelper h, SectState state, double x, double z) {
        ServerPlayer p = player(h, state, x, z);
        h.getLevel().addNewPlayer(p);
        return p;
    }

    // ------------------------------------------------------------------ 1: the elders gather, sit, are served tea, rise and disperse

    @GameTest(template = YARD, timeoutTicks = 2400, batch = "sect_council_session")
    public static void councilGathersTheEldersAtTheTableServesTeaAndDisperses(GameTestHelper h) {
        Stage stage = new Stage(h);
        SectLayout layout = stage.layout();
        long day = councilDay();
        time(h, day, C.from() - 700);
        List<SectDisciple> members = new ArrayList<>();
        double z = 15.0D;
        for (String key : C.members()) {
            if (SectCouncil.stays(key)) continue;
            members.add(npc(h, layout, key, 2.5D, z));
            z += 1.2D;
        }
        SectDisciple servant = npc(h, layout, C.servant(), 2.5D, 21.5D);
        SectLayoutFile file = layout.file();
        List<SectLayoutFile.Spot> cushions = SectPlaces.councilCushions(file);
        h.assertTrue(cushions.size() == 6, "six cushions in the stage: " + cushions.size());
        Set<String> sipped = new HashSet<>();
        Set<String> bowed = new HashSet<>();
        h.onEachTick(() -> {
            for (SectDisciple d : members) {
                if (d.pose() == SectPose.TEA) {
                    sipped.add(d.memberKey());
                }
                if (d.pose() == SectPose.BOW) {
                    bowed.add(d.memberKey());
                }
            }
        });
        h.startSequence()
                .thenWaitUntil(() -> {
                    for (SectDisciple d : members) {
                        int seat = SectCouncil.seat(d.memberKey());
                        BlockPos cell = seat == 0 ? stage.p(9, 4) : cushions.get(seat - 1).pos();
                        h.assertTrue(d.sitting() && near(d, cell, 1.1D), d.memberKey() + " is not at his seat " + cell + ": " + d.position()
                                + " sitting=" + d.sitting() + " doing=" + d.doingKind());
                    }
                    h.assertTrue(near(members.get(0), stage.p(9, 4), 1.1D), "the head is not on the mat of the dais");
                })
                .thenExecute(() -> time(h, day, C.tea() + 30))
                .thenWaitUntil(() -> {
                    h.assertTrue(servant.doingKind() == Kind.TEA, "the servant is not at the tea: " + servant.doingKind());
                    h.assertTrue(sipped.size() >= 3, "only " + sipped.size() + " members were served tea: " + sipped);
                })
                .thenExecute(() -> time(h, day, C.leave() + 30))
                .thenWaitUntil(() -> {
                    for (SectDisciple d : members) {
                        h.assertTrue(!d.sitting(), d.memberKey() + " is still sitting after the council rose");
                    }
                    h.assertTrue(bowed.size() >= 4, "they did not bow to the head: " + bowed);
                })
                .thenExecute(() -> time(h, day, C.to() + 60))
                .thenExecuteAfter(30, () -> {
                    // The day goes on: council seats are vacated, but the financier may already be sitting at his desk.
                    for (SectDisciple d : members) {
                        SectLife.Resolved routine = SectLife.resolve(d);
                        h.assertTrue(routine == null || routine.task().kind() != Kind.COUNCIL,
                                d.memberKey() + " still has a council task: " + routine);
                        boolean atCouncilSeat = near(d, stage.p(9, 4), 1.1D)
                                || cushions.stream().anyMatch(s -> near(d, s.pos(), 1.1D));
                        h.assertTrue(!d.sitting() || !atCouncilSeat,
                                d.memberKey() + " is still sitting at the council: " + d.position() + " routine=" + routine);
                        if (d.memberKey().equals("tae_gyun")) {
                            h.assertTrue(routine != null && routine.task().kind() == Kind.COUNT && "spot.chair".equals(routine.spotId()),
                                    "the financier did not resume his office routine: " + routine);
                            // sitting() includes the seven-tick approach glide (SectDisciple#sit).
                            // Its destination must already be the office chair; position may still be beside it.
                            Vec3 anchor = d.seatAnchor();
                            BlockPos chair = stage.p(20, 18);
                            h.assertTrue(!d.sitting() || anchor != null
                                            && Math.hypot(anchor.x - (chair.getX() + 0.5D), anchor.z - (chair.getZ() + 0.5D)) < 0.1D
                                            && Math.abs(anchor.y - chair.getY()) < 1.2D,
                                    "the financier is sitting away from his office chair: " + d.position() + " anchor=" + anchor);
                        }
                    }
                    SectLife.Resolved routine = SectLife.resolve(servant);
                    h.assertTrue(routine == null || routine.task().kind() != Kind.TEA, "the servant is still serving: " + routine);
                })
                // API: minecraft-src/net/minecraft/gametest/framework/GameTestSequence.java#thenWaitUntil.
                // Finish the real walk and mount, rather than accepting a correct destination alone.
                .thenWaitUntil(() -> {
                    SectDisciple financier = members.stream().filter(d -> d.memberKey().equals("tae_gyun")).findFirst().orElseThrow();
                    h.assertTrue(financier.sitting() && financier.getVehicle() instanceof SeatEntity
                                    && near(financier, stage.p(20, 18), 1.1D),
                            "the financier has not mounted his office chair after the council: " + financier.position());
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------ 2: the financier is in his office all working day

    @GameTest(template = YARD, timeoutTicks = 1500, batch = "sect_council_financier")
    public static void financierSitsInHisOfficeThroughTheWorkingDayAndEatsAtHisDesk(GameTestHelper h) {
        Stage stage = new Stage(h);
        SectLayout layout = stage.layout();
        long quiet = 1;
        time(h, quiet, 3000);
        SectDisciple gyun = npc(h, layout, "tae_gyun", 3.5D, 19.5D);
        BlockPos chair = stage.p(20, 18);
        net.minecraft.world.entity.Entity[] officeSeat = {null};
        h.startSequence()
                .thenWaitUntil(() -> {
                    h.assertTrue(gyun.sitting() && near(gyun, chair, 1.1D), "the financier is not at his desk: " + gyun.position() + " doing=" + gyun.doingKind());
                    h.assertTrue(gyun.doingKind() == Kind.COUNT, "he is not counting: " + gyun.doingKind());
                    h.assertTrue(gyun.getVehicle() instanceof SeatEntity, "the office chair does not hold him");
                    officeSeat[0] = gyun.getVehicle();
                })
                // breakfast: he eats at his desk, he does not walk to the dining hall
                .thenExecute(() -> time(h, quiet, 1100))
                .thenExecuteAfter(60, () -> {
                    h.assertTrue(gyun.doingKind() == Kind.EAT, "the financier does not eat at breakfast: " + gyun.doingKind());
                    h.assertTrue(gyun.sitting() && near(gyun, chair, 1.1D), "he left his office for breakfast: " + gyun.position());
                    h.assertTrue(gyun.getVehicle() == officeSeat[0], "breakfast made him leave his chair");
                })
                .thenExecute(() -> time(h, quiet, 5500))
                .thenExecuteAfter(60, () -> {
                    h.assertTrue(gyun.doingKind() == Kind.COUNT && near(gyun, chair, 1.1D), "back to the books: " + gyun.doingKind() + " " + gyun.position());
                    h.assertTrue(gyun.getVehicle() == officeSeat[0], "returning to the books made him leave his chair");
                })
                // dinner: the same
                .thenExecute(() -> time(h, quiet, 9300))
                .thenExecuteAfter(60, () -> {
                    h.assertTrue(gyun.doingKind() == Kind.EAT && near(gyun, chair, 1.1D), "dinner away from the desk: " + gyun.doingKind() + " " + gyun.position());
                    h.assertTrue(gyun.getVehicle() == officeSeat[0], "dinner made him leave his chair");
                })
                // at dawn he is counting too: the formation of the juniors is none of his business
                .thenExecute(() -> time(h, quiet, 300))
                .thenExecuteAfter(60, () -> {
                    h.assertTrue(gyun.doingKind() == Kind.COUNT && near(gyun, chair, 1.1D), "dawn: " + gyun.doingKind() + " " + gyun.position());
                })
                .thenSucceed();
    }

    /**
     * Sect check 24.10 «глава финансов не на своём стуле сидит»: the office has his armchair behind the table (only his role) and the
     * visitors' chairs on the near side; he sits in the armchair, not in the nearest chair.
     */
    @GameTest(template = YARD, timeoutTicks = 400, batch = "sect_council_armchair")
    public static void financierSitsInHisOwnArmchairNotTheVisitorsChair(GameTestHelper h) {
        Stage stage = new Stage(h);
        stage.spot("spot.arm", "seat_armchair", "sit", 21, 16, 21, 17, 0.0F, "treasury.f2.r1", List.of("elder_treasury"));
        SectLayout layout = stage.layout();
        time(h, 1, 3000);
        SectDisciple gyun = npc(h, layout, "tae_gyun", 3.5D, 19.5D);
        BlockPos armchair = stage.p(21, 16);
        BlockPos chair = stage.p(20, 18);
        h.succeedWhen(() -> {
            h.assertTrue(gyun.sitting() && near(gyun, armchair, 1.1D), "the financier is not in his armchair: " + gyun.position() + " sitting=" + gyun.sitting());
            h.assertFalse(near(gyun, chair, 0.9D), "he sits in the visitors' chair");
            h.assertTrue(gyun.doingKind() == Kind.COUNT, "he is not counting: " + gyun.doingKind());
        });
    }

    /**
     * Sect check 24.10 «на втором этаже, на собрании старейшин, просто уходят»: an intruder who stays is not left with a remark: the guard
     * walks up to HIM and takes him out (forced escort: the player is carried along behind the guard); if the escort is over and he is still
     * inside, or back, a guard comes for him again, with the «again» line.
     */
    @GameTest(template = YARD, timeoutTicks = 600, batch = "sect_council_expel")
    public static void intruderWhoStaysIsTakenOutForRealAndFetchedAgain(GameTestHelper h) {
        SectCouncilIntrusion.clearPause();
        Stage stage = new Stage(h);
        SectLayout layout = stage.layout();
        long day = councilDay();
        time(h, day, C.from() + 100);
        npc(h, layout, C.members().get(0), 9.5D, 5.5D);
        SectDisciple guard = npc(h, layout, SectRota.dayWatch(day, 0).key(), 2.5D, 6.5D);
        ServerPlayer p = playerInLevel(h, SectState.NONE.joined().contribute(30), 12.5D, 9.5D);
        double start = guard.distanceTo(p);
        boolean[] pushed = {false};
        h.onEachTick(() -> pushed[0] |= p.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4D);
        h.startSequence()
                .thenExecute(() -> SectCouncilIntrusion.check(p, layout))
                .thenExecute(() -> h.assertTrue(guard.escortGoal().forcedFor(p), "the guard did not set out for the intruder"))
                .thenWaitUntil(() -> {
                    h.assertTrue(guard.distanceTo(p) < Math.min(7.0D, start - 3.0D), "the guard stays at his door: " + guard.distanceTo(p));
                    h.assertTrue(pushed[0], "the intruder is not carried along");
                })
                // the escort is over (or he slipped away) and he is still inside: the guard comes again, the count of the day stays
                .thenExecute(() -> {
                    guard.escortGoal().cancel();
                    p.getPersistentData().putLong("murim_council_try", h.getLevel().getGameTime() - SectCouncilIntrusion.RETRY - 1);
                    SectCouncilIntrusion.check(p, layout);
                })
                .thenExecute(() -> {
                    h.assertTrue(guard.escortGoal().forcedFor(p), "nobody came for him the second time");
                    h.assertTrue(guard.bubbleKey().startsWith("murim.bubble.council_escort_again"), "no 'again' line: " + guard.bubbleKey());
                    h.assertTrue(SectCouncilIntrusion.count(p) == 1, "one intrusion, counted once: " + SectCouncilIntrusion.count(p));
                    h.assertFalse(SectService.state(p).has(io.github.verycooltimo.murim.sect.seal.PenanceService.SENTENCED), "a sentence for walking in");
                    SectCouncilIntrusion.clearPause();
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------ 3: an elder is called to the council, comes, and is thanked; an elder who stays away loses nothing

    @GameTest(template = YARD, timeoutTicks = 200, batch = "sect_council_elder")
    public static void elderPlayerIsCalledToTheCouncilAttendsAndIsRewardedSkippingCostsNothing(GameTestHelper h) {
        Stage stage = new Stage(h);
        SectLayout layout = stage.layout();
        SectLayoutFile file = layout.file();
        long day = councilDay();
        time(h, day, C.call() + 20);
        SectState elder = SectState.NONE.joined().with(SectPositions.ELDER).contribute(200);
        ServerPlayer p = player(h, elder, 2.5D, 20.5D);
        SectDisciple servant = npc(h, layout, "porter_oh", 2.5D, 4.0D);
        SectDisciple head = npc(h, layout, "tae_hwi", 9.5D, 6.5D);
        head.setNoAi(true);
        servant.setNoAi(true);
        // the call: a tablet goal, a servant walking up
        SectOffices.update(p, file);
        SectJourney.Goal goal = SectJourney.next(p);
        h.assertTrue(goal.id().equals("council"), "the tablet does not name the council: " + goal.id());
        var data = SectTablet.collect(p);
        h.assertTrue(data.goalName().getContents() instanceof TranslatableContents tc && tc.getKey().equals("murim.sect.journey.goal.council"),
                "the tablet's goal is not the council: " + data.goalName());
        h.assertTrue("invite:council".equals(servant.approachKind()), "no servant was sent with the call: " + servant.approachKind());
        long dayOfCall = SectSchedule.day(p.level().getDayTime());
        h.assertTrue(SectService.state(p).entry("office.invited.council", -1L) == dayOfCall, "the invitation is not in the journal");
        // he does not come yet: nothing is forced, nothing changes
        int merit = SectService.state(p).contribution();
        SectOffices.update(p, file);
        h.assertTrue(SectService.state(p).contribution() == merit && SectService.state(p).entry("office.done.council", -1L) < dayOfCall, "rewarded without coming");
        // the session: he sits down on a cushion of the council hall
        time(h, day, C.from() + 40);
        BlockPos cushion = SectPlaces.councilCushions(file).get(5).pos();
        h.getLevel().setBlock(cushion, IntSeating.FLOOR_CUSHION.get().defaultBlockState(), 3);
        Vec3 beside = Vec3.atBottomCenterOf(cushion);
        p.moveTo(beside.x - 1.0D, beside.y, beside.z, 0, 0);
        h.assertTrue(SeatEntity.sit(h.getLevel(), cushion, p), "the player cannot sit on the cushion");
        h.assertTrue(SectOffices.sitsAtCouncil(p, file), "sitting at the council is not recognised");
        for (int i = 0; i < 12; i++) {
            SectOffices.update(p, file);
        }
        h.assertTrue(SectService.state(p).entry("office.done.council", -1L) == dayOfCall, "the attendance is not in the journal");
        h.assertTrue(SectService.state(p).contribution() > merit, "no contribution for sitting at the council");
        h.assertTrue(head.bubbleKey().startsWith("murim.office.council.scene"), "the head did not speak: " + head.bubbleKey());
        h.assertTrue(!SectJourney.next(p).id().equals("council"), "the goal stays after the council was attended");
        // another elder stays away: the window closes, the goal goes away, nothing is taken
        ServerPlayer q = player(h, elder, 3.5D, 20.5D);
        time(h, day, C.call() + 20);
        SectOffices.update(q, file);
        h.assertTrue(SectJourney.next(q).id().equals("council"), "the second elder was not called");
        int before = SectService.state(q).contribution();
        SectStanding standing = SectService.standing(q);
        time(h, day, C.leave() + 100);
        SectOffices.update(q, file);
        h.assertTrue(!SectJourney.next(q).id().equals("council"), "the invitation outlived the council");
        h.assertTrue(SectService.state(q).contribution() == before && SectService.standing(q) == standing, "staying away cost something");
        h.assertTrue(SectService.state(q).member() && SectService.state(q).has(SectPositions.ELDER), "staying away cost the office");
        h.succeed();
    }

    // ------------------------------------------------------------------ 4: the guard at the door of the council hall keeps a junior out

    @GameTest(template = YARD, timeoutTicks = 900, batch = "sect_council_door")
    public static void doorGuardAsksAJuniorToWaitButNotAnElder(GameTestHelper h) {
        Stage stage = new Stage(h);
        SectLayout layout = stage.layout();
        long day = councilDay();
        time(h, day, C.from() + 100);
        SectRoster guardRoster = SectRota.dayWatch(day, 0);
        SectDisciple guard = npc(h, layout, guardRoster.key(), 2.5D, 14.5D);
        SectPlaces.Pick door = SectPlaces.councilDoor(layout.file());
        h.assertTrue(door != null, "the stage has no door of the council hall");
        ServerPlayer junior = playerInLevel(h, SectState.NONE.joined().contribute(30), 1.5D, 6.5D);
        h.startSequence()
                .thenWaitUntil(() -> {
                    SectLife.Resolved routine = SectLife.resolve(guard);
                    h.assertTrue(routine != null && routine.task().kind() == Kind.GUARD && SectSchedule.COUNCIL_DOOR.equals(routine.task().partner()),
                            "the guard is not on the council door: " + guard.doingKind() + " " + routine);
                    h.assertTrue(guard.position().distanceTo(new Vec3(door.x(), door.y(), door.z())) < 1.6D, "not at the door: " + guard.position());
                    h.assertTrue(guard.bubbleKey().startsWith("murim.bubble.council_door"), "the guard did not ask the junior to wait: " + guard.bubbleKey());
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------ 5: the audit with the financier and the quarrel in the court

    @GameTest(template = YARD, timeoutTicks = 700, batch = "sect_council_audit")
    public static void elderStandingByTheFinancierAuditsAndTheJudgedJuniorsCalmDown(GameTestHelper h) {
        Stage stage = new Stage(h);
        SectLayout layout = stage.layout();
        SectLayoutFile file = layout.file();
        SectEventsFile.Invitation audit = SectEventsFile.shipped().invitation("audit");
        long auditDay = audit.days().iterator().next();
        time(h, auditDay, audit.from() + 50);
        SectDisciple gyun = npc(h, layout, "tae_gyun", 19.5D, 18.5D);
        ServerPlayer p = player(h, SectState.NONE.joined().with(SectPositions.ELDER).contribute(200), 19.5D, 19.5D);
        SectOffices.update(p, file);
        h.assertTrue(SectJourney.next(p).id().equals("audit"), "the elder was not asked to the audit: " + SectJourney.next(p).id());
        h.startSequence()
                .thenWaitUntil(() -> {
                    h.assertTrue(gyun.doingKind() == Kind.COUNT && gyun.sitting(), "the financier is not at his books: " + gyun.doingKind());
                })
                .thenExecute(() -> {
                    int before = SectService.state(p).contribution();
                    p.moveTo(gyun.getX() + 1.5D, gyun.getY(), gyun.getZ(), 0, 0);
                    for (int i = 0; i < 9; i++) {
                        SectOffices.update(p, file);
                    }
                    h.assertTrue(SectService.state(p).entry("office.done.audit", -1L) == SectSchedule.day(p.level().getDayTime()), "the audit was not answered");
                    h.assertTrue(SectService.state(p).contribution() > before, "no contribution for the audit");
                    h.assertTrue(gyun.bubbleKey().startsWith("murim.office.audit.scene"), "the financier did not speak: " + gyun.bubbleKey());
                })
                // the quarrel: two juniors in the court, the elder hears them out, they bow and stop
                .thenExecute(() -> {
                    SectEventsFile.Invitation dispute = SectEventsFile.shipped().invitation("dispute");
                    long disputeDay = dispute.days().iterator().next();
                    time(h, disputeDay, dispute.from() + 60);
                    List<SectRoster> pair = SectOffices.disputePair(disputeDay);
                    SectDisciple a = npc(h, layout, pair.get(0).key(), 3.5D, 14.5D);
                    SectDisciple b = npc(h, layout, pair.get(1).key(), 3.5D, 16.5D);
                    ServerPlayer q = player(h, SectState.NONE.joined().with(SectPositions.ELDER).contribute(200), 12.5D, 18.5D);
                    SectOffices.update(q, file);
                    h.assertTrue(SectJourney.next(q).id().equals("dispute"), "no call to the quarrel: " + SectJourney.next(q).id());
                    h.runAfterDelay(250, () -> {
                        h.assertTrue(a.doingKind() == Kind.ARGUE && b.doingKind() == Kind.ARGUE, "the juniors do not quarrel: " + a.doingKind() + " " + b.doingKind());
                        q.moveTo(a.getX() + 1.0D, a.getY(), a.getZ() + 1.5D, 0, 0);
                        int before = SectService.state(q).contribution();
                        for (int i = 0; i < 9; i++) {
                            SectOffices.update(q, file);
                        }
                        h.assertTrue(SectService.state(q).contribution() > before, "no contribution for judging");
                        h.assertTrue(a.getPersistentData().getLong(SectOffices.DISPUTE_DONE) == SectSchedule.day(q.level().getDayTime())
                                && b.getPersistentData().getLong(SectOffices.DISPUTE_DONE) == SectSchedule.day(q.level().getDayTime()), "the juniors were not judged");
                        h.succeed();
                    });
                });
    }

    // ------------------------------------------------------------------ 6: a first disciple leads the line

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_council_line")
    public static void firstDiscipleCalledToLeadTheLineIsThankedOnceTheLineIsLed(GameTestHelper h) {
        Stage stage = new Stage(h);
        SectLayoutFile file = stage.layout().file();
        SectEventsFile.Invitation lead = SectEventsFile.shipped().invitation("lead_formation");
        long day = lead.days().iterator().next();
        time(h, day, 300);
        SectState first = SectState.NONE.joined().with(SectPositions.FIRST).contribute(100);
        ServerPlayer p = player(h, first, 10.5D, 10.5D);
        SectDisciple mentor = npc(h, stage.layout(), "gyeong_pil", 14.5D, 10.5D);
        mentor.setNoAi(true);
        SectOffices.update(p, file);
        h.assertTrue(SectJourney.next(p).id().equals("lead_formation"), "the first disciple was not called to the line: " + SectJourney.next(p).id());
        int before = SectService.state(p).contribution();
        SectOffices.update(p, file);
        h.assertTrue(SectService.state(p).contribution() == before, "paid before the line was led");
        SectOffices.formationDone(p);
        h.assertTrue(SectService.state(p).contribution() > before, "no contribution for leading the line");
        h.assertTrue(mentor.bubbleKey().startsWith("murim.office.lead_formation.scene"), "the mentor did not speak: " + mentor.bubbleKey());
        SectOffices.formationDone(p);
        int paid = SectService.state(p).contribution();
        SectOffices.formationDone(p);
        h.assertTrue(SectService.state(p).contribution() == paid, "paid twice for one morning");
        // a plain disciple and an elder are not asked to lead the line
        ServerPlayer q = player(h, SectState.NONE.joined().with(SectPositions.ELDER), 10.5D, 11.5D);
        SectOffices.update(q, file);
        h.assertTrue(!SectJourney.next(q).id().equals("lead_formation"), "an elder was asked to lead the line");
        h.succeed();
    }

    // ------------------------------------------------------------------ 7: author 21.10, an intruder at the council, tips

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_council_intruder")
    public static void intruderAtTheCouncilIsScoldedAndLedOutWithoutAnyPenalty(GameTestHelper h) {
        SectCouncilIntrusion.clearPause();
        Stage stage = new Stage(h);
        SectLayout layout = stage.layout();
        long day = councilDay();
        time(h, day, C.from() + 100);
        SectDisciple head = npc(h, layout, C.members().get(0), 9.5D, 5.5D);
        SectDisciple guard = npc(h, layout, SectRota.dayWatch(day, 0).key(), 2.5D, 6.5D);
        ServerPlayer p = playerInLevel(h, SectState.NONE.joined().contribute(30), 9.5D, 9.5D);
        h.assertTrue(SectCouncilIntrusion.intruding(p, layout.file()), "a player in the hall is not an intruder");
        SectCouncilIntrusion.check(p, layout);
        h.assertTrue(head.bubbleKey().startsWith("murim.bubble.council_reprimand"), "the head did not scold: " + head.bubbleKey());
        h.assertTrue(guard.bubbleKey().startsWith("murim.bubble.council_escort."), "the guard did not speak: " + guard.bubbleKey());
        h.assertTrue(guard.escortGoal().forPlayer(p), "the guard does not lead him out");
        h.assertTrue(SectCouncilIntrusion.paused(h.getLevel().getGameTime() + 10), "the meeting did not pause");
        h.assertTrue(SectCouncilIntrusion.count(p) == 1, "count " + SectCouncilIntrusion.count(p));
        h.assertFalse(io.github.verycooltimo.murim.sect.seal.PenanceService.sentenced(p), "a sentence for walking in");
        h.assertFalse(SectService.state(p).has(io.github.verycooltimo.murim.sect.seal.PenanceService.SENTENCED), "jailed");
        // the same day again: counted, the guard speaks his "again" line
        p.getPersistentData().putLong("murim_council_at", h.getLevel().getGameTime() - SectCouncilIntrusion.FRESH - 5);
        SectCouncilIntrusion.check(p, layout);
        h.assertTrue(SectCouncilIntrusion.count(p) == 2, "second count " + SectCouncilIntrusion.count(p));
        h.assertTrue(guard.bubbleKey().startsWith("murim.bubble.council_escort_again"), "no 'again' line: " + guard.bubbleKey());
        // an elder is invited to the table and is not scolded
        ServerPlayer elder = playerInLevel(h, SectState.NONE.joined().with(SectPositions.ELDER), 9.5D, 9.5D);
        h.assertFalse(SectCouncilIntrusion.intruding(elder, layout.file()), "an elder was treated as an intruder");
        // out in the forecourt nobody minds
        ServerPlayer out = playerInLevel(h, SectState.NONE.joined(), 12.5D, 16.5D);
        h.assertFalse(SectCouncilIntrusion.intruding(out, layout.file()), "outside the hall");
        SectCouncilIntrusion.clearPause();
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_council_busy")
    public static void councilElderRefusesBusinessDuringTheCouncilButTalksAfterwards(GameTestHelper h) {
        Stage stage = new Stage(h);
        SectLayout layout = stage.layout();
        long day = councilDay();
        time(h, day, C.from() + 100);
        SectDisciple elder = npc(h, layout, C.members().get(1), 8.5D, 6.5D);
        ServerPlayer p = playerInLevel(h, SectState.NONE.joined().contribute(30), 9.5D, 8.5D);
        DialogueService.open(p, elder);
        h.assertTrue(elder.bubbleKey().startsWith("murim.bubble.council_busy"), "no refusal: " + elder.bubbleKey());
        h.assertFalse(p.getData(ModAttachments.DIALOGUE).active(), "a dialogue opened during the council");
        time(h, day, C.to() + 200);
        h.assertFalse(SectCouncilIntrusion.refuses(p, elder), "refuses outside the council");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_tip_bed")
    public static void bedTipIsSaidOnceOverTheMentorsHeadAndSaved(GameTestHelper h) {
        Stage stage = new Stage(h);
        SectLayout layout = stage.layout();
        String mentorKey = SectRoster.ALL.stream().filter(r -> r.role() == SectRole.MENTOR).findFirst().orElseThrow().key();
        SectDisciple mentor = npc(h, layout, mentorKey, 12.5D, 15.5D);
        BlockPos bed = h.absolutePos(new BlockPos(14, 2, 18));
        h.getLevel().setBlock(bed, net.minecraft.world.level.block.Blocks.RED_BED.defaultBlockState(), 3);
        ServerPlayer p = playerInLevel(h, SectState.NONE.joined().with("met_mentor").with("room.bed").record("room.pos", bed.asLong()), 12.5D, 17.5D);
        h.assertTrue(SectTips.bedDue(p), "the tip is not due");
        for (int i = 0; i < SectTips.SETTLE - 1; i++) h.assertFalse(SectTips.checkBed(p), "said too early");
        h.assertTrue(SectTips.checkBed(p), "not said");
        h.assertTrue(mentor.bubbleKey().equals("murim.tip.bed"), "wrong bubble: " + mentor.bubbleKey());
        h.assertTrue(SectTips.seen(p, SectTips.BED), "not marked");
        // saved with the player: it survives the codec
        SectState back = SectState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                SectState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, SectService.state(p)).getOrThrow()).getOrThrow();
        h.assertTrue(back.has(SectTips.flag(SectTips.BED)), "flag lost in the save");
        h.assertFalse(SectTips.show(p, mentor, SectTips.BED), "said twice");
        h.assertFalse(SectTips.bedDue(p), "still due");
        h.succeed();
    }
}
