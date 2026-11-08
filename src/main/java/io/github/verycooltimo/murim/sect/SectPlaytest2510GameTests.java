package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.IntSeating;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Author playtest 25.10: actual navigation, seat leases, ordinary door ownership and office access. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectPlaytest2510GameTests {
    private SectPlaytest2510GameTests() { }

    private record Mini(SectLayoutFile file) implements SectLayout {
        public Vec3 at(String zone, double u, double v) { return null; }
        public float yaw(double u, double v) { return SectLayout.yawOf(u, v); }
        public double[] half(String zone) { return null; }
        public double[] local(String zone, Vec3 point) { return null; }
    }

    private static SectDisciple npc(GameTestHelper h, SectLayoutFile file, String key, int x, int z) {
        var d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setLayout(new Mini(file));
        d.setKeepAwake(true);
        Vec3 p = h.absoluteVec(new Vec3(x + 0.5, 2, z + 0.5));
        d.moveTo(p.x, p.y, p.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static BlockPos p(GameTestHelper h, int x, int z) { return h.absolutePos(new BlockPos(x, 2, z)); }

    private static void clear(GameTestHelper h) {
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(1, 2, 1), new BlockPos(23, 6, 23))) h.setBlock(pos, Blocks.AIR);
    }

    private static SectLayoutFile file(List<SectLayoutFile.Room> rooms, List<SectLayoutFile.Door> doors, List<SectLayoutFile.Spot> spots) {
        return new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), rooms, doors, List.of(), List.of(),
                spots, List.of(), List.of(), List.of());
    }

    private static SectLayoutFile.Room room(GameTestHelper h, String id, String building, int x0, int z0, int x1, int z1) {
        return new SectLayoutFile.Room(id, building, 2, p(h, x0, z0).getY(),
                new SectLayoutFile.Rect(p(h, x0, z0).getX(), p(h, x0, z0).getZ(), p(h, x1, z1).getX(), p(h, x1, z1).getZ()),
                "counting_room", "ok", List.of(), List.of(), 200);
    }

    private static SectLayoutFile.Spot seat(GameTestHelper h, String id, String kind, int x, int z, String room, String building, float yaw) {
        return new SectLayoutFile.Spot(id, kind, "sit", p(h, x, z), p(h, x - 1, z), null, yaw, room, building,
                null, null, List.of("all", "elder_treasury"), "ok", -1, -1);
    }

    @GameTest(template = "sect_yard", timeoutTicks = 8000, batch = "sect_2510_full_hall")
    public static void fullHallKeepsSeatsAndEmptiesWithinFiveThousandTicks(GameTestHelper h) {
        clear(h);
        // tools/gametest_yard.py puts the template floor at y=0. This room's feet,
        // stools and door are at y=2: supply their floor, including the exterior landing.
        // Source: SectEscortArrivalGameTests#road holds chunks beyond the template.
        BlockPos a = p(h, 1, 1);
        BlockPos b = p(h, 28, 23);
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                h.getLevel().setChunkForced(cx, cz, true);
            }
        }
        for (int x = 1; x <= 28; x++) for (int z = 1; z <= 23; z++) {
            h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            for (int y = 2; y <= 6; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        h.getLevel().setDayTime(10000);
        for (int y = 2; y <= 4; y++) {
            for (int x = 3; x <= 20; x++) for (int z : new int[] {3, 21}) h.setBlock(new BlockPos(x, y, z), Blocks.OAK_PLANKS);
            for (int z = 3; z <= 21; z++) for (int x : new int[] {3, 20}) h.setBlock(new BlockPos(x, y, z), Blocks.OAK_PLANKS);
        }
        // A single real opening. There is no route through any other wall.
        h.setBlock(new BlockPos(20, 2, 12), Blocks.AIR);
        h.setBlock(new BlockPos(20, 3, 12), Blocks.AIR);
        List<SectLayoutFile.Spot> seats = new ArrayList<>();
        for (int x : new int[] {6, 11, 16}) for (int z : new int[] {5, 8, 11, 14, 17}) {
            h.setBlock(new BlockPos(x, 2, z), IntSeating.STOOL.get());
            seats.add(seat(h, "diner." + seats.size(), "seat_stool", x, z, "meal", "dining_hall", 90));
        }
        var door = new SectLayoutFile.Door("exit", "opening", "free", p(h, 20, 12), p(h, 20, 12),
                List.of(p(h, 19, 12)), List.of(p(h, 21, 12)), "meal", null, 1, "ok");
        var f = file(List.of(room(h, "meal", "dining_hall", 4, 4, 19, 20)), List.of(door), seats);
        // A person with no next task must not monopolize the sole old exit target.
        // SectDisciple#tickLife still resolves routines with NoAI: sharing f would let
        // this second-wave porter reserve one of the fifteen diners' seats.
        var outside = npc(h, file(List.of(), List.of(), List.of()), "porter_jang", 23, 12);
        outside.setNoAi(true);
        List<SectDisciple> diners = new ArrayList<>();
        var members = SectRoster.generation(3).stream().filter(m -> !SectSchedule.officeHelper(m.key())).toList();
        for (int i = 0; i < members.size(); i++) diners.add(npc(h, f, members.get(i).key(), 5 + i % 3 * 5, 5 + i / 3 * 3));
        Map<String, String> assigned = new HashMap<>();
        boolean[] leaving = {false};
        long[] exitAt = {-1};
        h.onEachTick(() -> {
            h.getLevel().setDayTime(leaving[0] ? 11000 : 10000);
            for (var s : seats) h.assertFalse(outside.memberKey().equals(
                    SectSpots.of(h.getLevel()).holder(s.id(), h.getLevel().getGameTime())),
                    "idle exit blocker reserved a dining seat");
            if (!leaving[0]) for (var d : diners) if (d.sitting() && d.doingSpot() != null) {
                String previous = assigned.putIfAbsent(d.memberKey(), d.doingSpot());
                h.assertTrue(previous == null || previous.equals(d.doingSpot()), "diner changed seats during the meal");
                d.banSpot(d.doingSpot(), 1200); // even a stuck-path ban must not make him reseat
            }
        });
        h.startSequence().thenWaitUntil(() -> {
            h.assertTrue(diners.size() == seats.size(), "fixture is not a full hall");
            for (var d : diners) h.assertTrue(d.sitting(), "hall is still filling: " + d.memberKey()
                    + " ticks=" + d.tickCount + " alive=" + d.isAlive() + " pos=" + d.position()
                    + " task=" + d.doingKind() + " spot=" + d.doingSpot() + " " + d.walking().debug());
        }).thenExecute(() -> { leaving[0] = true; exitAt[0] = h.getTick(); })
                .thenWaitUntil(() -> {
                    for (var d : diners) h.assertTrue(!d.sitting() && d.getX() > p(h, 21, 12).getX(), "stuck diner: " + d.memberKey());
                    h.assertTrue(h.getTick() - exitAt[0] <= 5000, "hall exceeded the exit bound");
                }).thenSucceed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 500, batch = "sect_2510_player_door")
    public static void playerOpenedHouseDoorSurvivesDayAndNightSweeps(GameTestHelper h) {
        clear(h);
        BlockPos base = p(h, 12, 12);
        var door = io.github.verycooltimo.murim.decor.HallJoinery.HALL_DOOR.get();
        for (var half : DoubleBlockHalf.values()) h.getLevel().setBlock(half == DoubleBlockHalf.LOWER ? base : base.above(),
                door.defaultBlockState().setValue(DoorBlock.HALF, half).setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.OPEN, true), 3);
        var passer = npc(h, file(List.of(), List.of(), List.of()), "porter_jang", 18, 12);
        passer.setNoAi(true);
        h.onEachTick(() -> {
            h.getLevel().setDayTime(h.getTick() < 220 ? 4000 : 18000);
            SectDoors.sweep(h.getLevel());
        });
        h.runAfterDelay(450, () -> {
            h.assertTrue(h.getLevel().getBlockState(base).getValue(DoorBlock.OPEN), "sweep adopted a player's house door");
            h.succeed();
        });
    }

    @GameTest(template = "sect_yard", timeoutTicks = 160, batch = "sect_2510_owner_door")
    public static void houseDoorClosesOnlyAfterItsOpenerCrosses(GameTestHelper h) {
        clear(h);
        BlockPos base = p(h, 12, 12);
        var door = io.github.verycooltimo.murim.decor.HallJoinery.HALL_DOOR.get();
        for (var half : DoubleBlockHalf.values()) h.getLevel().setBlock(half == DoubleBlockHalf.LOWER ? base : base.above(),
                door.defaultBlockState().setValue(DoorBlock.HALF, half).setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.OPEN, true), 3);
        var owner = npc(h, file(List.of(), List.of(), List.of()), "porter_jang", 8, 12);
        owner.setNoAi(true);
        SectDoors.mark(owner, base, h.getLevel().getGameTime());
        h.onEachTick(() -> SectDoors.sweep(h.getLevel()));
        h.runAfterDelay(60, () -> {
            h.assertTrue(h.getLevel().getBlockState(base).getValue(DoorBlock.OPEN), "closed before the opener crossed");
            owner.setPos(h.absoluteVec(new Vec3(18.5, 2, 12.5)));
        });
        h.runAfterDelay(120, () -> {
            h.assertFalse(h.getLevel().getBlockState(base).getValue(DoorBlock.OPEN), "opener did not close behind himself");
            h.succeed();
        });
    }
    @GameTest(template = "sect_yard", timeoutTicks = 1800, batch = "sect_2510_office")
    public static void financierUsesHisArmchairAndJuniorsWorkAtAllRotations(GameTestHelper h) {
        clear(h);
        // Run rotations in sequence so the same roster members do not coexist (SectLife duplicate protection).
        int[] rotation = {0};
        int[] seatedTicks = {0};
        SectDisciple[] actors = new SectDisciple[3];
        h.onEachTick(() -> h.getLevel().setDayTime(28000)); // ordinary working day, no council hall in this fixture
        Runnable[] next = new Runnable[1];
        next[0] = () -> {
            clear(h);
            float yaw = rotation[0] * 90.0F;
            Direction front = Direction.fromYRot(yaw), side = front.getClockWise();
            BlockPos chair = p(h, 12, 10);
            List<SectLayoutFile.Spot> spots = new ArrayList<>();
            spots.add(new SectLayoutFile.Spot("own", "seat_armchair", "sit", chair, chair.relative(side), null, yaw,
                    "office", "treasury", null, null, List.of("elder_treasury"), "ok", -1, -1));
            h.getLevel().setBlock(chair, IntSeating.ARMCHAIR.get().defaultBlockState().setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, front), 3);
            for (int sign : new int[] {-1, 1}) {
                BlockPos visitor = chair.relative(front, 3).relative(side, sign);
                spots.add(new SectLayoutFile.Spot("visitor." + sign, "seat_chair", "sit", visitor, visitor.relative(front), null,
                        yaw + 180, "office", "treasury", null, null, List.of("all"), "ok", -1, -1));
                h.getLevel().setBlock(visitor, IntSeating.CHAIR.get().defaultBlockState().setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, front.getOpposite()), 3);
                for (int forward = 0; forward <= 2; forward++) {
                    BlockPos clutter = chair.relative(side, sign * 2).relative(front, forward);
                    h.getLevel().setBlock(clutter, io.github.verycooltimo.murim.decor.IntStore.CABINET.get().defaultBlockState(), 3);
                    h.getLevel().setBlock(clutter.above(), io.github.verycooltimo.murim.decor.IntStore.CABINET.get().defaultBlockState(), 3);
                }
            }
            for (int offset = -1; offset <= 1; offset++) h.getLevel().setBlock(chair.relative(front).relative(side, offset), Blocks.OAK_PLANKS.defaultBlockState(), 3);
            var f = file(List.of(room(h, "office", "treasury", 5, 4, 20, 20)), List.of(), spots);
            actors[0] = npc(h, f, "tae_gyun", 8, 18);
            actors[1] = npc(h, f, "yul_san", 10, 18);
            actors[2] = npc(h, f, "yul_ak", 14, 18);
            seatedTicks[0] = 0;
            h.runAfterDelay(30, () -> {
                for (int sign : new int[] {-1, 1}) {
                    var path = actors[0].getNavigation().createPath(chair.relative(side, sign * 2), 0);
                    h.assertTrue(path != null && path.canReach(), "desk side blocked at rotation " + rotation[0] + " side " + sign);
                }
            });
            h.runAfterDelay(400, () -> {
                h.assertTrue(seatedTicks[0] >= 200, "financier spent too little of the working day in his own chair at rotation " + rotation[0] + ": " + seatedTicks[0]);
                for (int i = 1; i <= 2; i++) h.assertTrue(actors[i].doingKind() == SectSchedule.Kind.WORK
                        && actors[i].doingSpot() != null && actors[i].doingSpot().startsWith("visitor."), "junior left the office job");
                for (var actor : actors) actor.discard();
                if (++rotation[0] < 4) next[0].run(); else h.succeed();
            });
        };
        h.onEachTick(() -> {
            if (actors[0] != null && actors[0].sitting() && "own".equals(actors[0].doingSpot())) seatedTicks[0]++;
        });
        next[0].run();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 160, batch = "sect_2510_late_diner")
    public static void lateDinerWaitsAndPartialExitPathsAreRejected(GameTestHelper h) {
        clear(h);
        // A valid floor outside a door, but a sealed wall between it and the diner: endpoint checks alone would accept it.
        for (int z = 2; z <= 20; z++) for (int y = 2; y <= 5; y++) h.setBlock(new BlockPos(18, y, z), Blocks.OAK_PLANKS);
        for (int x = 2; x <= 18; x++) for (int y = 2; y <= 5; y++) {
            h.setBlock(new BlockPos(x, y, 2), Blocks.OAK_PLANKS);
            h.setBlock(new BlockPos(x, y, 20), Blocks.OAK_PLANKS);
        }
        for (int z = 2; z <= 20; z++) for (int y = 2; y <= 5; y++) h.setBlock(new BlockPos(2, y, z), Blocks.OAK_PLANKS);
        var spot = seat(h, "meal", "seat_stool", 10, 10, "meal", "dining_hall", 0);
        var door = new SectLayoutFile.Door("blocked", "opening", "free", p(h, 18, 10), p(h, 18, 10),
                List.of(p(h, 17, 10)), List.of(p(h, 19, 10)), "meal", null, 1, "ok");
        var f = file(List.of(room(h, "meal", "dining_hall", 3, 3, 17, 19)), List.of(door), List.of(spot));
        var leaver = npc(h, f, "gyeong_ha", 10, 10);
        var late = npc(h, f, "yul_jin", 21, 14);
        leaver.setNoAi(true); late.setNoAi(true);
        leaver.setOnGround(true); late.setOnGround(true);
        var traffic = new SectDining(leaver);
        h.assertTrue(traffic.begin(new SectLife.Resolved(new SectSchedule.Task(SectSchedule.Kind.EAT, "dining", 0, 0, 0, 0),
                leaver.position(), 0, "meal", "meal", null)), "departure not registered");
        h.onEachTick(() -> traffic.tick());
        h.runAfterDelay(100, () -> {
            h.assertTrue(traffic.leaving() && leaver.walking().goal() == null, "partial exit path was accepted");
            h.assertTrue(SectDining.waitForAisle(late), "latecomer did not yield to the outgoing aisle");
            traffic.cancel();
            h.assertFalse(SectDining.waitForAisle(late), "latecomer kept waiting after the aisle cleared");
            h.succeed();
        });
    }

    @GameTest(template = "sect_yard", timeoutTicks = 160, batch = "sect_2510_player_reopen")
    public static void playerReopeningCancelsTheOriginalNpcDoorOwner(GameTestHelper h) {
        clear(h);
        BlockPos base = p(h, 12, 12);
        var door = io.github.verycooltimo.murim.decor.HallJoinery.HALL_DOOR.get();
        for (var half : DoubleBlockHalf.values()) h.getLevel().setBlock(half == DoubleBlockHalf.LOWER ? base : base.above(),
                door.defaultBlockState().setValue(DoorBlock.HALF, half).setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.OPEN, true), 3);
        var owner = npc(h, file(List.of(), List.of(), List.of()), "porter_jang", 8, 12);
        owner.setNoAi(true);
        SectDoors.mark(owner, base, h.getLevel().getGameTime());
        owner.setPos(h.absoluteVec(new Vec3(18.5, 2, 12.5)));
        // Event handler normalizes either half, even if close/reopen occurs between two sweep ticks.
        SectDoors.playerUsed(h.getLevel(), base.above(), h.getLevel().getBlockState(base.above()));
        h.onEachTick(() -> SectDoors.sweep(h.getLevel()));
        h.runAfterDelay(120, () -> {
            h.assertTrue(h.getLevel().getBlockState(base).getValue(DoorBlock.OPEN), "stale opener closed the player's reopened door");
            h.succeed();
        });
    }

    @GameTest(template = "sect_yard", batch = "sect_2510_meal_reservation")
    public static void mealReservationSurvivesLeaseExpiryAndPathBans(GameTestHelper h) {
        var seats = List.of(seat(h, "one", "seat_stool", 8, 10, "meal", "dining_hall", 0),
                seat(h, "two", "seat_stool", 12, 10, "meal", "dining_hall", 0));
        var f = file(List.of(room(h, "meal", "dining_hall", 4, 4, 18, 18)), List.of(), seats);
        var claims = new SectSpots();
        var task = new SectSchedule.Task(SectSchedule.Kind.EAT, "dining", 0, 0, 0, 0);
        var first = SectPlaces.pick(f, SectRoster.of("gyeong_ha").orElseThrow(), task, 9000, claims, 0, Map.of());
        h.assertTrue(first != null, "first diner got no seat");
        long later = SectSpots.TTL + 20;
        var second = SectPlaces.pick(f, SectRoster.of("gyeong_on").orElseThrow(), task, 9000 + later, claims, later, Map.of());
        h.assertTrue(second != null && !first.spotId().equals(second.spotId()), "expired lease let another diner take a meal seat");
        var returned = SectPlaces.pick(f, SectRoster.of("gyeong_ha").orElseThrow(), task, 9000 + later, claims, later,
                Map.of(first.spotId(), later + 1200));
        h.assertTrue(returned != null && first.spotId().equals(returned.spotId()), "interrupted diner moved to a different seat");
        h.succeed();
    }

}
