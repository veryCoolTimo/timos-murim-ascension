package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.IntSeating;
import io.github.verycooltimo.murim.entity.SeatStyle;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The posture of a person of the sect as a state machine (author 19.10: «the disciples get stuck in the sleeping animation: they
 * walk, but lie. Then the sitting animation and the lotus pose get mixed. The head of the sect sits on a chair in the lotus and his
 * model breaks»). Server side, what the clients are sent:
 *
 * <ul>
 *   <li>sleeping and sitting end the moment he moves, gets a path, is put somewhere else or has to fight;</li>
 *   <li>the way of sitting is the furniture's: a chair, an armchair (the throne), a stool or a bench is the chair sit on the seat top,
 *       a cushion, a mat or the floor is the lotus; the two never hold at once;</li>
 *   <li>a reload (save and load of the entity) leaves no stale posture: the routine sets it again.</li>
 * </ul>
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectPoseGameTests {

    private static final String YARD = "sect_yard";

    private SectPoseGameTests() {
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

    private static SectLayoutFile file(List<SectLayoutFile.Room> rooms, List<SectLayoutFile.Bed> beds, List<SectLayoutFile.Spot> spots, Map<String, String> roles) {
        return new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), roles, List.of(), rooms, List.of(), List.of(), beds, spots,
                List.of(), List.of(), List.of());
    }

    /** Tick of the day: 5400 is the reading hour of tae_rok (the training period begins with a lecture), 12000 the evening, 14000 the night. */
    private static void dayTime(GameTestHelper h, int tickOfDay) {
        h.getLevel().setDayTime(Math.floorDiv(h.getLevel().getDayTime(), 24000L) * 24000L + tickOfDay);
    }

    private static BlockPos p(GameTestHelper h, int x, int y, int z) {
        return h.absolutePos(new BlockPos(x, y, z));
    }

    /** A person with a name and the routine on the mini layout (the routine decides where he is and what he does). */
    private static SectDisciple member(GameTestHelper h, SectLayout layout, String key, double x, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setLayout(layout);
        d.setKeepAwake(true);
        Vec3 at = h.absoluteVec(new Vec3(x, 2, z));
        d.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    /** A bare person: no name, no routine, nobody tells him what to do (the posture rules alone). */
    private static SectDisciple bare(GameTestHelper h, double x, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        Vec3 at = h.absoluteVec(new Vec3(x, 2, z));
        d.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static void bed(GameTestHelper h, int x, int z) {
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
        h.setBlock(new BlockPos(x, 2, z), bed.setValue(BedBlock.PART, BedPart.FOOT));
        h.setBlock(new BlockPos(x + 1, 2, z), bed.setValue(BedBlock.PART, BedPart.HEAD));
    }

    // ------------------------------------------------------------------ lying down

    /** The 19.10 report: a path starts for a sleeper nobody woke, he must get up at once and walk upright. */
    @GameTest(template = YARD, timeoutTicks = 200, batch = "sect_pose_sleep_path")
    public static void aSleeperWhoGetsAPathStandsUpAndWalksUpright(GameTestHelper h) {
        bed(h, 8, 8);
        SectDisciple d = bare(h, 10.5, 8.5);
        d.setNoAi(false);
        d.startSleeping(p(h, 9, 2, 8));
        h.assertTrue(d.isSleeping() && d.pose() == SectPose.SLEEP && d.getPose() == Pose.SLEEPING, "does not lie: " + d.pose() + " " + d.getPose());
        Vec3 goal = h.absoluteVec(new Vec3(16.5, 2, 12.5));
        boolean[] walked = {false};
        h.onEachTick(() -> {
            if (h.getTick() == 10) {
                h.assertTrue(d.isSleeping(), "woke by himself before anything happened");
                d.getNavigation().moveTo(goal.x, goal.y, goal.z, 0.7D);   // a path, and nobody calls wake()
            }
            if (h.getTick() > 11) {
                h.assertTrue(!d.isSleeping() && d.getPose() != Pose.SLEEPING && d.pose() != SectPose.SLEEP,
                        "walks lying at tick " + h.getTick() + ": sleeping=" + d.isSleeping() + " pose=" + d.getPose() + "/" + d.pose());
                walked[0] |= d.distanceToSqr(goal) < 100.0D && !d.getNavigation().isDone();
            }
        });
        h.succeedWhen(() -> h.assertTrue(walked[0] && d.getPose() == Pose.STANDING && d.position().distanceTo(goal) < 2.0D,
                "did not walk to the goal: " + d.position() + " " + d.getPose()));
    }

    /** Moved by something else (the settle command, the relocation of a stuck walker): not in the bed any more, so not lying. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "sect_pose_sleep_moved")
    public static void aSleeperPutSomewhereElseGetsUp(GameTestHelper h) {
        bed(h, 8, 8);
        SectDisciple d = bare(h, 10.5, 8.5);
        d.startSleeping(p(h, 9, 2, 8));
        Vec3 away = h.absoluteVec(new Vec3(16.5, 2, 16.5));
        h.runAfterDelay(5, () -> d.moveTo(away.x, away.y, away.z, 0, 0));
        h.succeedWhen(() -> h.assertTrue(h.getTick() > 8 && !d.isSleeping() && d.getPose() == Pose.STANDING && d.pose() == SectPose.NONE,
                "still lying away from the bed: " + d.isSleeping() + " " + d.getPose() + " " + d.pose()));
    }

    /** Asleep in a bed means the lying pose, also after a reload (the pose is not saved, the bed is). */
    @GameTest(template = YARD, timeoutTicks = 80, batch = "sect_pose_sleep_reload")
    public static void aSleeperLoadedFromASaveLiesAndWakesOnAPath(GameTestHelper h) {
        bed(h, 8, 8);
        SectDisciple d = bare(h, 10.5, 8.5);
        d.startSleeping(p(h, 9, 2, 8));
        CompoundTag tag = d.saveWithoutId(new CompoundTag());
        d.discard();
        SectDisciple again = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        again.load(tag);
        again.setUUID(java.util.UUID.randomUUID());   // the discarded one still holds its UUID until the end of the tick
        h.getLevel().addFreshEntity(again);
        again.setNoAi(false);
        Vec3 goal = h.absoluteVec(new Vec3(16.5, 2, 12.5));
        h.runAfterDelay(8, () -> {
            h.assertTrue(again.isSleeping() && again.pose() == SectPose.SLEEP && again.getPose() == Pose.SLEEPING,
                    "a loaded sleeper does not lie: " + again.isSleeping() + " " + again.pose() + " " + again.getPose());
            again.getNavigation().moveTo(goal.x, goal.y, goal.z, 0.7D);
        });
        h.succeedWhen(() -> h.assertTrue(h.getTick() > 12 && !again.isSleeping() && again.getPose() == Pose.STANDING && again.pose() == SectPose.NONE,
                "the loaded sleeper still lies: " + again.isSleeping() + " " + again.getPose() + " " + again.pose()));
    }

    // ------------------------------------------------------------------ sitting

    private static void seatStandsUp(GameTestHelper h, SeatStyle style, Block seat, double seatTop) {
        BlockPos chair = p(h, 12, 2, 14);
        h.setBlock(new BlockPos(12, 2, 14), seat);
        SectDisciple d = bare(h, 12.5, 15.5);
        d.setNoAi(false);
        Vec3 exit = d.position();
        Vec3 top = SectLife.seatTop(h.getLevel(), chair);
        if (style == SeatStyle.CHAIR) {
            h.assertTrue(top != null && Math.abs(top.y - (chair.getY() + seatTop)) < 1.0E-6D, "seat top: " + top);
        }
        d.sit(style, top, 180.0F, exit);
        d.setPose(SectPose.SIT);
        Vec3 goal = h.absoluteVec(new Vec3(18.5, 2, 18.5));
        h.runAfterDelay(20, () -> {
            h.assertTrue(d.seatStyle() == style && d.sitting(), "does not sit: " + d.seatStyle());
            if (style == SeatStyle.CHAIR) {
                h.assertTrue(d.getVehicle() instanceof io.github.verycooltimo.murim.decor.SeatEntity, "the chair does not hold its sitter");
            }
            h.assertTrue(d.onGround(), "a supported sitter must be able to start ground navigation: " + d.position());
            d.getNavigation().moveTo(goal.x, goal.y, goal.z, 0.7D);   // a path starts: no more sitting
        });
        h.succeedWhen(() -> h.assertTrue(h.getTick() > 24 && d.seatStyle() == SeatStyle.NONE && d.pose() == SectPose.NONE
                        && !d.isPassenger() && !d.isNoGravity() && d.position().distanceTo(goal) < 2.0D,
                "still seated after the path started: " + d.seatStyle() + " " + d.pose() + " nogravity=" + d.isNoGravity() + " " + d.position()));
    }

    @GameTest(template = YARD, timeoutTicks = 200, batch = "sect_pose_sit_path_chair")
    public static void aSitterOnAChairWhoGetsAPathStandsUpAndWalks(GameTestHelper h) {
        seatStandsUp(h, SeatStyle.CHAIR, IntSeating.CHAIR.get(), 9.0D / 16.0D);
    }

    @GameTest(template = YARD, timeoutTicks = 200, batch = "sect_pose_sit_path_floor")
    public static void aSitterInTheLotusWhoGetsAPathStandsUpAndWalks(GameTestHelper h) {
        seatStandsUp(h, SeatStyle.LOTUS, Blocks.AIR, 0.0D);
    }

    /**
     * A mat is 1 px high: its seat entity stands below the block boundary. Standing up from it must leave the sitter on the
     * mat's top, not in the block under it (the head of the sect dropped 12 px into his dais at the end of the scene, 10.11),
     * and a person who stood up a moment ago sits down again at once (vanilla's 60-tick boarding cooldown is not for furniture).
     */
    @GameTest(template = YARD, timeoutTicks = 120, batch = "sect_pose_mat_dismount")
    public static void standingUpFromAMatLeavesHimOnTheMatAndHeCanSitAgainAtOnce(GameTestHelper h) {
        BlockPos mat = p(h, 12, 2, 14);
        h.setBlock(new BlockPos(12, 2, 14), IntSeating.MAT.get());
        SectDisciple d = bare(h, 12.5, 15.5);
        d.setNoAi(false);
        Vec3 exit = d.position();
        Vec3 top = SectLife.seatTop(h.getLevel(), mat);
        h.assertTrue(top != null && Math.abs(top.y - (mat.getY() + 1.0D / 16.0D)) < 1.0E-6D, "mat top: " + top);
        d.sit(SeatStyle.LOTUS, top, 180.0F, exit);
        d.setPose(SectPose.SIT);
        h.runAfterDelay(20, () -> {
            h.assertTrue(d.getVehicle() instanceof io.github.verycooltimo.murim.decor.SeatEntity, "the mat does not hold its sitter: " + d.position());
            h.assertTrue(Math.abs(d.getY() - top.y) < 1.0E-3D, "seated height: " + d.getY() + " want " + top.y);
            d.sit(SeatStyle.NONE);
            h.assertTrue(d.getY() >= mat.getY() - 1.0E-6D, "stood up INTO the block under the mat: y=" + d.getY() + " mat=" + mat.getY());
            // Sit down again in the same tick: no boarding cooldown for furniture.
            d.sit(SeatStyle.LOTUS, top, 180.0F, exit);
            d.setPose(SectPose.SIT);
        });
        h.runAfterDelay(40, () -> {
            h.assertTrue(d.sitting() && d.getVehicle() instanceof io.github.verycooltimo.murim.decor.SeatEntity,
                    "could not sit down again after standing up: style=" + d.seatStyle() + " vehicle=" + d.getVehicle() + " y=" + d.getY());
            h.assertTrue(Math.abs(d.getY() - top.y) < 1.0E-3D, "re-seated height: " + d.getY() + " want " + top.y);
        });
        h.succeedWhen(() -> h.assertTrue(h.getTick() > 45, "waiting"));
    }

    // ------------------------------------------------------------------ the furniture decides how he sits

    /**
     * An elder at the desk of his house sits where the plan puts a seat. {@code kind} is what the layout says it is, {@code seat} the
     * real block; the sitter ends on the seat surface in {@code style}.
     */
    private static void atTheDesk(GameTestHelper h, String kind, Block seat, double seatTop, SeatStyle style) {
        dayTime(h, 5400);
        Direction facing = Direction.NORTH;
        BlockState state = seat.defaultBlockState();
        if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            state = state.setValue(HorizontalDirectionalBlock.FACING, facing);
        }
        h.setBlock(new BlockPos(12, 2, 14), state);
        BlockPos desk = p(h, 12, 2, 12);
        BlockPos chair = p(h, 12, 2, 14);
        List<SectLayoutFile.Room> rooms = List.of(new SectLayoutFile.Room("scriptures.f0.r1", "scriptures", 0, p(h, 0, 2, 0).getY(),
                new SectLayoutFile.Rect(p(h, 8, 2, 8).getX(), p(h, 8, 2, 8).getZ(), p(h, 16, 2, 16).getX(), p(h, 16, 2, 16).getZ()),
                "library", "ok", List.of(), List.of(), 40));
        List<SectLayoutFile.Spot> spots = new ArrayList<>();
        spots.add(new SectLayoutFile.Spot("spot.desk", "work_desk", "stand", desk, null, desk, 0.0F, "scriptures.f0.r1", "scriptures",
                null, null, List.of("elder"), "ok", -1, -1));
        // The way in is the cell behind the chair, as in the built sect (the front is the desk).
        spots.add(new SectLayoutFile.Spot("spot.chair", kind, "sit", chair, chair.south(), null, 180.0F, "scriptures.f0.r1", "scriptures",
                null, null, List.of("elder"), "ok", -1, -1));
        var layout = new Mini(h.absoluteVec(new Vec3(12.5, 2, 12.5)), file(rooms, List.of(), spots, Map.of("tae_rok", "elder")));
        SectDisciple d = member(h, layout, "tae_rok", 6.5, 15.5);
        Vec3 want = Vec3.atBottomCenterOf(chair).add(0.0D, seatTop, 0.0D);
        List<String> why = new ArrayList<>();
        h.succeedWhen(() -> {
            why.clear();
            why.add("style=" + d.seatStyle() + " pos=" + d.position() + " want=" + want + " pose=" + d.pose() + " gravity=" + !d.isNoGravity()
                    + " doing=" + d.doingKind() + " resolved=" + SectLife.resolve(d) + " dormant=" + d.dormant() + " alive=" + d.isAlive()
                    + " nav_done=" + d.getNavigation().isDone() + " stuck=" + d.walking().stuckStage() + " time=" + h.getLevel().getDayTime());
            h.assertTrue(d.seatStyle() == style, "not in the " + style + ": " + why);
            // On a seat he walks onto he stops within half a block of its middle; on a chair he is put on the middle of it.
            double flat = Math.hypot(d.getX() - want.x, d.getZ() - want.z);
            h.assertTrue(Math.abs(d.getY() - want.y) < 0.06D && flat < (SectLife.seatTop(h.getLevel(), chair) != null ? 0.05D : 0.6D),
                    "not on the seat surface: " + why);
            h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(d.getYRot() - 180.0F)) < 25.0F, "not facing the desk: " + d.getYRot());
            h.assertTrue(d.atPlace(SectLife.resolve(d).spot()), "the routine does not count the seat as his place");
        });
    }

    @GameTest(template = YARD, timeoutTicks = 700, batch = "sect_pose_seat_chair")
    public static void aChairIsSatOnInTheChairSitOnTheSeatSurface(GameTestHelper h) {
        atTheDesk(h, "seat_chair", IntSeating.CHAIR.get(), 9.0D / 16.0D, SeatStyle.CHAIR);
    }

    @GameTest(template = YARD, timeoutTicks = 700, batch = "sect_pose_seat_armchair")
    public static void anArmchairTheThroneIsSatOnInTheChairSitOnTheSeatSurface(GameTestHelper h) {
        atTheDesk(h, "seat_armchair", IntSeating.ARMCHAIR.get(), 10.0D / 16.0D, SeatStyle.CHAIR);
    }

    @GameTest(template = YARD, timeoutTicks = 700, batch = "sect_pose_seat_stool")
    public static void aStoolIsSatOnInTheChairSit(GameTestHelper h) {
        atTheDesk(h, "seat_stool", IntSeating.STOOL.get(), 8.5D / 16.0D, SeatStyle.CHAIR);
    }

    @GameTest(template = YARD, timeoutTicks = 700, batch = "sect_pose_seat_cushion")
    public static void aCushionIsSatOnInTheLotus(GameTestHelper h) {
        atTheDesk(h, "cushion", IntSeating.FLOOR_CUSHION.get(), 2.5D / 16.0D, SeatStyle.LOTUS);
    }

    // ------------------------------------------------------------------ a whole day

    /**
     * Night in his bed, morning at the desk on a chair, evening on his feet: one person through the transitions, and at every tick
     * the postures stay true to each other.
     */
    @GameTest(template = YARD, timeoutTicks = 2400, batch = "sect_pose_day")
    public static void fromTheBedToTheChairAndBackOnHisFeetTheStatesNeverContradict(GameTestHelper h) {
        BlockPos desk = p(h, 14, 2, 12);
        BlockPos chair = p(h, 14, 2, 14);
        h.setBlock(new BlockPos(14, 2, 14), IntSeating.CHAIR.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        bed(h, 8, 8);
        BlockPos head = p(h, 9, 2, 8);
        List<SectLayoutFile.Room> rooms = List.of(new SectLayoutFile.Room("scriptures.f0.r1", "scriptures", 0, p(h, 0, 2, 0).getY(),
                new SectLayoutFile.Rect(p(h, 6, 2, 6).getX(), p(h, 6, 2, 6).getZ(), p(h, 20, 2, 20).getX(), p(h, 20, 2, 20).getZ()),
                "library", "ok", List.of(), List.of(), 40));
        List<SectLayoutFile.Spot> spots = new ArrayList<>();
        spots.add(new SectLayoutFile.Spot("spot.desk", "work_desk", "stand", desk, null, desk, 0.0F, "scriptures.f0.r1", "scriptures",
                null, null, List.of("elder"), "ok", -1, -1));
        spots.add(new SectLayoutFile.Spot("spot.chair", "seat_chair", "sit", chair, chair.south(), null, 180.0F, "scriptures.f0.r1", "scriptures",
                null, null, List.of("elder"), "ok", -1, -1));
        List<SectLayoutFile.Bed> beds = List.of(new SectLayoutFile.Bed("bed.tae_rok", head, p(h, 10, 2, 8), -90.0F, null, "elder_1", "elder",
                "tae_rok", 1, "ok"));
        var layout = new Mini(h.absoluteVec(new Vec3(12.5, 2, 12.5)), file(rooms, beds, spots, Map.of("tae_rok", "elder")));
        dayTime(h, 14000);
        SectDisciple d = member(h, layout, "tae_rok", 12.5, 9.5);
        Vec3 bedAt = new Vec3(head.getX() + 0.5D, head.getY() + 0.6875D, head.getZ() + 0.5D);
        int[] phase = {0};
        int[] mismatch = {0};
        Vec3[] last = {d.position()};
        boolean[] slept = {false};
        long[] sat = {0, 0, 0};   // ticks asleep, ticks on the chair, ticks standing after the chair
        h.onEachTick(() -> {
            Vec3 now = d.position();
            double moved = Math.hypot(now.x - last[0].x, now.z - last[0].z);
            last[0] = now;
            // the invariants of the posture, every tick
            if (d.isSleeping()) {
                // (the tick he lies down he is put into the bed: that move is not walking)
                h.assertTrue(d.getNavigation().isDone() && now.distanceToSqr(bedAt) < 2.0D && (!slept[0] || moved < 0.08D),
                        "lies while moving at tick " + h.getTick() + ": pos=" + now + " nav_done=" + d.getNavigation().isDone() + " moved=" + moved);
                sat[0]++;
            }
            slept[0] = d.isSleeping();
            h.assertTrue(d.getPose() != Pose.SLEEPING || d.isSleeping(), "the body lies, the person does not sleep");
            if (d.seatStyle() == SeatStyle.CHAIR) {
                h.assertTrue(!d.isSleeping(), "sits and sleeps");
                sat[1]++;
            }
            boolean seatedPoseWithoutSeat = d.pose().seated() && !d.sitting() && !d.isSleeping();
            mismatch[0] = seatedPoseWithoutSeat ? mismatch[0] + 1 : 0;
            h.assertTrue(mismatch[0] <= 25, "a seated pose without a seat for " + mismatch[0] + " ticks: " + d.pose());
            // the day
            if (phase[0] == 0 && d.isSleeping()) {
                phase[0] = 1;
                dayTime(h, 5400);   // morning: get up, go to the desk
            }
            if (phase[0] == 1 && d.seatStyle() == SeatStyle.CHAIR && sat[1] > 60) {
                h.assertTrue(d.position().distanceTo(Vec3.atBottomCenterOf(chair).add(0.0D, 9.0D / 16.0D, 0.0D)) < 0.05D, "not on the chair surface: " + d.position());
                phase[0] = 2;
                dayTime(h, 12000);    // evening: the desk is done with
            }
            if (phase[0] == 2 && !d.sitting() && !d.isSleeping()) {
                sat[2]++;
            }
        });
        h.succeedWhen(() -> h.assertTrue(phase[0] == 2 && sat[2] > 40 && d.getPose() == Pose.STANDING && !d.isNoGravity() && d.onGround()
                        && d.position().y < chair.getY() + 0.3D,
                "the day did not run: phase=" + phase[0] + " asleep=" + sat[0] + " on the chair=" + sat[1] + " after=" + sat[2] + " pos=" + d.position()
                        + " sitting=" + d.sitting() + " ground=" + d.onGround()));
    }

    // ------------------------------------------------------------------ a reload

    /** The sitting style and the seated pose are not saved; a person loaded from a save stands and the routine sits him down again. */
    @GameTest(template = YARD, timeoutTicks = 900, batch = "sect_pose_sit_reload")
    public static void aSitterLoadedFromASaveStandsAndTheRoutineSitsHimAgain(GameTestHelper h) {
        dayTime(h, 5400);
        BlockPos desk = p(h, 12, 2, 12);
        BlockPos chair = p(h, 12, 2, 14);
        h.setBlock(new BlockPos(12, 2, 14), IntSeating.CHAIR.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        List<SectLayoutFile.Room> rooms = List.of(new SectLayoutFile.Room("scriptures.f0.r1", "scriptures", 0, p(h, 0, 2, 0).getY(),
                new SectLayoutFile.Rect(p(h, 8, 2, 8).getX(), p(h, 8, 2, 8).getZ(), p(h, 16, 2, 16).getX(), p(h, 16, 2, 16).getZ()),
                "library", "ok", List.of(), List.of(), 40));
        List<SectLayoutFile.Spot> spots = new ArrayList<>();
        spots.add(new SectLayoutFile.Spot("spot.desk", "work_desk", "stand", desk, null, desk, 0.0F, "scriptures.f0.r1", "scriptures",
                null, null, List.of("elder"), "ok", -1, -1));
        spots.add(new SectLayoutFile.Spot("spot.chair", "seat_chair", "sit", chair, chair.south(), null, 180.0F, "scriptures.f0.r1", "scriptures",
                null, null, List.of("elder"), "ok", -1, -1));
        var layout = new Mini(h.absoluteVec(new Vec3(12.5, 2, 12.5)), file(rooms, List.of(), spots, Map.of("tae_rok", "elder")));
        SectDisciple d = member(h, layout, "tae_rok", 9.5, 15.5);
        boolean[] reloaded = {false};
        SectDisciple[] now = {d};
        h.onEachTick(() -> {
            if (!reloaded[0] && d.seatStyle() == SeatStyle.CHAIR && h.getTick() > 100) {
                reloaded[0] = true;
                CompoundTag tag = d.saveWithoutId(new CompoundTag());
                d.discard();
                SectDisciple again = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
                again.load(tag);
                again.setUUID(java.util.UUID.randomUUID());
                again.setLayout(layout);
                again.setKeepAwake(true);
                h.getLevel().addFreshEntity(again);
                now[0] = again;
                h.assertTrue(again.seatStyle() == SeatStyle.NONE && again.pose() == SectPose.NONE && !again.isNoGravity(),
                        "a stale posture came back from the save: " + again.seatStyle() + " " + again.pose());
            }
        });
        h.succeedWhen(() -> h.assertTrue(reloaded[0] && now[0] != d && now[0].seatStyle() == SeatStyle.CHAIR
                        && now[0].position().distanceTo(Vec3.atBottomCenterOf(chair).add(0.0D, 9.0D / 16.0D, 0.0D)) < 0.05D,
                "the routine did not sit the loaded person down: " + now[0].seatStyle() + " " + now[0].position()));
    }

    // ------------------------------------------------------------------ 14.11: chairs

    /**
     * Standing up from a chair whose front and sides are walled in (the desk in front, a shelf beside): vanilla's stand-up search
     * found no free side and put him ON the seat block, a block above the floor, and he came down from there (author 14.11:
     * «they stand ON the chair»). He steps from the seat to the cell he came from and is never above the seat on the way.
     */
    @GameTest(template = YARD, timeoutTicks = 120, batch = "sect_pose_chair_dismount")
    public static void standingUpFromAChairWithItsSidesTakenPutsHimOnTheFloorNotOnTheSeat(GameTestHelper h) {
        BlockPos chair = p(h, 12, 2, 14);
        h.setBlock(new BlockPos(12, 2, 14), IntSeating.CHAIR.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        for (int y = 2; y <= 3; y++) {
            h.setBlock(new BlockPos(11, y, 14), Blocks.STONE);
            h.setBlock(new BlockPos(13, y, 14), Blocks.STONE);
            h.setBlock(new BlockPos(12, y, 13), Blocks.STONE);
        }
        SectDisciple d = bare(h, 12.5, 15.5);
        d.setNoAi(false);
        Vec3 exit = d.position();
        Vec3 top = SectLife.seatTop(h.getLevel(), chair);
        h.assertTrue(top != null, "no seat top");
        d.sit(SeatStyle.CHAIR, top, 180.0F, exit);
        d.setPose(SectPose.SIT);
        double[] highest = {Double.NEGATIVE_INFINITY};
        boolean[] up = {false};
        h.runAfterDelay(20, () -> {
            h.assertTrue(d.getVehicle() instanceof io.github.verycooltimo.murim.decor.SeatEntity, "the chair does not hold its sitter: " + d.position());
            h.assertTrue(Math.abs(d.getY() - top.y) < 1.0E-3D, "seated height: " + d.getY() + " want " + top.y);
            d.sit(SeatStyle.NONE);
            up[0] = true;
            h.assertTrue(!d.isPassenger(), "still riding the chair");
            h.assertTrue(d.getY() <= top.y + 1.0E-3D, "stood up ABOVE the seat: y=" + d.getY() + " seat=" + top.y);
        });
        h.onEachTick(() -> {
            if (up[0]) {
                highest[0] = Math.max(highest[0], d.getY());
                h.assertTrue(highest[0] <= top.y + 0.16D, "on the way down he was above the seat: " + highest[0] + " seat=" + top.y);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(h.getTick() > 40 && up[0], "waiting");
            h.assertTrue(Math.abs(d.getY() - exit.y) < 0.05D && Math.hypot(d.getX() - exit.x, d.getZ() - exit.z) < 0.25D,
                    "not on the floor where he came from: " + d.position() + " exit=" + exit);
            h.assertTrue(d.seatStyle() == SeatStyle.NONE && !d.isPassenger() && !d.isNoGravity(), "still seated: " + d.seatStyle());
        });
    }

    /**
     * Two people, one chair: a lapsed lease handed the chair of a diner to a late comer. The seat refused him, he stood up, walked to
     * it again and sat, every half second (author 14.11: «they sit down on the chair and jump out, again and again»). The seat is now
     * given up at once and the next free one taken; the first diner is not disturbed.
     */
    @GameTest(template = YARD, timeoutTicks = 500, batch = "sect_pose_two_on_a_chair")
    public static void aLateDinerWhoseSeatIsTakenPicksAnotherAndDoesNotBounce(GameTestHelper h) {
        lateDiner(h, 0);
    }

    /** The same meal with the sitters' 20-tick routine phase shifted (the phase is the entity id, so it differs from run to run). */
    @GameTest(template = YARD, timeoutTicks = 500, batch = "sect_pose_two_on_a_chair_8")
    public static void aLateDinerWhoseSeatIsTakenDoesNotBounceInAnotherRoutinePhaseA(GameTestHelper h) {
        lateDiner(h, 8);
    }

    @GameTest(template = YARD, timeoutTicks = 500, batch = "sect_pose_two_on_a_chair_16")
    public static void aLateDinerWhoseSeatIsTakenDoesNotBounceInAnotherRoutinePhaseB(GameTestHelper h) {
        lateDiner(h, 16);
    }

    private static void lateDiner(GameTestHelper h, int idShift) {
        for (int i = 0; i < idShift; i++) new net.minecraft.world.entity.decoration.ArmorStand(h.getLevel(), 0, 0, 0);
        dayTime(h, 10000);
        for (int x : new int[] {10, 14}) {
            h.setBlock(new BlockPos(x, 2, 14), IntSeating.CHAIR.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        }
        BlockPos one = p(h, 10, 2, 14);
        BlockPos two = p(h, 14, 2, 14);
        List<SectLayoutFile.Room> rooms = List.of(new SectLayoutFile.Room("meal", "dining_hall", 0, one.getY(),
                new SectLayoutFile.Rect(p(h, 6, 2, 6).getX(), p(h, 6, 2, 6).getZ(), p(h, 20, 2, 20).getX(), p(h, 20, 2, 20).getZ()),
                "room", "ok", List.of(), List.of(), 100));
        List<SectLayoutFile.Spot> spots = new ArrayList<>();
        spots.add(new SectLayoutFile.Spot("spot.one", "seat_chair", "sit", one, one.south(), null, 180.0F, "meal", "dining_hall",
                null, null, List.of("all"), "ok", -1, -1));
        spots.add(new SectLayoutFile.Spot("spot.two", "seat_chair", "sit", two, two.south(), null, 180.0F, "meal", "dining_hall",
                null, null, List.of("all"), "ok", -1, -1));
        var layout = new Mini(h.absoluteVec(new Vec3(12.5, 2, 12.5)), file(rooms, List.of(), spots, Map.of()));
        // The first diner sits on spot one; his lease is gone, the late one still holds the spot on paper.
        SectDisciple first = bare(h, 10.5, 15.5);
        first.setNoAi(false);
        first.sit(SeatStyle.CHAIR, SectLife.seatTop(h.getLevel(), one), 180.0F, first.position());
        first.setPose(SectPose.EAT);
        SectSpots.of(h.getLevel()).claim("spot.one", "yul_seok", h.getLevel().getGameTime());
        SectDisciple late = member(h, layout, "yul_seok", 10.5, 16.5);
        int[] stoodUp = {0};
        boolean[] was = {false};
        h.onEachTick(() -> {
            // Tick 400 is the end: the callback below ends the test and the harness takes the people away in that same tick,
            // a dead first diner there is the cleanup, not a disturbance (seen twice in the full suite: tick=400 alive=false).
            if (h.getTick() >= 400) return;
            dayTime(h, 10000);   // the whole test is one meal
            boolean now = late.sitting();
            if (was[0] && !now) stoodUp[0]++;
            was[0] = now;
            h.assertTrue(first.sitting() && first.getVehicle() != null || h.getTick() < 10, "the first diner was disturbed: " + first.seatStyle() + " veh=" + first.getVehicle() + " nav=" + first.getNavigation().isDone()
                    + " defending=" + first.defending() + " spar=" + first.spar() + " alarm=" + first.alarmGoal().mode(h.getLevel().getGameTime()) + " guard=" + first.guardTarget()
                    + " pos=" + first.position() + " tick=" + h.getTick() + " noAi=" + first.isNoAi() + " alive=" + first.isAlive());
        });
        Vec3 want = Vec3.atBottomCenterOf(two).add(0.0D, 9.0D / 16.0D, 0.0D);
        h.runAfterDelay(400, () -> {
            h.assertTrue(stoodUp[0] <= 1, "the late diner bounced on the seat: stood up " + stoodUp[0] + " times");
            h.assertTrue(late.sitting() && late.position().distanceTo(want) < 0.1D, "the late diner is not on the free chair: " + late.seatStyle() + " " + late.position()
                    + " doing=" + late.doingSpot() + " kind=" + late.doingKind() + " resolved=" + SectLife.resolve(late));
            h.succeed();
        });
    }
}
