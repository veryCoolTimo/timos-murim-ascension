package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.GateDoorBlock;
import io.github.verycooltimo.murim.decor.GateDoors;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Docs/design/38 section 5.8 on a small hand-made layout in the test yard: the layout file anchors, every role has a
 * bed, the schedule picks reachable and unshared spots, the graph router finds the way between two doors, a closed
 * big gate is opened and crossed, and a stuck walker recovers (re-path, step back, another spot, relocation unseen).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectLayoutAiGameTests {

    private static final String YARD = "sect_yard";

    private SectLayoutAiGameTests() {
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

    /** Collects the pieces of a mini file in absolute coordinates. */
    private static final class Build {
        final GameTestHelper h;
        final List<SectLayoutFile.Room> rooms = new ArrayList<>();
        final List<SectLayoutFile.Door> doors = new ArrayList<>();
        final List<SectLayoutFile.Bed> beds = new ArrayList<>();
        final List<SectLayoutFile.Spot> spots = new ArrayList<>();
        final List<SectLayoutFile.Waypoint> wps = new ArrayList<>();
        final List<SectLayoutFile.Edge> edges = new ArrayList<>();
        final Map<String, String> roles;

        Build(GameTestHelper h, Map<String, String> roles) {
            this.h = h;
            this.roles = roles;
        }

        BlockPos p(int x, int y, int z) {
            return h.absolutePos(new BlockPos(x, y, z));
        }

        Build wp(String id, int x, int z) {
            wps.add(new SectLayoutFile.Waypoint(id, p(x, 2, z), id.startsWith("door") ? "door_in" : "anchor", id, null));
            return this;
        }

        Build edge(String a, String b, int len, String... crosses) {
            edges.add(new SectLayoutFile.Edge(edges.size(), a, b, len, List.of(), List.of(crosses), false, false));
            return this;
        }

        Build seat(String id, String kind, int x, int z, String reach) {
            BlockPos at = p(x, 2, z);
            spots.add(new SectLayoutFile.Spot(id, kind, "sit", at, at.south(), null, 0.0F, null, "dining_hall", null, null,
                    List.of("all"), reach, -1, -1));
            return this;
        }

        SectLayoutFile file() {
            return new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), roles, List.of(), rooms, doors, List.of(), beds, spots,
                    wps, edges, List.of());
        }
    }

    private static SectDisciple npc(GameTestHelper h, SectLayout layout, String key, double x, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setLayout(layout);
        d.setKeepAwake(true);
        Vec3 p = h.absoluteVec(new Vec3(x, 2, z));
        d.moveTo(p.x, p.y, p.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static void dayTime(GameTestHelper h, int tickOfDay) {
        h.getLevel().setDayTime(Math.floorDiv(h.getLevel().getDayTime(), 24000L) * 24000L + tickOfDay);
    }

    // ------------------------------------------------------------------ 1: the file loads and anchors

    @GameTest(template = YARD, timeoutTicks = 20, batch = "sect_layout_ai_file")
    public static void layoutFileLoadsAndAnchorsToTheSite(GameTestHelper h) {
        SectLayoutFile shipped = SectLayoutFile.shipped();
        h.assertTrue(shipped != null && shipped.beds().size() >= 44 && shipped.rooms().size() > 100, "layout.json did not load");
        var f = shipped.frame();
        MountHuaSite moved = MountHuaSite.placement(f.centerX() + 300, f.centerZ() - 120, f.baseY() + 12, f.rotation());
        SectLayoutFile anchored = SectLayoutFile.anchored(moved);
        h.assertTrue(anchored != null, "anchored file missing");
        var a = anchored.bed("bed.01");
        var b = shipped.bed("bed.01");
        h.assertTrue(a.head().equals(b.head().offset(300, 12, -120)), "bed not moved with the site: " + a.head() + " vs " + b.head());
        // Only the mountain the sect was baked on has the built sect; an old world has none.
        h.assertTrue(SectLayout.hua(moved).file() == null, "an old world's Hua layout handed out the baked sect");
        MountHuaSite baked = new MountHuaSite(f.centerX() + 300, f.centerZ() - 120, f.baseY() + 12, f.rotation(), 1L, true);
        h.assertTrue(SectLayout.hua(baked).file() == anchored, "the Hua layout does not hand out the anchored file");
        SectLayoutFile back = SectLayoutFile.anchored(MountHuaSite.placement(f.centerX(), f.centerZ(), f.baseY(), f.rotation()));
        h.assertTrue(back.bed("bed.01").head().equals(b.head()), "identity anchor moved the bed");
        h.succeed();
    }

    /** Real SectPlaces data, with its training square moved into the loaded yard; no brazier fixture.
     * API: reference/minecraft-src/net/minecraft/gametest/framework/GameTestHelper.java#getLevel, #assertTrue;
     * reference/minecraft-src/net/minecraft/server/level/ServerLevel.java#setDayTime.
     */
    @GameTest(template = YARD, timeoutTicks = 40, batch = "sect_baked_night_watch")
    public static void bakedLayoutKeepsExactlyOneJuniorOnWatchAt15000(GameTestHelper h) {
        var shipped = SectLayoutFile.shipped();
        var frame = shipped.frame();
        var square = shipped.zone("training_square");
        BlockPos origin = h.absolutePos(new BlockPos(12, 2, 12));
        int dx = origin.getX() - (square.rect().x0() + square.rect().x1()) / 2;
        int dz = origin.getZ() - (square.rect().z0() + square.rect().z1()) / 2;
        var site = new MountHuaSite(frame.centerX() + dx, frame.centerZ() + dz,
                frame.baseY() + origin.getY() - square.y(), frame.rotation(), 1L, true);
        SectLayout layout = SectLayout.hua(site);
        var area = layout.file().zone("training_square");
        long oldTime = h.getLevel().getDayTime();
        try {
            h.getLevel().setDayTime(15000);
            int watches = 0;
            for (SectRoster junior : SectRoster.generation(3)) {
                SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
                d.setMember(junior);
                d.setLayout(layout);
                d.setRainOverride(false);
                d.moveTo(origin.getX() + .5D, origin.getY(), origin.getZ() + .5D, 0, 0);
                var routine = SectLife.resolve(d);
                h.assertTrue(routine != null, junior.key() + " has no baked-layout routine");
                if (routine.task().kind() != SectSchedule.Kind.WATCH) continue;
                watches++;
                h.assertTrue(junior.key().equals(SectNightWatch.member(15000)), "wrong junior on WATCH");
                h.assertTrue(area.rect().contains((int) Math.floor(routine.spot().x), (int) Math.floor(routine.spot().z)), "watch resolved outside baked square");
                // Exercise the real assignment path, rather than setting doingKind as the old fixture did.
                new io.github.verycooltimo.murim.entity.ScheduleGoal(d).tick();
                h.assertTrue(d.doingKind() == SectSchedule.Kind.WATCH, "sleep replaced WATCH");
                var post = SectNightWatch.post(d, routine.spot());
                h.assertTrue(area.rect().contains((int) Math.floor(post.x), (int) Math.floor(post.z)), "no-brazier post left the training area");
            }
            h.assertTrue(watches == 1, "expected exactly one junior on WATCH, got " + watches);
        } finally {
            h.getLevel().setDayTime(oldTime);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ 2: own beds

    @GameTest(template = YARD, timeoutTicks = 700, batch = "sect_layout_ai_beds")
    public static void everyoneLiesInTheirOwnBedAndStaleClaimsAreTakenOver(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.NIGHT);
        Build b = new Build(h, Map.of("yul_jin", "disciple_3rd", "yul_pyo", "disciple_3rd"));
        int i = 0;
        for (String key : new String[] {"yul_jin", "yul_pyo"}) {
            int z = 8 + 6 * i++;
            var bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
            h.setBlock(new BlockPos(8, 2, z), bed.setValue(BedBlock.PART, BedPart.FOOT));
            h.setBlock(new BlockPos(9, 2, z), bed.setValue(BedBlock.PART, BedPart.HEAD));
            b.beds.add(new SectLayoutFile.Bed("bed." + key, b.p(9, 2, z), b.p(10, 2, z), -90.0F, null, "dorm_3rd", "disciple_3rd", key, i, "ok"));
        }
        var layout = new Mini(h.absoluteVec(new Vec3(12.5, 2, 12.5)), b.file());
        // the old nearest-free-bed scan had given yul_jin's bed to yul_pyo
        SectRooms.claim(h.getLevel(), b.p(9, 2, 8), "npc:yul_pyo");
        SectDisciple a = npc(h, layout, "yul_jin", 14.5, 8.5);
        SectDisciple c = npc(h, layout, "yul_pyo", 14.5, 14.5);
        h.succeedWhen(() -> {
            h.assertTrue(a.isSleeping() && c.isSleeping(), "not both asleep: " + a.isSleeping() + " " + c.isSleeping() + " a=" + a.position()
                    + " bed=" + SectLife.findBed(a, "x") + " resolved=" + SectLife.resolve(a) + " dormant=" + a.dormant() + " nav=" + a.getNavigation().isDone());
            h.assertTrue(a.getSleepingPos().orElseThrow().equals(b.p(9, 2, 8)), "yul_jin lies in a stranger's bed");
            h.assertTrue(c.getSleepingPos().orElseThrow().equals(b.p(9, 2, 14)), "yul_pyo lies in a stranger's bed");
        });
    }

    // ------------------------------------------------------------------ 3: reachable, unshared spots

    @GameTest(template = YARD, timeoutTicks = 40, batch = "sect_layout_ai_spots")
    public static void scheduleSkipsUnreachableSpotsAndNeverSharesOne(GameTestHelper h) {
        dayTime(h, 1050);
        Build b = new Build(h, Map.of());
        b.seat("spot.cut", "seat_bench", 4, 4, "one_way_drop").seat("spot.a", "seat_bench", 6, 4, "ok").seat("spot.b", "seat_bench", 8, 4, "ok");
        var layout = new Mini(h.absoluteVec(new Vec3(12.5, 2, 12.5)), b.file());
        SectDisciple one = npc(h, layout, "gyeong_pil", 12.5, 12.5);
        SectDisciple two = npc(h, layout, "gyeong_on", 12.5, 14.5);
        SectDisciple three = npc(h, layout, "gyeong_dan", 12.5, 16.5);
        // Docs/design/38 §5.5: claims belong to the level, not this miniature layout.
        // Earlier batches may reserve these diners' seats in another layout until the meal ends.
        var claims = SectSpots.of(h.getLevel());
        var diners = List.of("gyeong_pil", "gyeong_on", "gyeong_dan");
        diners.forEach(claims::release);
        try {
            var r1 = SectLife.resolve(one);
            var r2 = SectLife.resolve(two);
            var r3 = SectLife.resolve(three);
            h.assertTrue(r1 != null && r2 != null, "no places after clearing prior meal reservations: one=" + r1 + " two=" + r2);
            h.assertTrue(!"spot.cut".equals(r1.spotId()) && !"spot.cut".equals(r2.spotId()), "an unreachable spot was chosen");
            h.assertTrue(!r1.spotId().equals(r2.spotId()), "two people on one spot");
            h.assertTrue(r3 == null || r3.spotId() == null || !r3.spotId().equals(r1.spotId()) && !r3.spotId().equals(r2.spotId())
                    && !"spot.cut".equals(r3.spotId()), "third diner shares a spot");
            h.assertTrue(SectLife.resolve(one).spotId().equals(r1.spotId()), "the claim is not sticky");
        } finally {
            diners.forEach(claims::release);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ 4: A* between two doors, walking through one

    private static Build doorWorld(GameTestHelper h) {
        for (int z = 1; z <= 23; z++) {
            for (int y = 2; y <= 4; y++) {
                h.setBlock(new BlockPos(12, y, z), Blocks.OAK_PLANKS);
            }
        }
        var door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        h.setBlock(new BlockPos(12, 2, 12), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        h.setBlock(new BlockPos(12, 3, 12), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        Build b = new Build(h, Map.of());
        b.wp("anchor.west", 4, 12).wp("door.1.a", 11, 12).wp("door.1.b", 13, 12).wp("anchor.east", 20, 12);
        b.doors.add(new SectLayoutFile.Door("door.1", "hall_door", "wooden_door", b.p(12, 2, 12), b.p(12, 2, 12),
                List.of(b.p(11, 2, 12)), List.of(b.p(13, 2, 12)), null, null, 1, "ok"));
        b.edge("anchor.west", "door.1.a", 7).edge("door.1.a", "door.1.b", 2, "door.1").edge("door.1.b", "anchor.east", 7);
        return b;
    }

    @GameTest(template = YARD, timeoutTicks = 700, batch = "sect_layout_ai_route")
    public static void routerFindsThePathBetweenTwoDoorsAndTheWalkerFollowsIt(GameTestHelper h) {
        Build b = doorWorld(h);
        SectLayoutFile file = b.file();
        var plan = SectRouter.plan(file, b.p(4, 2, 12), b.p(20, 2, 12), null, SectWalking.clear(h.getLevel()));
        h.assertTrue(plan != null && plan.edges().stream().anyMatch(e -> e.crosses().contains("door.1")), "the route does not go through the door: " + plan);
        h.assertTrue(plan.legs().stream().noneMatch(l -> l.type == SectRouter.Type.OPEN), "a wooden door became a gate leg");
        h.assertTrue(file.route("door.1.a", "door.1.b").nodes().size() == 2, "A* between the door sides failed");
        var layout = new Mini(h.absoluteVec(new Vec3(12.5, 2, 12.5)), file);
        SectDisciple d = npc(h, layout, "cook_kim", 4.5, 12.5);
        d.setNoAi(false);
        Vec3 goal = h.absoluteVec(new Vec3(20.5, 2, 12.5));
        Vec3[] previous = {d.position()};
        h.onEachTick(() -> {
            h.assertTrue(d.position().distanceTo(previous[0]) < 1.6D, "NPC jumped: " + previous[0] + " -> " + d.position());
            previous[0] = d.position();
            d.walking().walk(goal, 0.4D);
        });
        h.succeedWhen(() -> h.assertTrue(d.position().distanceTo(goal) < 1.5D, "walker did not arrive: " + d.position()));
    }

    // ------------------------------------------------------------------ 5: the closed big gate

    @GameTest(template = YARD, timeoutTicks = 900, batch = "sect_layout_ai_gate")
    public static void closedBigGateIsOpenedAndCrossed(GameTestHelper h) {
        for (int x = 1; x <= 22; x++) {
            for (int y = 2; y <= 5; y++) {
                if (x < 5 || x > 8) {
                    h.setBlock(new BlockPos(x, y, 6), Blocks.OAK_PLANKS);
                }
            }
        }
        BlockPos click = h.absolutePos(new BlockPos(6, 2, 6));
        ServerLevel level = h.getLevel();
        var placed = GateDoors.GATE_DOOR_ITEM.get().place(new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND,
                new ItemStack(GateDoors.GATE_DOOR_ITEM.get()), new BlockHitResult(Vec3.atCenterOf(click), Direction.SOUTH, click, false)));
        h.assertTrue(placed.consumesAction(), "gate not placed");
        BlockPos origin = h.absolutePos(new BlockPos(5, 2, 6));
        h.assertTrue(!level.getBlockState(origin).getValue(GateDoorBlock.OPEN), "gate is not closed");
        Build b = new Build(h, Map.of());
        b.wp("anchor.south", 6, 10).wp("door.g.a", 6, 7).wp("door.g.b", 6, 5).wp("anchor.north", 6, 2);
        b.doors.add(new SectLayoutFile.Door("door.g", "gate_door", "gate_toggle", b.p(5, 2, 6), b.p(8, 5, 6),
                List.of(b.p(5, 2, 7), b.p(6, 2, 7), b.p(7, 2, 7), b.p(8, 2, 7)),
                List.of(b.p(5, 2, 5), b.p(6, 2, 5), b.p(7, 2, 5), b.p(8, 2, 5)), null, null, 4, "ok"));
        b.edge("anchor.south", "door.g.a", 3).edge("door.g.a", "door.g.b", 2, "door.g").edge("door.g.b", "anchor.north", 3);
        var plan = SectRouter.plan(b.file(), b.p(6, 2, 10), b.p(6, 2, 2), null, SectWalking.clear(level));
        h.assertTrue(plan != null && plan.legs().stream().anyMatch(l -> l.type == SectRouter.Type.OPEN && "door.g".equals(l.door)),
                "the plan has no explicit gate crossing");
        var layout = new Mini(h.absoluteVec(new Vec3(12.5, 2, 12.5)), b.file());
        SectDisciple d = npc(h, layout, "cook_kim", 6.5, 10.5);
        Vec3 goal = h.absoluteVec(new Vec3(6.5, 2, 2.5));
        boolean[] wasOpen = {false};
        h.onEachTick(() -> {
            wasOpen[0] |= level.getBlockState(origin).getValue(GateDoorBlock.OPEN);
            d.walking().walk(goal, 0.4D);
        });
        h.succeedWhen(() -> {
            h.assertTrue(wasOpen[0], "the gate was never opened");
            h.assertTrue(d.position().distanceTo(goal) < 1.5D, "walker did not get through the gate: " + d.position());
        });
    }

    /**
     * A gate that never opens is a stuck walk like any other (review 18.10): the walker gave the gate fresh time on every
     * retry, so the stuck monitor was held off for good and he stood at it for ever.
     */
    @GameTest(template = YARD, timeoutTicks = 900, batch = "sect_layout_ai_gate_stuck")
    public static void aGateThatNeverOpensCountsAsStuck(GameTestHelper h) {
        for (int x = 1; x <= 22; x++) {
            for (int y = 2; y <= 5; y++) {
                if (x < 5 || x > 8) {
                    h.setBlock(new BlockPos(x, y, 6), Blocks.OAK_PLANKS);
                }
            }
        }
        BlockPos click = h.absolutePos(new BlockPos(6, 2, 6));
        ServerLevel level = h.getLevel();
        var placed = GateDoors.GATE_DOOR_ITEM.get().place(new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND,
                new ItemStack(GateDoors.GATE_DOOR_ITEM.get()), new BlockHitResult(Vec3.atCenterOf(click), Direction.SOUTH, click, false)));
        h.assertTrue(placed.consumesAction(), "gate not placed");
        BlockPos origin = h.absolutePos(new BlockPos(5, 2, 6));
        // A gate that is swinging for ever cannot be started again: every attempt to open it fails.
        try {
            java.lang.reflect.Field busy = io.github.verycooltimo.murim.decor.GateDoorBlockEntity.class.getDeclaredField("busy");
            busy.setAccessible(true);
            busy.setInt(level.getBlockEntity(origin), Integer.MAX_VALUE);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        Build b = new Build(h, Map.of());
        b.wp("anchor.south", 6, 10).wp("door.g.a", 6, 7).wp("door.g.b", 6, 5).wp("anchor.north", 6, 2);
        b.doors.add(new SectLayoutFile.Door("door.g", "gate_door", "gate_toggle", b.p(5, 2, 6), b.p(8, 5, 6),
                List.of(b.p(5, 2, 7), b.p(6, 2, 7), b.p(7, 2, 7), b.p(8, 2, 7)),
                List.of(b.p(5, 2, 5), b.p(6, 2, 5), b.p(7, 2, 5), b.p(8, 2, 5)), null, null, 4, "ok"));
        b.edge("anchor.south", "door.g.a", 3).edge("door.g.a", "door.g.b", 2, "door.g").edge("door.g.b", "anchor.north", 3);
        var layout = new Mini(h.absoluteVec(new Vec3(12.5, 2, 12.5)), b.file());
        SectDisciple d = npc(h, layout, "cook_kim", 6.5, 10.5);
        Vec3 goal = h.absoluteVec(new Vec3(6.5, 2, 2.5));
        h.onEachTick(() -> d.walking().walk(goal, 0.4D));
        h.succeedWhen(() -> {
            h.assertTrue(d.walking().stuckStage() >= 1, "a gate that never opens did not count as stuck (stage " + d.walking().stuckStage()
                    + ", at " + d.position() + ")");
            h.assertTrue(d.position().distanceTo(goal) > 3.0D, "he got through a gate that cannot open");
        });
    }

    /** Closing the gate by hand must survive a disciple walking beside it on the same side. */
    @GameTest(template = YARD, timeoutTicks = 160, batch = "sect_2510_gate_intent")
    public static void playerClosedGateStaysClosedBesideParallelWalker(GameTestHelper h) {
        BlockPos click = h.absolutePos(new BlockPos(6, 2, 6));
        var level = h.getLevel();
        var placed = GateDoors.GATE_DOOR_ITEM.get().place(new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND,
                new ItemStack(GateDoors.GATE_DOOR_ITEM.get()), new BlockHitResult(Vec3.atCenterOf(click), Direction.SOUTH, click, false)));
        h.assertTrue(placed.consumesAction(), "gate not placed");
        BlockPos origin = h.absolutePos(new BlockPos(5, 2, 6));
        var gate = (GateDoorBlock) level.getBlockState(origin).getBlock();
        gate.setOpen(level, origin, true);
        h.assertTrue(gate.toggle(level, origin, h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL)),
                "player could not start closing the gate");
        Build b = new Build(h, Map.of());
        var layout = new Mini(h.absoluteVec(new Vec3(12.5, 2, 12.5)), b.file());
        SectDisciple d = npc(h, layout, "cook_kim", 6.5, 7.5);
        Vec3 along = h.absoluteVec(new Vec3(10.5, 2, 7.5));
        h.onEachTick(() -> {
            d.walking().walk(along, 0.4D);
            h.assertFalse(level.getBlockState(origin).getValue(GateDoorBlock.OPEN), "disciple reopened a gate he did not need to cross");
            h.assertFalse(h.getTick() > 40 && level.getBlockEntity(origin) instanceof io.github.verycooltimo.murim.decor.GateDoorBlockEntity be && be.busy(),
                    "disciple started opening a gate beside his path");
        });
        h.runAfterDelay(100, h::succeed);
    }

    // ------------------------------------------------------------------ 6: stuck recovery

    @GameTest(template = YARD, timeoutTicks = 1100, batch = "sect_layout_ai_stuck")
    public static void stuckWalkerRepathsStepsBackBansTheSpotAndIsMovedUnseen(GameTestHelper h) {
        // a closed box around the walker: pathfinding, re-path and sidesteps cannot get him out
        for (int x = 17; x <= 19; x++) {
            for (int z = 17; z <= 19; z++) {
                for (int y = 2; y <= 5; y++) {
                    if (!(x == 18 && z == 18 && y <= 4)) {
                        h.setBlock(new BlockPos(x, y, z), Blocks.OBSIDIAN);
                    }
                }
            }
        }
        Build b = new Build(h, Map.of());
        b.wp("anchor.out", 14, 18).wp("anchor.goal", 8, 18);
        b.edge("anchor.out", "anchor.goal", 6);
        var layout = new Mini(h.absoluteVec(new Vec3(12.5, 2, 12.5)), b.file());
        SectDisciple d = npc(h, layout, "cook_kim", 18.5, 18.5);
        Vec3 goal = h.absoluteVec(new Vec3(8.5, 2, 18.5));
        BlockPos inside = h.absolutePos(new BlockPos(18, 2, 18));
        int[] maxStage = {0};
        long[] jumpAt = {-1L};
        boolean[] banned = {false};
        Vec3[] previous = {d.position()};
        long[] ticks = {0L};
        h.onEachTick(() -> {
            ticks[0]++;
            d.walking().walk(goal, 0.4D);
            maxStage[0] = Math.max(maxStage[0], d.walking().stuckStage());
            if (d.walking().takeSpotBan()) {
                banned[0] = true;
            }
            if (d.position().distanceTo(previous[0]) > 3.0D) {
                h.assertTrue(jumpAt[0] < 0, "relocated more than once");
                h.assertTrue(banned[0], "relocated before the other spot was tried");
                h.assertTrue(ticks[0] >= SectWalking.STUCK_RELOCATE, "relocated after only " + ticks[0] + " ticks");
                jumpAt[0] = ticks[0];
            } else {
                h.assertTrue(jumpAt[0] >= 0 || d.blockPosition().distManhattan(inside) <= 3, "left the box without being moved");
            }
            previous[0] = d.position();
        });
        h.succeedWhen(() -> {
            h.assertTrue(banned[0], "the walker never gave up on its spot (stage reached " + maxStage[0] + ")");
            h.assertTrue(jumpAt[0] >= 0, "never moved to a waypoint");
            h.assertTrue(d.position().distanceTo(goal) < 1.5D, "did not walk on after the move: " + d.position());
        });
    }

    // ------------------------------------------------------------------ 7: dialogue names the place

    @GameTest(template = YARD, timeoutTicks = 40, batch = "sect_layout_ai_place")
    public static void dialogueNamesThePlaceWhereTheMemberStands(GameTestHelper h) {
        Build b = new Build(h, Map.of());
        b.rooms.add(new SectLayoutFile.Room("main_hall.f0.r1", "main_hall", 0, b.p(0, 2, 0).getY(),
                new SectLayoutFile.Rect(b.p(3, 2, 3).getX(), b.p(3, 2, 3).getZ(), b.p(9, 2, 9).getX(), b.p(9, 2, 9).getZ()),
                "audience_hall", "ok", List.of(), List.of(), 40));
        b.rooms.add(new SectLayoutFile.Room("ancestors.f0.r1", "ancestors", 0, b.p(0, 2, 0).getY(),
                new SectLayoutFile.Rect(b.p(13, 2, 3).getX(), b.p(13, 2, 3).getZ(), b.p(19, 2, 9).getX(), b.p(19, 2, 9).getZ()),
                "shrine", "ok", List.of(), List.of(), 40));
        var layout = new Mini(h.absoluteVec(new Vec3(12.5, 2, 12.5)), b.file());
        SectDisciple leader = npc(h, layout, "tae_hwi", 6.5, 6.5);
        leader.setNoAi(true);
        h.assertTrue(whereKey(h, "tae_hwi").equals("murim.sect.place.audience_hall"), "by day in the hall: " + whereKey(h, "tae_hwi"));
        Vec3 shrine = h.absoluteVec(new Vec3(16.5, 2, 6.5));
        leader.moveTo(shrine.x, shrine.y, shrine.z, 0, 0);
        h.assertTrue(whereKey(h, "tae_hwi").equals("murim.sect.place.shrine"), "in the evening at the shrine: " + whereKey(h, "tae_hwi"));
        Vec3 outside = h.absoluteVec(new Vec3(22.5, 2, 20.5));
        leader.moveTo(outside.x, outside.y, outside.z, 0, 0);
        h.assertTrue(whereKey(h, "tae_hwi").equals("murim.sect.place.unknown"), "outside every room: " + whereKey(h, "tae_hwi"));
        // The search is a box round the asker, not the world: somebody asking from far away (and a world-sized box would
        // be a scan of 3.75 million columns) is told nothing.
        leader.moveTo(shrine.x, shrine.y, shrine.z, 0, 0);
        h.assertTrue(whereKey(h, "tae_hwi").equals("murim.sect.place.shrine"), "back at the shrine: " + whereKey(h, "tae_hwi"));
        BlockPos far = h.absolutePos(BlockPos.ZERO).offset((int) SectLife.WHERE_RANGE + 200, 0, 0);
        h.assertTrue(((net.minecraft.network.chat.contents.TranslatableContents) SectLife.whereIs(h.getLevel(), "tae_hwi", far).getContents())
                .getKey().equals("murim.sect.place.unknown"), "a member beyond the search range was named");
        h.succeed();
    }

    private static String whereKey(GameTestHelper h, String key) {
        return ((net.minecraft.network.chat.contents.TranslatableContents) SectLife.whereIs(h.getLevel(), key, h.absolutePos(BlockPos.ZERO)).getContents()).getKey();
    }

    // ------------------------------------------------------------------ office work is done sitting (author 17.10)

    /** An elder at his desk sits on the seat beside it, facing the desk, and stands when the period ends. */
    @GameTest(template = YARD, timeoutTicks = 700, batch = "sect_layout_ai_sit")
    public static void deskWorkIsDoneSittingAndEndsStanding(GameTestHelper h) {
        dayTime(h, 5400);
        Build b = new Build(h, Map.of());
        BlockPos desk = b.p(12, 2, 12);
        BlockPos chair = b.p(12, 2, 14);
        h.setBlock(new BlockPos(12, 2, 14), Blocks.OAK_SLAB);
        b.rooms.add(new SectLayoutFile.Room("scriptures.f0.r1", "scriptures", 0, b.p(0, 2, 0).getY(),
                new SectLayoutFile.Rect(b.p(8, 2, 8).getX(), b.p(8, 2, 8).getZ(), b.p(16, 2, 16).getX(), b.p(16, 2, 16).getZ()),
                "library", "ok", List.of(), List.of(), 40));
        b.spots.add(new SectLayoutFile.Spot("spot.desk", "work_desk", "stand", desk, null, desk, 0.0F, "scriptures.f0.r1", "scriptures",
                null, null, List.of("elder"), "ok", -1, -1));
        b.spots.add(new SectLayoutFile.Spot("spot.chair", "seat_chair", "sit", chair, chair, null, 180.0F, "scriptures.f0.r1", "scriptures",
                null, null, List.of("elder"), "ok", -1, -1));
        var layout = new Mini(h.absoluteVec(new Vec3(12.5, 2, 12.5)), b.file());
        SectDisciple d = npc(h, layout, "tae_rok", 6.5, 14.5);
        h.succeedWhen(() -> {
            h.assertTrue(d.sitting(), "the elder does not sit at his desk: " + d.position() + " " + SectLife.resolve(d));
            Vec3 seat = Vec3.atBottomCenterOf(chair).add(0, 0.5, 0);
            h.assertTrue(d.position().distanceTo(seat) < 0.7D, "not on the seat: " + d.position() + " seat " + seat);
            float want = SectLayout.yawOf(desk.getX() - chair.getX(), desk.getZ() - chair.getZ());
            h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(d.getYRot() - want)) < 40.0F, "not facing the desk: " + d.getYRot() + " vs " + want);
        });
    }

    /** Standing up when the working period is over. */
    // Own batch: both sit tests spawn tae_rok, and two people with one key in a batch make SectLife#duplicate remove one
    // of them when its check (every 200 ticks, offset by the entity id) falls inside the test.
    @GameTest(template = YARD, timeoutTicks = 900, batch = "sect_layout_ai_sit_leaves")
    public static void leavesTheSeatWhenThePeriodEnds(GameTestHelper h) {
        dayTime(h, 5400);
        Build b = new Build(h, Map.of());
        BlockPos desk = b.p(12, 2, 12);
        BlockPos chair = b.p(12, 2, 14);
        h.setBlock(new BlockPos(12, 2, 14), Blocks.OAK_SLAB);
        b.rooms.add(new SectLayoutFile.Room("scriptures.f0.r1", "scriptures", 0, b.p(0, 2, 0).getY(),
                new SectLayoutFile.Rect(b.p(8, 2, 8).getX(), b.p(8, 2, 8).getZ(), b.p(16, 2, 16).getX(), b.p(16, 2, 16).getZ()),
                "library", "ok", List.of(), List.of(), 40));
        b.spots.add(new SectLayoutFile.Spot("spot.desk", "work_desk", "stand", desk, null, desk, 0.0F, "scriptures.f0.r1", "scriptures",
                null, null, List.of("elder"), "ok", -1, -1));
        b.spots.add(new SectLayoutFile.Spot("spot.chair", "seat_chair", "sit", chair, chair, null, 180.0F, "scriptures.f0.r1", "scriptures",
                null, null, List.of("elder"), "ok", -1, -1));
        var layout = new Mini(h.absoluteVec(new Vec3(12.5, 2, 12.5)), b.file());
        SectDisciple d = npc(h, layout, "tae_rok", 6.5, 14.5);
        boolean[] sat = {false};
        h.onEachTick(() -> {
            if (d.sitting()) {
                sat[0] = true;
            }
            if (sat[0] && h.getTick() == 300) {
                dayTime(h, 12000);   // evening: tae_rok leaves his desk
            }
        });
        h.succeedWhen(() -> h.assertTrue(sat[0] && h.getTick() > 300 && !d.sitting(), "still sitting after the period ended: sat=" + sat[0] + " tick=" + h.getTick() + " time=" + h.getLevel().getDayTime() + " res=" + SectLife.resolve(d) + " dormant=" + d.dormant()));
    }
}
