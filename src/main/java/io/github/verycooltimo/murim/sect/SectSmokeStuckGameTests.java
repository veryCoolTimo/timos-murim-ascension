package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModBlocks;
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

import java.util.List;
import java.util.Map;

/**
 * Walks that stood still for 480 ticks in the smoke pass of 22.10 (fresh worlds at four rotations), rebuilt as small scenes.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectSmokeStuckGameTests {

    private static final String YARD = "sect_yard";

    private SectSmokeStuckGameTests() {
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
                for (int y = 2; y <= 16; y++) {
                    h.getLevel().setBlock(h.absolutePos(new BlockPos(x, y, z)), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private static String pathText(SectDisciple d) {
        var p = d.getNavigation().getPath();
        if (p == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder("next=" + p.getNextNodeIndex() + "/" + p.getNodeCount() + " done=" + d.getNavigation().isDone());
        for (int i = 0; i < Math.min(5, p.getNodeCount()); i++) {
            sb.append(" [").append(p.getNode(i).x).append(',').append(p.getNode(i).y).append(',').append(p.getNode(i).z).append(']');
        }
        return sb.append(" hcol=").append(d.horizontalCollision).append(" ground=").append(d.onGround()).toString();
    }


    /** Places the blocks of a dump taken from a smoke world ("x y z state" per line, relative to its corner) at the test origin, lifted by {@code lift}. */
    private static int load(GameTestHelper h, String resource, int lift) throws Exception {
        int placed = 0;
        try (var in = SectSmokeStuckGameTests.class.getResourceAsStream(resource);
             var rd = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = rd.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split(" ", 4);
                var parsed = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                        h.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK), f[3], false);
                BlockPos at = h.absolutePos(new BlockPos(Integer.parseInt(f[0]), Integer.parseInt(f[1]) + lift, Integer.parseInt(f[2])));
                h.getLevel().setBlock(at, parsed.blockState(), 2);
                placed++;
            }
        }
        return placed;
    }

    private static SectLayoutFile emptyFile() {
        return new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    /**
     * A person pressed against the side of a thin pillar: the pillar is 5 px wide, so the body touches it with its middle in the cell
     * of the pillar and the pathfinder starts inside an obstacle (smoke pass: three people of the dawn formation row stood so for 480
     * ticks, "nopath", feet and head in {@code red_pillar}). He must walk on to a goal behind the wall.
     */
    @GameTest(template = YARD, timeoutTicks = 400, batch = "sect_stuck_pillar")
    public static void aWalkerPressedAgainstAThinPillarWalksOn(GameTestHelper h) {
        clearYard(h);
        // the pocket: wall to the east (x 10) from z 12 south, a pillar at (9,12) in the lane, open to the west
        for (int z = 12; z <= 18; z++) {
            for (int y = 2; y <= 4; y++) {
                h.setBlock(new BlockPos(10, y, z), Blocks.STONE_BRICKS);
            }
        }
        var pillar = ModBlocks.RED_PILLAR.get().defaultBlockState();
        h.setBlock(new BlockPos(9, 2, 12), pillar);
        h.setBlock(new BlockPos(9, 3, 12), pillar);
        SectDisciple d = npc(h, new Mini(emptyFile()), "gyeong_ha", 9.5, 2, 13.5);
        Vec3 goal = h.absoluteVec(new Vec3(9.5, 2, 5.5));
        Vec3 start = h.absoluteVec(new Vec3(9.5, 2, 13.5));
        // press him against the pillar: the body touches its south face
        h.runAfterDelay(2L, () -> {
            Vec3 at = h.absoluteVec(new Vec3(9.5, 2, 12.0 + 0.66 + 0.3 + 0.005));
            d.setPos(at);
            d.setDeltaMovement(Vec3.ZERO);
        });
        h.onEachTick(() -> {
            if (h.getTick() > 4L) {
                d.walking().walk(goal, 0.9D);
            }
        });
        h.succeedWhen(() -> h.assertTrue(d.position().distanceTo(goal) < 1.6D, "still not at the goal behind the pillar: " + d.position()
                + " cell " + d.blockPosition().toShortString() + " nav=" + pathText(d)));
    }

    /**
     * A flight as tall as stair.07 of the sect (12 steps, three wide, open to one side) between a foot and a landing, with the route the
     * graph gives: he must reach the landing.
     */
    @GameTest(template = YARD, timeoutTicks = 900, batch = "sect_stuck_flight")
    public static void aLongOpenFlightIsClimbedToTheLanding(GameTestHelper h) {
        clearYard(h);
        BlockState step = ModBlocks.POLISHED_HUA_GRANITE_STAIRS.get().defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
        BlockState rock = ModBlocks.POLISHED_HUA_GRANITE.get().defaultBlockState();
        int steps = 12;
        for (int i = 0; i < steps; i++) {
            for (int z = 4; z <= 6; z++) {
                h.getLevel().setBlock(h.absolutePos(new BlockPos(2 + i, 2 + i, z)), step, 3);
                for (int y = 1; y < 2 + i; y++) {
                    h.getLevel().setBlock(h.absolutePos(new BlockPos(2 + i, y, z)), rock, 3);
                }
            }
        }
        for (int x = 2 + steps; x <= 20; x++) {
            for (int z = 2; z <= 8; z++) {
                for (int y = 1; y <= 1 + steps; y++) {
                    h.getLevel().setBlock(h.absolutePos(new BlockPos(x, y, z)), rock, 3);
                }
            }
        }
        // stair feet at (1,2,5), landing at (2+steps, 2+steps, 5)
        List<SectLayoutFile.Waypoint> wps = List.of(
                new SectLayoutFile.Waypoint("anchor.start", h.absolutePos(new BlockPos(1, 2, 12)), "anchor", null, null),
                new SectLayoutFile.Waypoint("stair.01.bottom", h.absolutePos(new BlockPos(1, 2, 5)), "stair_bottom", "stair.01", null),
                new SectLayoutFile.Waypoint("stair.01.top", h.absolutePos(new BlockPos(2 + steps, 2 + steps, 5)), "stair_top", "stair.01", null));
        List<SectLayoutFile.Edge> edges = List.of(
                new SectLayoutFile.Edge(0, "anchor.start", "stair.01.bottom", 8, List.of(), List.of(), false, false),
                new SectLayoutFile.Edge(1, "stair.01.bottom", "stair.01.top", steps, List.of(), List.of(), true, false));
        SectLayoutFile file = new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), wps, edges, List.of());
        SectDisciple d = npc(h, new Mini(file), "gyeong_ha", 1.5, 2, 12.5);
        Vec3 goal = h.absoluteVec(new Vec3(2 + steps + 3.5, 2 + steps, 5.5));
        h.onEachTick(() -> d.walking().walk(goal, 0.9D));
        h.succeedWhen(() -> h.assertTrue(d.position().distanceTo(goal) < 1.6D, "not at the landing yet: " + d.position()));
    }

    /**
     * The last step into a cell that holds a rug: the pathfinder ends one cell short (its goal accuracy), so the last fraction of a
     * block is walked with ordinary travel, and that step must not treat a 1 px rug (collision 0.075 high) as a wall. Smoke pass 22.10:
     * dorm sleepers stood in the doorway 1.35 blocks from the cell beside their bed, "free=false", for 480 ticks.
     */
    @GameTest(template = YARD, timeoutTicks = 300, batch = "sect_stuck_rug")
    public static void theLastStepOntoARugIsWalked(GameTestHelper h) {
        clearYard(h);
        // a wall across the room with a one-wide hall door (open, as the sect leaves it for the sleepers) and the rug behind it
        for (int x = 5; x <= 13; x++) {
            for (int y = 2; y <= 4; y++) {
                if (x != 9) {
                    h.setBlock(new BlockPos(x, y, 10), Blocks.STONE_BRICKS);
                }
            }
        }
        var door = io.github.verycooltimo.murim.decor.HallJoinery.HALL_DOOR.get().defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.SOUTH)
                .setValue(net.minecraft.world.level.block.DoorBlock.OPEN, true);
        h.setBlock(new BlockPos(9, 2, 10), door.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
        h.setBlock(new BlockPos(9, 3, 10), door.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        h.setBlock(new BlockPos(9, 2, 9), io.github.verycooltimo.murim.decor.IntStore.RUG_STRAW.get().defaultBlockState());
        SectDisciple d = npc(h, new Mini(emptyFile()), "gyeong_ha", 9.5, 2, 10.85);
        Vec3 goal = h.absoluteVec(new Vec3(9.5, 2.0, 9.5));
        h.onEachTick(() -> d.walking().walk(goal, 0.9D));
        h.succeedWhen(() -> h.assertTrue(d.position().distanceToSqr(goal) < 0.3D, "stopped short of the rug cell: " + d.position() + " goal " + goal));
    }

    /**
     * stair.07 as the smoke world built it (blocks copied from the fresh world, seed 424242 rotation 0: x -943..-924, y 209..228, z 3144..3152,
     * shifted so that y 209 is the floor of the yard). Seo Gi stood on this flight 480 ticks in three of five worlds, always on the
     * sixth step, hopping on the spot; each relocation put him back at the foot and he hopped again (24 relocations in one run).
     */
    @GameTest(template = YARD, timeoutTicks = 1200, batch = "sect_stuck_stair07")
    public static void stair07OfTheSmokeWorldIsClimbedFromFootToLanding(GameTestHelper h) throws Exception {
        clearYard(h);
        int placed = load(h, "/data/murim/gametest/stair07_smoke_world.txt", 1);
        h.assertTrue(placed > 1500, "the stair blocks were not read: " + placed);
        // world (x, y, z) -> test (x + 943, y - 208, z - 3144)
        List<SectLayoutFile.Waypoint> wps = List.of(
                new SectLayoutFile.Waypoint("stair.07.bottom", h.absolutePos(new BlockPos(3, 5, 3)), "stair_bottom", "stair.07", null),
                new SectLayoutFile.Waypoint("stair.07.top", h.absolutePos(new BlockPos(15, 17, 3)), "stair_top", "stair.07", null));
        List<SectLayoutFile.Edge> edges = List.of(
                new SectLayoutFile.Edge(0, "stair.07.bottom", "stair.07.top", 12, List.of(), List.of(), true, false));
        SectLayoutFile file = new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), wps, edges, List.of());
        SectDisciple d = npc(h, new Mini(file), "seo_gi", 3.5, 5, 3.5);
        Vec3 goal = h.absoluteVec(new Vec3(18.5, 17, 3.5));
        double[] best = {Double.MAX_VALUE};
        long[] bestAt = {0};
        h.onEachTick(() -> {
            d.walking().walk(goal, 0.9D);
            double r = d.position().distanceTo(goal);
            if (r < best[0] - 0.5D) {
                best[0] = r;
                bestAt[0] = h.getTick();
            }
            h.assertTrue(h.getTick() - bestAt[0] < 300L || r < 1.6D, "no headway for 300 ticks at " + d.position() + " cell " + d.blockPosition().toShortString()
                    + " nav=" + pathText(d));
        });
        h.succeedWhen(() -> h.assertTrue(d.position().distanceTo(goal) < 1.6D, "not at the landing yet: " + d.position()));
    }

    /**
     * A dorm room of the smoke world (seed 424242, rotation 1): yul_bin stood 480 ticks on the edge of a chest (0.75 high) in the aisle
     * between the beds, "partial-done", 1.8 blocks from the cell beside his bed. He must get down and walk to it.
     */
    @GameTest(template = YARD, timeoutTicks = 600, batch = "sect_stuck_dorm_room")
    public static void aSleeperOnTheEdgeOfAChestWalksToTheCellBesideHisBed(GameTestHelper h) throws Exception {
        clearYard(h);
        int placed = load(h, "/data/murim/gametest/dorm_room_bed38_smoke_world.txt", 0);
        h.assertTrue(placed > 500, "the room was not read: " + placed);
        SectDisciple d = npc(h, new Mini(emptyFile()), "gyeong_ha", 9.22, 2.75, 8.0);
        d.setNoAi(false);
        Vec3 goal = h.absoluteVec(new Vec3(7.5, 2.0, 8.5));
        h.onEachTick(() -> d.walking().walk(goal, 0.9D));
        h.succeedWhen(() -> h.assertTrue(d.position().distanceToSqr(goal) < 0.5D, "still at " + d.position() + " goal " + goal + " " + d.walking().describe()));
    }

    /**
     * The room of bed.19 in the smoke world (seed 424242, rotation 0): beds and chests on both walls, a tea table and a stool in the
     * aisle, so the aisle is one lane wide at the table. Two servants met head-on in it at night and stood nose to nose for 480 ticks,
     * four times in a row ("free=false", crowd 3): one has to give way. Room dump x -984..-974, y 213..219, z 3142..3153.
     */
    @GameTest(template = YARD, timeoutTicks = 900, batch = "sect_stuck_dorm_room")
    public static void twoWalkersMeetingHeadOnInTheOneLaneAisleGetPastEachOther(GameTestHelper h) throws Exception {
        clearYard(h);
        int placed = load(h, "/data/murim/gametest/dorm_room_bed19_smoke_world.txt", 1);
        h.assertTrue(placed > 300, "the room was not read: " + placed);
        // world (x, y, z) -> test (x + 984, y - 212, z - 3142)
        SectDisciple north = npc(h, new Mini(emptyFile()), "gyeong_ha", 5.3, 3.1, 4.9);       // in the cell beside the bed, wants out
        SectDisciple south = npc(h, new Mini(emptyFile()), "water_gu", 5.46, 3.08, 5.78);     // in the lane at the table, wants in
        Vec3 out = h.absoluteVec(new Vec3(5.5, 3.0, 9.5));
        Vec3 in = h.absoluteVec(new Vec3(5.5, 3.0, 4.5));
        h.onEachTick(() -> {
            north.walking().walk(out, 0.9D);
            south.walking().walk(in, 0.9D);
        });
        h.succeedWhen(() -> {
            h.assertTrue(north.position().distanceToSqr(out) < 1.0D, "the one who wants out: " + north.walking().describe());
            h.assertTrue(south.position().distanceToSqr(in) < 1.0D, "the one who wants in: " + south.walking().describe());
        });
    }

    /**
     * The shelter of the rain is the covered cell nearest to the place of the work, and must be on the same side of the walls: the
     * nearest by distance was behind a brick wall, and a first-rank disciple walked along the wall for 480 ticks (twice) in the smoke
     * pass of 23.10.
     */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "sect_stuck_shelter")
    public static void theRainShelterIsOnTheSameSideOfTheWallAsTheWork(GameTestHelper h) {
        clearYard(h);
        for (int x = 6; x <= 18; x++) {
            for (int y = 2; y <= 4; y++) {
                h.setBlock(new BlockPos(x, y, 10), Blocks.STONE_BRICKS);
            }
        }
        // a roof behind the wall, 4 blocks from the work, and a roof on the work's side, 8 blocks away
        for (int x = 10; x <= 14; x++) {
            for (int z = 5; z <= 9; z++) {
                h.setBlock(new BlockPos(x, 6, z), Blocks.OAK_PLANKS);
            }
        }
        for (int x = 19; x <= 23; x++) {
            for (int z = 12; z <= 16; z++) {
                h.setBlock(new BlockPos(x, 6, z), Blocks.OAK_PLANKS);
            }
        }
        SectDisciple d = npc(h, new Mini(emptyFile()), "gyeong_ha", 12.5, 2, 12.5);
        Vec3 work = h.absoluteVec(new Vec3(12.5, 2, 12.5));
        h.runAfterDelay(3L, () -> {
            Vec3 cover = SectWeather.find(d, work);
            h.assertTrue(cover != null, "no shelter found");
            Vec3 rel = h.relativeVec(cover);
            h.assertTrue(rel.z > 10.5D, "the shelter " + rel + " is behind the wall");
            h.assertTrue(SectWeather.covered(h.getLevel(), BlockPos.containing(cover)), "the shelter " + rel + " is not covered");
            h.succeed();
        });
    }

    /**
     * A tea table in one lane of a two-wide aisle, the walker in the aisle beside it, the goal a floor cell behind it: the way is the free
     * lane, never over the table (the "beside him" exception is for a person who already stands on furniture).
     */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "sect_stuck_aisle")
    public static void aWalkerOnTheFloorGoesRoundTheTeaTableNotOverIt(GameTestHelper h) {
        clearYard(h);
        for (int z = 4; z <= 16; z++) {
            for (int y = 2; y <= 4; y++) {
                h.setBlock(new BlockPos(7, y, z), Blocks.STONE_BRICKS);
                h.setBlock(new BlockPos(10, y, z), Blocks.STONE_BRICKS);
            }
        }
        BlockPos table = new BlockPos(8, 2, 10);
        h.setBlock(table, io.github.verycooltimo.murim.decor.IntSeating.TEA_TABLE.get().defaultBlockState());
        SectDisciple d = npc(h, new Mini(emptyFile()), "gyeong_ha", 8.5, 2, 11.5);
        h.runAfterDelay(4L, () -> {
            var path = d.getNavigation().createPath(h.absolutePos(new BlockPos(8, 2, 8)), 0);
            h.assertTrue(path != null && path.canReach(), "no way round the table: " + path);
            for (int i = 0; i < path.getNodeCount(); i++) {
                h.assertTrue(!path.getNode(i).asBlockPos().equals(h.absolutePos(table)), "the way leads over the tea table (node " + i + ")");
            }
            h.succeed();
        });
    }

    /**
     * A sleeper standing on a chest (0.75 high) in the middle of its cell, in the crowded room of bed.19 (the smoke worlds, rotations 0
     * and 3, seed 777: five sleepers stood so on chests and tea tables, "free=false" against the lantern over the aisle, 480 ticks each).
     * He must get down and walk to the cell beside his bed.
     */
    @GameTest(template = YARD, timeoutTicks = 600, batch = "sect_stuck_dorm_room")
    public static void aSleeperStandingOnAChestInTheRoomOfBed19GetsDownAndWalksToHisBed(GameTestHelper h) throws Exception {
        clearYard(h);
        h.assertTrue(load(h, "/data/murim/gametest/dorm_room_bed19_smoke_world.txt", 1) > 300, "the room was not read");
        // the chest of the east wall at world (-978, 215, 3147) -> test (6, 3, 5); its top is at 3.75
        SectDisciple d = npc(h, new Mini(emptyFile()), "water_gu", 6.5, 3.75, 5.5);
        d.setOnGround(true);
        Vec3 goal = h.absoluteVec(new Vec3(4.5, 3.0, 4.5));
        h.onEachTick(() -> d.walking().walk(goal, 0.9D));
        h.succeedWhen(() -> h.assertTrue(d.position().distanceToSqr(goal) < 0.5D, "water_gu: " + d.walking().describe()));
    }

    /** A covered cell with a thin wall in it is no shelter: the plate is a few px thick, so the cell passes for standable. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "sect_stuck_shelter")
    public static void theRainShelterIsNeverACellWithAThinWallInIt(GameTestHelper h) {
        clearYard(h);
        for (int z = 5; z <= 9; z += 3) {
            for (int x = 11; x <= 13; x++) {
                h.setBlock(new BlockPos(x, 6, z), Blocks.OAK_PLANKS);
            }
        }
        // the nearer roof (z 8) has a thin wall in each cell, the farther one (z 5) is clean
        for (int x = 11; x <= 13; x++) {
            h.setBlock(new BlockPos(x, 2, 8), io.github.verycooltimo.murim.decor.HallDecor.THIN_WALL_PLASTER_BASE.get().defaultBlockState());
            h.setBlock(new BlockPos(x, 3, 8), io.github.verycooltimo.murim.decor.HallDecor.THIN_WALL_PLASTER_BASE.get().defaultBlockState());
            h.setBlock(new BlockPos(x, 4, 8), io.github.verycooltimo.murim.decor.HallDecor.THIN_WALL_PLASTER_BASE.get().defaultBlockState());
        }
        SectDisciple d = npc(h, new Mini(emptyFile()), "gyeong_ha", 12.5, 2, 12.5);
        Vec3 work = h.absoluteVec(new Vec3(12.5, 2, 11.5));
        h.runAfterDelay(3L, () -> {
            Vec3 cover = SectWeather.find(d, work);
            h.assertTrue(cover != null, "no shelter found");
            Vec3 rel = h.relativeVec(cover);
            BlockPos cell = BlockPos.containing(cover);
            h.assertTrue(!(h.getLevel().getBlockState(cell).getBlock() instanceof io.github.verycooltimo.murim.decor.ThinWallBlock)
                    && !(h.getLevel().getBlockState(cell.above()).getBlock() instanceof io.github.verycooltimo.murim.decor.ThinWallBlock),
                    "the shelter " + rel + " is in a cell with a thin wall");
            h.succeed();
        });
    }

    /**
     * The last stuck step puts a person at his goal when it is a few steps away and in sight, not at the nearest waypoint (which sent
     * him through the same crowd again: 24 relocations of one guard in a run).
     */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "sect_stuck_relocate")
    public static void theRelocationPutsHimAtHisGoalWhenItIsNear(GameTestHelper h) {
        clearYard(h);
        List<SectLayoutFile.Waypoint> wps = List.of(new SectLayoutFile.Waypoint("anchor.far", h.absolutePos(new BlockPos(20, 2, 20)), "anchor", null, null));
        SectLayoutFile file = new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), wps, List.of(), List.of());
        SectDisciple d = npc(h, new Mini(file), "gyeong_ha", 3.5, 2, 3.5);
        Vec3 goal = h.absoluteVec(new Vec3(3.5, 2, 7.5));
        h.runAfterDelay(3L, () -> {
            d.walking().walk(goal, 0.9D);
            d.walking().relocate(file);
            h.assertTrue(d.position().distanceToSqr(goal) < 0.25D, "moved to " + d.position() + ", not to the goal " + goal);
            h.succeed();
        });
    }

    // ---- smoke pass 23.10, world 424242 rotation 3 (dumps are relative to the room corner, lifted by 1 so that the floor is the yard floor)

    /**
     * Bed.19 of rotation 3 (world x -2962..-2946, y 213.., z -1932..-1916): a sleeper put on the chest between the two beds, with a red beam
     * over every cell round it, cannot walk off at all (his head, 2.55 high on the chest, is in the beams), so he must be set on the floor.
     */
    @GameTest(template = YARD, timeoutTicks = 600, batch = "sect_stuck_dorm_room")
    public static void aSleeperOnTheChestBetweenTheBedsUnderBeamsIsSetDownAndWalks(GameTestHelper h) throws Exception {
        clearYard(h);
        h.assertTrue(load(h, "/data/murim/gametest/dorm_room_bed19_smoke_world_rot3.txt", 1) > 300, "the room was not read");
        // chest at world (-2954, 214, -1924) -> test (8, 2, 8); the cell beside the bed at (-2955, 214, -1923) -> (7, 2, 9)
        SectDisciple d = npc(h, new Mini(emptyFile()), "water_gu", 8.5, 2.75, 8.5);
        d.setOnGround(true);
        Vec3 goal = h.absoluteVec(new Vec3(7.5, 2.0, 9.5));
        h.onEachTick(() -> d.walking().walk(goal, 0.9D));
        h.succeedWhen(() -> h.assertTrue(d.position().distanceToSqr(goal) < 0.5D, "water_gu: " + d.position() + " " + d.walking().describe()));
    }

    /**
     * The same chest, asked directly: the sleeper's spot is the floor cell DIAGONAL to the chest (1.4 blocks), which the step-off rule once took
     * for "the task is this very piece" and refused to get him down (smoke pass 23.10: water_gu, 480 ticks, 2.55 blocks high under the beams).
     */
    @GameTest(template = YARD, timeoutTicks = 300, batch = "sect_stuck_dorm_room")
    public static void aSleeperOnTheChestIsSetDownWhenHisSpotIsTheDiagonalFloorCell(GameTestHelper h) throws Exception {
        clearYard(h);
        h.assertTrue(load(h, "/data/murim/gametest/dorm_room_bed19_smoke_world_rot3.txt", 1) > 300, "the room was not read");
        SectDisciple d = npc(h, new Mini(emptyFile()), "water_gu", 8.5, 2.75, 8.5);
        d.setNoAi(true);
        d.setOnGround(true);
        Vec3 goal = h.absoluteVec(new Vec3(7.5, 2.0, 9.5));
        // no AI: nothing but the step-off rule can bring him down
        h.onEachTick(() -> d.walking().walk(goal, 0.9D));
        h.succeedWhen(() -> h.assertTrue(d.getY() < h.absoluteVec(new Vec3(0, 2.4, 0)).y, "still on the chest: " + d.position()));
    }

    /** The same room, walked from its door in the east wall (world -2951, 214, -1923) along the aisle to the cell beside the bed. */
    @GameTest(template = YARD, timeoutTicks = 600, batch = "sect_stuck_dorm_room")
    public static void aServantWalksFromTheDoorOfBed19ToTheCellBesideTheBedAndNeverOntoTheChest(GameTestHelper h) throws Exception {
        clearYard(h);
        h.assertTrue(load(h, "/data/murim/gametest/dorm_room_bed19_smoke_world_rot3.txt", 1) > 300, "the room was not read");
        SectDisciple d = npc(h, new Mini(emptyFile()), "water_gu", 12.5, 2.0, 9.5);
        Vec3 goal = h.absoluteVec(new Vec3(7.5, 2.0, 9.5));
        BlockPos chest = h.absolutePos(new BlockPos(8, 2, 8));
        h.onEachTick(() -> {
            d.walking().walk(goal, 0.9D);
            h.assertTrue(d.getY() < h.absoluteVec(new Vec3(0, 2.4, 0)).y || d.distanceToSqr(goal) < 0.5D,
                    "stands on the furniture at " + d.position() + " chest " + chest.toShortString());
        });
        h.succeedWhen(() -> h.assertTrue(d.position().distanceToSqr(goal) < 0.5D, "water_gu: " + d.position() + " " + d.walking().describe()));
    }

    /**
     * Bed.02 of rotation 3: the way from the east door of the rug room (world -2970, 214, -1929) west to the door of the dorm room: a first-rank
     * stood on the rug 0.85 inside the door and did not move for 480 ticks ("reach-run@2/4", no collision).
     */
    @GameTest(template = YARD, timeoutTicks = 800, batch = "sect_stuck_dorm_room")
    public static void aWalkerThroughTheRugRoomOfBed02ReachesTheWestRoom(GameTestHelper h) throws Exception {
        clearYard(h);
        h.assertTrue(load(h, "/data/murim/gametest/dorm_room_bed02_smoke_world_rot3.txt", 1) > 300, "the room was not read");
        // world (x, y, z) -> test (x + 2988, y - 212, z + 1936)
        SectDisciple d = npc(h, new Mini(emptyFile()), "gyeong_on", 19.5, 2.0, 7.5);
        Vec3 goal = h.absoluteVec(new Vec3(5.5, 2.0, 7.5));
        h.onEachTick(() -> d.walking().walk(goal, 0.9D));
        h.succeedWhen(() -> h.assertTrue(d.position().distanceToSqr(goal) < 0.5D, "gyeong_on: " + d.position() + " " + d.walking().describe()));
    }

    /**
     * Beds 35 and 37 of rotation 3, second floor: two men in the one-row alley behind the shut hall door (world -2947, 218, -1934) and the
     * brick wall, the goal in the room across the corridor. The door is shut, as the sect leaves it.
     */
    @GameTest(template = YARD, timeoutTicks = 1000, batch = "sect_stuck_dorm_room")
    public static void aWalkerBehindAShutHallDoorOnTheSecondFloorReachesTheRoomAcrossTheCorridor(GameTestHelper h) throws Exception {
        clearYard(h);
        h.assertTrue(load(h, "/data/murim/gametest/dorm_room_bed35_smoke_world_rot3.txt", 1) > 300, "the room was not read");
        // world (x, y, z) -> test (x + 2960, y - 216, z + 1940)
        SectDisciple d = npc(h, new Mini(emptyFile()), "yul_do", 14.5, 2.0, 6.5);
        Vec3 goal = h.absoluteVec(new Vec3(4.5, 2.0, 10.5));
        h.onEachTick(() -> d.walking().walk(goal, 0.9D));
        h.succeedWhen(() -> h.assertTrue(d.position().distanceToSqr(goal) < 0.5D, "yul_do: " + d.position() + " " + d.walking().describe()));
    }

    /**
     * The gatekeeper of rotation 0 (smoke pass 23.10, edge door.52.a>stair.06.bottom, in the rain): perched on the lower half of the first stair of a
     * loft stairwell with a finger of his body, his middle over the 1-high ledge beside it, head in the planks of the loft floor, "hcol", no headway
     * for 480 ticks. He must be set down and walk on under the loft. World (x, y, z) -> test (x + 976, y - 213, z - 3148).
     */
    @GameTest(template = YARD, timeoutTicks = 800, batch = "sect_stuck_dorm_room")
    public static void aWalkerPerchedOnTheEdgeOfTheFirstStairUnderTheLoftGetsDownAndWalksOn(GameTestHelper h) throws Exception {
        clearYard(h);
        h.assertTrue(load(h, "/data/murim/gametest/gate_stair_loft_smoke_world_rot0.txt", 0) > 500, "the stairwell was not read");
        SectDisciple d = npc(h, new Mini(emptyFile()), "gyeong_ha", 12.25, 3.5, 9.3);
        d.setOnGround(true);
        Vec3 goal = h.absoluteVec(new Vec3(8.5, 2.0, 7.5));
        h.onEachTick(() -> d.walking().walk(goal, 0.9D));
        h.succeedWhen(() -> h.assertTrue(d.position().distanceToSqr(goal) < 0.7D, "gatekeeper: " + d.position() + " " + d.walking().describe()));
    }

    /**
     * Bed.02 of rotation 3 again, with the man who was in the aisle: Mok Hayeon walks east to the door as Gyeong On walks west along the same line in
     * the two-wide aisle of the rug room, and both stood nose to nose for 480 ticks (the side step looked two blocks aside, out of the aisle).
     */
    @GameTest(template = YARD, timeoutTicks = 900, batch = "sect_stuck_dorm_room")
    public static void twoWalkersMeetingHeadOnInTheTwoWideAisleOfBed02PassEachOther(GameTestHelper h) throws Exception {
        clearYard(h);
        h.assertTrue(load(h, "/data/murim/gametest/dorm_room_bed02_smoke_world_rot3.txt", 1) > 300, "the room was not read");
        SectDisciple west = npc(h, new Mini(emptyFile()), "gyeong_on", 17.0, 2.0, 7.5);
        SectDisciple east = npc(h, new Mini(emptyFile()), "mok_hayeon", 16.4, 2.0, 7.5);
        Vec3 toWest = h.absoluteVec(new Vec3(6.5, 2.0, 7.5));
        Vec3 toEast = h.absoluteVec(new Vec3(17.5, 2.0, 7.5));
        h.onEachTick(() -> {
            west.walking().walk(toWest, 0.9D);
            east.walking().walk(toEast, 0.9D);
        });
        h.succeedWhen(() -> {
            h.assertTrue(west.position().distanceToSqr(toWest) < 1.0D, "gyeong_on: " + west.position() + " " + west.walking().describe());
            h.assertTrue(east.position().distanceToSqr(toEast) < 1.0D, "mok_hayeon: " + east.position() + " " + east.walking().describe());
        });
    }

    /**
     * Beds 35 and 37 of rotation 3, as they stood: both men in the cell behind the shut hall door, both wanting out, a third coming in through
     * the same door from the corridor. The side step must never lead across the brick wall (the cell two blocks aside was behind it).
     */
    @GameTest(template = YARD, timeoutTicks = 1200, batch = "sect_stuck_dorm_room")
    public static void twoMenBehindAShutHallDoorAndOneComingInAllGetThrough(GameTestHelper h) throws Exception {
        clearYard(h);
        h.assertTrue(load(h, "/data/murim/gametest/dorm_room_bed35_smoke_world_rot3.txt", 1) > 300, "the room was not read");
        SectDisciple a = npc(h, new Mini(emptyFile()), "yul_do", 14.5, 2.0, 6.5);
        SectDisciple b = npc(h, new Mini(emptyFile()), "yul_su", 14.6, 2.0, 6.5);
        SectDisciple in = npc(h, new Mini(emptyFile()), "gyeong_on", 10.5, 2.0, 6.5);
        Vec3 outA = h.absoluteVec(new Vec3(4.5, 2.0, 10.5));
        Vec3 outB = h.absoluteVec(new Vec3(5.5, 2.0, 11.5));
        Vec3 inside = h.absoluteVec(new Vec3(14.5, 2.0, 6.5));
        h.onEachTick(() -> {
            a.walking().walk(outA, 0.9D);
            b.walking().walk(outB, 0.9D);
            in.walking().walk(inside, 0.9D);
        });
        h.succeedWhen(() -> {
            h.assertTrue(a.position().distanceToSqr(outA) < 1.0D, "yul_do: " + a.position() + " " + a.walking().describe());
            h.assertTrue(b.position().distanceToSqr(outB) < 1.0D, "yul_su: " + b.position() + " " + b.walking().describe());
            h.assertTrue(in.position().distanceToSqr(inside) < 1.0D, "gyeong_on: " + in.position() + " " + in.walking().describe());
        });
    }
}
