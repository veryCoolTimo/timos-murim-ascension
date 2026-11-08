package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Routing and timing of the sect AI (docs/adr/0017, outside playtest 19.10): the walker takes the stair the plan names, a person
 * is at the table when the meal begins, and a walk takes what the lead time estimates.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectRoutingGameTests {

    private static final String YARD = "sect_yard";

    private SectRoutingGameTests() {
    }

    private record Mini(SectLayoutFile file) implements SectLayout {
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

    private static SectDisciple npc(GameTestHelper h, SectLayout layout, String key, double x, double y, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setLayout(layout);
        d.setKeepAwake(true);
        d.setNoAi(false);
        Vec3 p = h.absoluteVec(new Vec3(x, y, z));
        d.moveTo(p.x, p.y, p.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static void clearYard(GameTestHelper h) {
        for (int x = 0; x < 24; x++) {
            for (int z = 0; z < 24; z++) {
                for (int y = 2; y <= 8; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_meal_seat")
    public static void aSeatedDinerResistsPlayerPushAndDismounts(GameTestHelper h) {
        clearYard(h);
        BlockPos cell = h.absolutePos(new BlockPos(12, 2, 12));
        h.getLevel().setBlock(cell, io.github.verycooltimo.murim.decor.IntSeating.BENCH.get().defaultBlockState(), 3);
        SectLayoutFile empty = new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        SectDisciple d = npc(h, new Mini(empty), "gyeong_ha", 12.5, 2, 13.5);
        d.setNoAi(true);
        d.sit(io.github.verycooltimo.murim.entity.SeatStyle.CHAIR, SectLife.seatTop(h.getLevel(), cell), 0, d.position());
        var player = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        boolean[] pushing = {false};
        // Registered up front: GameTestInfo cannot take new tick hooks while it iterates its scheduled ones.
        // Applies the collision impulse a nearby player supplies, repeatedly, over real entity ticks.
        h.onEachTick(() -> {
            if (pushing[0] && d.sitting()) d.push(d.getX() - player.getX(), 0, d.getZ() - player.getZ());
        });
        h.runAfterDelay(30, () -> {
            h.assertTrue(d.getVehicle() instanceof io.github.verycooltimo.murim.decor.SeatEntity, "diner only plays a seated pose");
            Vec3 anchor = d.position();
            player.setPos(anchor.add(0.25D, 0, 0));
            pushing[0] = true;
            h.runAfterDelay(20, () -> {
                h.assertTrue(d.position().distanceToSqr(anchor) < 0.0001D, "player slid the diner along the bench");
                h.assertTrue(d.isPassenger(), "push unseated the diner");
                d.standUp();
                h.assertTrue(!d.isPassenger(), "diner cannot leave his seat");
                h.succeed();
            });
        });
    }

    @GameTest(template = YARD, batch = "sect_meal_wall")
    public static void dinersClaimWallSeatsBeforeAisleSeats(GameTestHelper h) {
        BlockPos wall = h.absolutePos(new BlockPos(2, 2, 10));
        BlockPos aisle = h.absolutePos(new BlockPos(12, 2, 10));
        var room = new SectLayoutFile.Room("dining", "dining_hall", 0, wall.getY(),
                new SectLayoutFile.Rect(wall.getX() - 1, wall.getZ() - 9, aisle.getX() + 10, wall.getZ() + 10),
                "dining", "ok", List.of(), List.of(), 400);
        List<SectLayoutFile.Spot> seats = new ArrayList<>();
        for (BlockPos at : List.of(aisle, wall)) seats.add(new SectLayoutFile.Spot(at.equals(wall) ? "wall" : "aisle", "seat_bench",
                "sit", at, at.south(), null, 0, "dining", "dining_hall", null, null, List.of("all"), "ok", -1, -1));
        SectLayoutFile file = new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), List.of(room), List.of(),
                List.of(), List.of(), seats, List.of(), List.of(), List.of());
        SectSpots claims = new SectSpots();
        var task = new SectSchedule.Task(SectSchedule.Kind.EAT, "dining", 0, 0, 0, 0);
        var first = SectPlaces.pick(file, SectRoster.of("gyeong_ha").orElseThrow(), task, 1000, claims, 0, Map.of());
        h.assertTrue(first != null && "wall".equals(first.spotId()), "aisle chosen before wall");
        var second = SectPlaces.pick(file, SectRoster.of("gyeong_on").orElseThrow(), task, 1000, claims, 0, Map.of());
        h.assertTrue(second != null && "aisle".equals(second.spotId()), "occupied wall seat shared");
        h.succeed();
    }

    /** Controlled cohort checks actual navigation and the 95% gate, separately from the full-mountain playtest. */
    @GameTest(template = YARD, timeoutTicks = 2400, batch = "sect_meal_arrivals")
    public static void atLeast95PercentOfDinersArriveByTheirBell(GameTestHelper h) {
        clearYard(h);
        List<SectRoster> members = SectRoster.ALL.stream().filter(m -> m.role() == SectRole.FIRST || m.role() == SectRole.SECOND || m.role() == SectRole.DISCIPLE)
                .filter(m -> SectSchedule.task(m, 9000L, false).kind() == SectSchedule.Kind.EAT).limit(20).toList();
        List<SectDisciple> diners = new ArrayList<>();
        boolean[] arrived = new boolean[members.size()];
        boolean[] checked = new boolean[members.size()];
        for (int i = 0; i < members.size(); i++) {
            int z = i + 2;
            BlockPos cell = h.absolutePos(new BlockPos(20, 2, z));
            h.getLevel().setBlock(cell, io.github.verycooltimo.murim.decor.IntSeating.STOOL.get().defaultBlockState(), 3);
            var seat = new SectLayoutFile.Spot("cohort." + i, "seat_stool", "sit", cell, cell.west(), null, 90,
                    null, "dining_hall", null, null, List.of("all"), "ok", -1, -1);
            SectLayoutFile file = new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(seat), List.of(), List.of(), List.of());
            diners.add(npc(h, new Mini(file), members.get(i).key(), 2.5, 2, z + 0.5));
        }
        long base = 0L;
        long[] clock = {8000};
        h.onEachTick(() -> {
            h.getLevel().setDayTime(base + clock[0]++);
            for (int i = 0; i < diners.size(); i++) {
                int bell = SectPlaces.secondWave(members.get(i)) ? 10000 : 9000;
                if (!checked[i] && clock[0] >= bell + SectLead.JITTER) {
                    checked[i] = true;
                    arrived[i] = diners.get(i).getVehicle() instanceof io.github.verycooltimo.murim.decor.SeatEntity;
                }
            }
        });
        h.runAfterDelay(2250, () -> {
            int onTime = 0;
            for (boolean yes : arrived) if (yes) onTime++;
            h.assertTrue(diners.size() == 20 && onTime * 100 >= diners.size() * 95,
                    "arrival rate below 95%: " + onTime + "/" + diners.size());
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ 1: the walking speed the lead time plans with

    private static void walkSpeed(GameTestHelper h, double factor) {
        clearYard(h);
        SectLayoutFile empty = new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        SectDisciple d = npc(h, new Mini(empty), "gyeong_ha", 2.5, 2, 12.5);
        Vec3 goal = h.absoluteVec(new Vec3(18.5, 2, 12.5));
        long[] start = {-1L};
        h.onEachTick(() -> {
            if (start[0] < 0) {
                start[0] = h.getTick();
            }
            d.walking().walk(goal, factor);
        });
        h.succeedWhen(() -> {
            h.assertTrue(d.position().distanceTo(goal) < 1.6D, "not there yet: " + d.position());
            long ticks = h.getTick() - start[0];
            double pace = SectStagger.factor(d.memberKey(), "pace", 0.12D);
            double expected = 16.0D * SectLead.TICKS_PER_CELL_FULL / (factor * pace);
            MurimMod.LOGGER.info("Sect-speed: factor={} ticks={} expected={} per cell {}", factor, ticks, Math.round(expected), ticks / 16.0D);
            h.assertTrue(ticks > expected * 0.75D && ticks < expected * 1.3D,
                    "16 cells at factor " + factor + " took " + ticks + " ticks, the lead time plans " + Math.round(expected));
        });
    }

    @GameTest(template = YARD, timeoutTicks = 600, batch = "sect_routing_speed_stroll")
    public static void aStrollingWalkTakesWhatTheLeadTimePlans(GameTestHelper h) {
        walkSpeed(h, 0.6D);
    }

    @GameTest(template = YARD, timeoutTicks = 600, batch = "sect_routing_speed_brisk")
    public static void aBriskWalkTakesWhatTheLeadTimePlans(GameTestHelper h) {
        walkSpeed(h, SectLead.TRANSIT);
    }

    // ------------------------------------------------------------------ 2: the walker climbs the stair the plan names

    /**
     * A loft over the east half (floor blocks at y 2, so people stand at 3) reached by the stair at z 4, the way the graph names; a
     * second stair at z 20 leads up to a ledge the loft does not join (a wall across it): the stair that does not lead where the person
     * goes. He starts nearer to the wrong one.
     */
    @GameTest(template = YARD, timeoutTicks = 900, batch = "sect_routing_stairs")
    public static void theWalkerClimbsTheStairThePlanNamesNotTheNearerOne(GameTestHelper h) {
        clearYard(h);
        for (int x = 14; x <= 22; x++) {
            for (int z = 1; z <= 22; z++) {
                h.setBlock(new BlockPos(x, 2, z), Blocks.OAK_PLANKS);
            }
        }
        for (int x = 14; x <= 22; x++) {
            for (int y = 3; y <= 5; y++) {
                h.setBlock(new BlockPos(x, y, 15), Blocks.OAK_PLANKS);
            }
        }
        BlockState up = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
        h.setBlock(new BlockPos(13, 2, 4), up);
        h.setBlock(new BlockPos(13, 2, 20), up);
        SectSpotsFixture fixture = new SectSpotsFixture(h);
        SectDisciple d = npc(h, new Mini(fixture.file()), "cook_kim", 6.5, 2, 19.5);
        Vec3 goal = h.absoluteVec(new Vec3(19.5, 3, 5.5));
        List<Vec3> trail = new ArrayList<>();
        h.onEachTick(() -> {
            trail.add(d.position());
            d.walking().walk(goal, 0.9D);
        });
        h.succeedWhen(() -> {
            h.assertTrue(d.position().distanceTo(goal) < 1.6D, "not on the loft yet: " + d.position());
            Vec3 decoy = h.absoluteVec(new Vec3(14.5, 3, 20.5));
            for (Vec3 p : trail) {
                h.assertTrue(p.distanceTo(decoy) > 2.0D, "he went up the stair that leads nowhere: " + p);
            }
        });
    }

    /** The graph of the stair test: start, the two stair feet, the named stair top, the goal. */
    private static final class SectSpotsFixture {
        private final GameTestHelper h;

        SectSpotsFixture(GameTestHelper h) {
            this.h = h;
        }

        BlockPos p(int x, int y, int z) {
            return h.absolutePos(new BlockPos(x, y, z));
        }

        SectLayoutFile file() {
            List<SectLayoutFile.Waypoint> wps = List.of(
                    new SectLayoutFile.Waypoint("anchor.start", p(6, 2, 19), "anchor", null, null),
                    new SectLayoutFile.Waypoint("anchor.west", p(10, 2, 5), "anchor", null, null),
                    new SectLayoutFile.Waypoint("stair.01.bottom", p(12, 2, 4), "stair_bottom", "stair.01", null),
                    new SectLayoutFile.Waypoint("stair.01.top", p(14, 3, 4), "stair_top", "stair.01", null),
                    new SectLayoutFile.Waypoint("anchor.loft", p(19, 3, 5), "anchor", null, null));
            List<SectLayoutFile.Edge> edges = List.of(
                    new SectLayoutFile.Edge(0, "anchor.start", "anchor.west", 16, List.of(), List.of(), false, false),
                    new SectLayoutFile.Edge(1, "anchor.west", "stair.01.bottom", 3, List.of(), List.of(), false, false),
                    new SectLayoutFile.Edge(2, "stair.01.bottom", "stair.01.top", 3, List.of(), List.of(), true, false),
                    new SectLayoutFile.Edge(3, "stair.01.top", "anchor.loft", 6, List.of(), List.of(), false, false));
            return new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), wps, edges, List.of());
        }
    }

    // ------------------------------------------------------------------ 3: at the table when the meal begins

    /**
     * Dinner begins at tick 9000 of the day. A first-wave person stands in the far corner of the yard, the dining seat is in the
     * opposite one: he must stay put until it is time to set off (not leave at the bell), and be seated by the bell plus the
     * arrival tolerance of {@link SectLead#JITTER}.
     */
    @GameTest(template = YARD, timeoutTicks = 1800, batch = "sect_routing_meal")
    public static void aDinerSetsOffInTimeAndIsSeatedWhenDinnerBegins(GameTestHelper h) {
        clearYard(h);
        BlockPos seatAt = h.absolutePos(new BlockPos(20, 2, 20));
        var seat = new SectLayoutFile.Spot("spot.table.1", "seat_bench", "sit", seatAt, seatAt.south(), null, 0.0F, null, "dining_hall", null,
                null, List.of("all"), "ok", -1, -1);
        SectLayoutFile file = new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(seat), List.of(), List.of(), List.of());
        SectDisciple d = npc(h, new Mini(file), "gyeong_ha", 2.5, 2, 2.5);
        long base = Math.floorDiv(h.getLevel().getDayTime(), 24000L) * 24000L;
        long[] clock = {7600L};
        Vec3 home = d.position();
        int[] firstMove = {-1};
        h.onEachTick(() -> {
            h.getLevel().setDayTime(base + clock[0]++);
            if (firstMove[0] < 0 && d.position().distanceTo(home) > 1.0D) {
                firstMove[0] = (int) (clock[0] - 1L);
            }
        });
        h.runAfterDelay(1750L, () -> {
            long bell = 9000L;
            h.assertTrue(firstMove[0] > 0, "he never set off");
            h.assertTrue(firstMove[0] < bell, "he left at " + firstMove[0] + ", after the bell: that is the late start of the playtest");
            h.assertTrue(firstMove[0] > 7600L + 60L, "he set off at once, with half an hour to spare: " + firstMove[0]);
            h.assertTrue(d.sitting() || d.position().distanceTo(Vec3.atBottomCenterOf(seatAt)) < 1.6D,
                    "not at the table at " + (clock[0] - 1L) + ": " + d.position() + " doing " + d.doingKind());
            MurimMod.LOGGER.info("Sect-meal: set off at {} for the bell at {}, seated by {}", firstMove[0], bell, clock[0] - 1L);
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ 4: off the furniture, never into a pillar

    /**
     * A person stands on a bed in a nook: pillars (a centred one and a thick corner one) and a wall around, a rug on the only free floor.
     * The step-off must put him on the free floor with his body clear of every pillar shape, and never inside a block whose collision or
     * model overlaps him (the pillar cells stand beside the free floor, 10 px thick: an exact-fit test would pass and the robe would sit in the wood).
     */
    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_routing_pillar")
    public static void steppingOffTheBedNeverPutsHimInAPillar(GameTestHelper h) {
        clearYard(h);
        var empty = new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        // floor at y 1; the bed at (10,2,10); walls around except the south; pillars at the sides of the free floor to the south
        for (int x = 8; x <= 12; x++) {
            for (int z = 8; z <= 14; z++) {
                h.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
                h.setBlock(new BlockPos(x, 3, z), Blocks.AIR);
            }
        }
        for (int z = 8; z <= 11; z++) {
            h.setBlock(new BlockPos(9, 2, z), Blocks.STONE_BRICKS);
            h.setBlock(new BlockPos(9, 3, z), Blocks.STONE_BRICKS);
            h.setBlock(new BlockPos(11, 2, z), Blocks.STONE_BRICKS);
            h.setBlock(new BlockPos(11, 3, z), Blocks.STONE_BRICKS);
        }
        h.setBlock(new BlockPos(10, 2, 8), Blocks.STONE_BRICKS);
        h.setBlock(new BlockPos(10, 3, 8), Blocks.STONE_BRICKS);
        h.setBlock(new BlockPos(10, 2, 10), Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, Direction.NORTH)
                .setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD));
        h.setBlock(new BlockPos(10, 2, 11), Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, Direction.NORTH)
                .setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT));
        // the free floor: (10,2,12) and (10,2,13) between a centred pillar at (9,2,12) and a thick corner pillar at (11,2,12)
        h.setBlock(new BlockPos(9, 2, 12), io.github.verycooltimo.murim.registry.ModBlocks.RED_PILLAR.get().defaultBlockState());
        h.setBlock(new BlockPos(9, 3, 12), io.github.verycooltimo.murim.registry.ModBlocks.RED_PILLAR.get().defaultBlockState());
        var thick = io.github.verycooltimo.murim.decor.HallJoinery.THICK_RED_PILLAR.get().defaultBlockState();
        h.setBlock(new BlockPos(11, 2, 12), thick);
        h.setBlock(new BlockPos(11, 3, 12), thick);
        SectDisciple d = npc(h, new Mini(empty), "gyeong_ha", 10.5, 2.5625, 11.5);
        d.setNoAi(true);      // a person without AI does not tick his gravity: he stands where he is put
        d.setOnGround(true);
        h.runAfterDelay(4L, () -> {
            h.assertTrue(Math.abs(d.getY() - h.absoluteVec(new Vec3(0, 2.5625D, 0)).y) < 0.01D, "he is not on the bed: " + d.position());
            boolean moved = d.walking().stepOffFurniture();
            h.assertTrue(moved, "no step-off from the bed at " + d.position());
            var box = d.getBoundingBox();
            h.assertTrue(h.getLevel().noCollision(d, box), "he stands in a block: " + d.position());
            // no block whose model (shape, not only collision) overlaps the body
            for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(box.minX - 0.01, box.minY, box.minZ - 0.01),
                    BlockPos.containing(box.maxX + 0.01, box.maxY, box.maxZ + 0.01))) {
                var state = h.getLevel().getBlockState(p);
                if (state.isAir()) {
                    continue;
                }
                var shape = state.getShape(h.getLevel(), p).move(p.getX(), p.getY(), p.getZ());
                boolean overlaps = !shape.isEmpty() && shape.toAabbs().stream().anyMatch(a -> a.intersects(box));
                boolean carpet = state.getCollisionShape(h.getLevel(), p).isEmpty() || state.getCollisionShape(h.getLevel(), p).max(Direction.Axis.Y) <= 0.1D;
                h.assertTrue(!overlaps || carpet, "his body overlaps " + state.getBlock() + " at " + p.toShortString() + " (he stands at " + d.position() + ")");
            }
            h.succeed();
        });
    }
}
