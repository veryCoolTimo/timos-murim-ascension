package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModBlocks;
import io.github.verycooltimo.murim.dev.DevRegionCommand;
import io.github.verycooltimo.murim.decor.HallDecor;
import io.github.verycooltimo.murim.decor.ThinWallBlock;
import net.minecraft.core.Direction;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercises the real command transaction on a small-height pad, without changing the site's SavedData. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HuaRegradeGameTests {
    private HuaRegradeGameTests() { }

    @GameTest(template = "hua_regrade", batch = "hua_regrade", timeoutTicks = 100)
    public static void dryRunPreservesWallAndGateAndContinuesCheckerboard(GameTestHelper helper) throws java.io.IOException {
        var level = helper.getLevel();
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        int top = corner.getY() + 5;
        MountHuaSite site = new MountHuaSite(corner.getX() + 38, corner.getZ() + 32, 70, 0, level.getSeed());
        var zone = new MountHuaPlan.Zone("training", "Synthetic pad", 6, 11.5, 48, 47,
                site.nominal(top), false, 0, 0);
        // Ensure all chunks are loaded BEFORE planning. The production command never loads them itself.
        loadArea(level, site, zone);
        for (int u = -18; u < 30; u++) for (int v = -12; v < 20; v++) {
            level.setBlock(at(site, u, top - 1, v), Blocks.DIRT.defaultBlockState(), 2);
            level.setBlock(at(site, u, top, v), ((u + v) & 1) == 0
                    ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.BRICKS.defaultBlockState(), 2);
        }
        var wall = HallDecor.THIN_WALL_PLASTER.get().defaultBlockState()
                .setValue(ThinWallBlock.FACING, Direction.SOUTH).setValue(ThinWallBlock.CENTER, true)
                .setValue(ThinWallBlock.EAST, true).setValue(ThinWallBlock.WEST, true);
        for (int u = -16; u < 28; u++) level.setBlock(at(site, u, top + 1, 19), wall, 2 | 16);
        for (int v = -10; v < 19; v++) {
            level.setBlock(at(site, -18, top + 1, v), wall, 2 | 16);
            level.setBlock(at(site, 29, top + 1, v), wall, 2 | 16);
        }
        level.setBlock(at(site, 4, top + 2, 19), wall.setValue(ThinWallBlock.CENTER, false), 2 | 16);
        level.setBlock(at(site, 4, top + 3, 19), Blocks.LANTERN.defaultBlockState(), 2 | 16);
        BlockPos gate = at(site, 8, top + 2, -12);
        level.setBlock(gate, Blocks.RED_CONCRETE.defaultBlockState(), 2);
        BlockPos outside = at(site, -25, top + 1, 10);
        level.setBlock(outside, Blocks.LANTERN.defaultBlockState(), 2);
        var originalFloor = level.getBlockState(at(site, 4, top, 4));
        HuaRegrade plan = HuaRegrade.plan(level, site, List.of(zone), (x, z) -> top);
        helper.assertTrue(level.getBlockState(at(site, 4, top, 26)).isAir(), "dry-run wrote the world");
        helper.assertTrue(level.getBlockState(at(site, 4, top + 1, 19)).equals(wall), "dry-run moved a wall");
        helper.assertTrue(plan.get(at(site, 4, top + 1, 34)).equals(wall), "rear wall state lost");
        helper.assertTrue(plan.get(at(site, 4, top + 2, 34)).equals(wall.setValue(ThinWallBlock.CENTER, false)),
                "wall column composition lost");
        helper.assertTrue(plan.get(at(site, 4, top + 3, 34)).is(Blocks.LANTERN), "wall lantern not moved");
        helper.assertTrue(plan.get(at(site, 4, top + 1, 19)).isAir(), "old inner wall remains");
        helper.assertTrue(plan.get(at(site, -18, top + 1, 27)).equals(wall), "side wall not extended");
        for (int u = 2; u < 7; u++) for (int v = 25; v < 30; v++) {
            helper.assertTrue(plan.get(at(site, u, top, v)).is(((u + v) & 1) == 0 ? Blocks.STONE_BRICKS : Blocks.BRICKS),
                    "paving checkerboard/phase lost at " + u + "," + v);
        }
        helper.assertTrue(plan.get(gate).is(Blocks.RED_CONCRETE), "north gate overwritten");
        helper.assertTrue(plan.get(outside).is(Blocks.LANTERN), "outside building overwritten");
        helper.assertTrue(plan.get(at(site, 4, top, 4)).equals(originalFloor), "unchanged surface overwritten");
        helper.assertTrue(plan.edits().entrySet().stream().anyMatch(e -> HuaRegrade.natural(e.getValue())
                && e.getKey().getY() < top), "grading did not use natural terrain");
        var before = plan.edits().keySet().stream().collect(java.util.stream.Collectors.toMap(p -> p, level::getBlockState));
        String name = "hua_regrade_test_" + Long.toUnsignedString(System.nanoTime());
        var box = plan.backupBounds();
        java.nio.file.Path backup = DevRegionCommand.saveRegion(level, name, box);
        try {
            plan.apply();
            helper.assertTrue(level.getBlockState(at(site, 4, top + 1, 34)).equals(wall), "apply did not move wall");
            helper.assertTrue(level.getBlockState(gate).is(Blocks.RED_CONCRETE), "apply destroyed gate");
            HuaRegrade repeated = HuaRegrade.plan(level, site, List.of(zone), (x, z) -> top);
            helper.assertTrue(repeated.moves().isEmpty(), "already moved walls scheduled again");
            helper.assertTrue(repeated.get(at(site, 4, top + 2, 34)).equals(wall.setValue(ThinWallBlock.CENTER, false))
                    && repeated.get(at(site, 4, top + 3, 34)).is(Blocks.LANTERN), "second regrade changed rear column");
            repeated.apply();
            DevRegionCommand.restoreRegion(level, name, new BlockPos(box.minX(), box.minY(), box.minZ()), Rotation.NONE, false);
            for (var entry : before.entrySet()) {
                helper.assertTrue(level.getBlockState(entry.getKey()).equals(entry.getValue()),
                        "exact undo differs at " + entry.getKey());
            }
        } finally {
            java.nio.file.Files.deleteIfExists(backup);
        }
        helper.succeed();
    }

    @GameTest(template = "hua_regrade", batch = "hua_regrade", timeoutTicks = 100)
    public static void authorMovedRearWallIsAlreadyDone(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        int top = corner.getY() + 5;
        MountHuaSite site = new MountHuaSite(corner.getX() + 38, corner.getZ() + 32, 70, 0, level.getSeed());
        var zone = new MountHuaPlan.Zone("training", "Synthetic pad", 6, 11.5, 48, 47,
                site.nominal(top), false, 0, 0);
        loadArea(level, site, zone);
        var wall = HallDecor.THIN_WALL_PLASTER.get().defaultBlockState();
        for (int u = -18; u < 30; u++) for (int v = -12; v < 35; v++) {
            level.setBlock(at(site, u, top, v), Blocks.DIRT.defaultBlockState(), 2);
        }
        for (int u = -16; u < 28; u++) level.setBlock(at(site, u, top + 1, 34), wall, 2 | 16);
        for (int v = -10; v < 34; v++) {
            level.setBlock(at(site, -18, top + 1, v), wall, 2 | 16);
            level.setBlock(at(site, 29, top + 1, v), wall, 2 | 16);
        }
        // A different composition in the destination used to collide with a misidentified side tip.
        level.setBlock(at(site, -18, top + 2, 34), Blocks.RED_CONCRETE.defaultBlockState(), 2);
        var plan = HuaRegrade.plan(level, site, List.of(zone), (x, z) -> top);
        helper.assertTrue(plan.moves().isEmpty(), "author's completed wall moved again");
        helper.assertTrue(plan.get(at(site, -18, top + 2, 34)).is(Blocks.RED_CONCRETE), "author's column altered");
        helper.succeed();
    }

    @GameTest(template = "hua_regrade", batch = "hua_regrade", timeoutTicks = 100)
    public static void campExpandsOldPavingAndCanRegradeAgain(GameTestHelper helper) {
        checkExpandedPad(helper, "camp");
    }

    @GameTest(template = "hua_regrade", batch = "hua_regrade", timeoutTicks = 100)
    public static void alchemyExpandsOldPavingAndCanRegradeAgain(GameTestHelper helper) {
        checkExpandedPad(helper, "alchemy");
    }

    private static void checkExpandedPad(GameTestHelper helper, String id) {
        var level = helper.getLevel();
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        int top = corner.getY() + 5;
        var actual = MountHuaPlan.ZONES.stream().filter(z -> z.id().equals(id)).findFirst().orElseThrow();
        int minU = (int) Math.floor(actual.u() - actual.width() / 2.0);
        int minV = (int) Math.floor(actual.v() - actual.depth() / 2.0);
        MountHuaSite site = new MountHuaSite(corner.getX() + 20 - minU, corner.getZ() + 20 - minV,
                70, 0, level.getSeed());
        var zone = new MountHuaPlan.Zone(id, "Synthetic pad", actual.u(), actual.v(), actual.width(), actual.depth(),
                site.nominal(top), false, 0, 0);
        loadArea(level, site, zone);
        int[] old = id.equals("camp") ? new int[] {-81, 10, -44, 57} : new int[] {-40, 54, -25, 65};
        for (int u = old[0]; u <= old[2]; u++) for (int v = old[1]; v <= old[3]; v++) {
            level.setBlock(at(site, u, top - 1, v), Blocks.DIRT.defaultBlockState(), 2);
            level.setBlock(at(site, u, top, v), Blocks.STONE_BRICKS.defaultBlockState(), 2);
        }
        BlockPos oldMark = at(site, -70, top, 32);
        BlockPos raisedMark = at(site, -71, top + 1, 32);
        BlockPos wallFooting = at(site, -72, top, 32);
        if (id.equals("camp")) {
            level.setBlock(oldMark, Blocks.WHITE_CONCRETE.defaultBlockState(), 2);
            level.setBlock(raisedMark, Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 2);
            level.setBlock(wallFooting, Blocks.WHITE_CONCRETE.defaultBlockState(), 2);
            level.setBlock(wallFooting.above(), HallDecor.PLASTER.get().defaultBlockState(), 2);
        }
        BlockPos extension = at(site, minU + 3, top, minV + 3);
        var plan = HuaRegrade.plan(level, site, List.of(zone), (x, z) -> top);
        helper.assertTrue(plan.get(extension).is(Blocks.STONE_BRICKS), id + " paving did not extend to new pad");
        if (id.equals("camp")) {
            helper.assertTrue(plan.get(oldMark).is(Blocks.STONE_BRICKS), "old white footprint survives in camp floor");
            helper.assertTrue(plan.get(raisedMark).isAir(), "raised footprint survives above camp floor");
            helper.assertTrue(plan.get(wallFooting).is(Blocks.WHITE_CONCRETE)
                    && plan.get(wallFooting.above()).is(HallDecor.PLASTER.get()), "concrete wall footing was treated as a marker");
            helper.assertTrue(plan.cleanedCampMarkers() == 2, "unexpected marker cleanup scope");
            helper.assertTrue(level.getBlockState(oldMark).is(Blocks.WHITE_CONCRETE), "dry-run erased a mark");
        }
        plan.apply();
        var repeated = HuaRegrade.plan(level, site, List.of(zone), (x, z) -> top);
        helper.assertTrue(repeated.get(extension).is(Blocks.STONE_BRICKS) && repeated.moves().isEmpty(),
                id + " repeat regrade damaged paving");
        helper.assertTrue(repeated.edits().keySet().stream().noneMatch(p -> p.getY() <= top - 2),
                id + " repeat regrade kept moving the terrain margin");
        if (id.equals("camp")) {
            helper.assertTrue(repeated.cleanedCampMarkers() == 0 && repeated.get(oldMark).is(Blocks.STONE_BRICKS)
                    && repeated.get(raisedMark).isAir(), "repeat regrade regenerated footprint markings");
        }
        helper.succeed();
    }

    @GameTest(template = "hua_regrade", batch = "hua_regrade", timeoutTicks = 20)
    public static void pavingPatternKeepsPhaseInEveryRotation(GameTestHelper helper) {
        java.util.Map<Long, net.minecraft.world.level.block.state.BlockState> sample = new java.util.HashMap<>();
        for (int u = -5; u < 3; u++) for (int v = -7; v < 1; v++) {
            sample.put(BlockPos.asLong(u, 0, v), ((u + v) & 1) == 0
                    ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.BRICKS.defaultBlockState());
        }
        var pattern = HuaRegrade.PavingPattern.from(sample);
        for (int rotation = 0; rotation < 4; rotation++) {
            MountHuaSite site = MountHuaSite.placement(100, 100, 70, rotation);
            for (int u = -20; u < 20; u++) for (int v = 4; v < 12; v++) {
                int[] w = site.toWorld(u + 0.5, v + 0.5);
                int ru = (int) Math.floor(site.localU(w[0] + 0.5, w[1] + 0.5));
                int rv = (int) Math.floor(site.localV(w[0] + 0.5, w[1] + 0.5));
                helper.assertTrue(pattern.at(ru, rv).is(((u + v) & 1) == 0 ? Blocks.STONE_BRICKS : Blocks.BRICKS),
                        "paving phase changed after rotation " + Rotation.values()[rotation]);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "hua_regrade", batch = "hua_regrade", timeoutTicks = 100)
    public static void nineWideFlightsCarveEveryLaneWithOuterStoneCurbs(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        MountHuaSite site = new MountHuaSite(corner.getX() + 24, corner.getZ() - 18, 70, 0, level.getSeed());
        var arch = MountHuaPlan.ZONES.stream().filter(z -> z.id().equals("arch")).findFirst().orElseThrow();
        loadArea(level, site, arch);
        // Existing terrain follows the plan, except a three-high old riser across both flights.
        // The unchanged-height kerb must also be refreshed by the real regrade transaction.
        var flights = java.util.Arrays.stream(MountHuaPlan.PATHS)
                .filter(p -> MountHuaPlan.pathWidth(p) == 9 && p[0] == arch.u() && p[5] > p[2]).toList();
        int front = (int) flights.getFirst()[1], rear = (int) flights.getLast()[4];
        for (int u = -18; u <= 34; u++) for (int v = front - 3; v <= rear + 3; v++) {
            int top = (int) Math.round(site.worldY(site.shape().height(u + 0.5, v + 0.5)));
            final int cu = u, cv = v;
            if (flights.stream().anyMatch(p -> MountHuaShape.pathAt(p, cu + 0.5, cv + 0.5)[0]
                    <= MountHuaPlan.pathHalfWidth(p))) top += 3;
            for (int y = top - 4; y <= top; y++) level.setBlock(at(site, u, y, v), Blocks.STONE.defaultBlockState(), 2);
        }
        int landing = (int) Math.round(site.worldY(arch.y()));
        int edge = (int) Math.floor(arch.u() - arch.width() / 2.0);
        int middle = (int) arch.v();
        BlockPos authorBlock = at(site, edge, landing, middle - 2);
        level.setBlock(authorBlock, HallDecor.PLASTER.get().defaultBlockState(), 2);
        HuaRegrade plan = HuaRegrade.plan(level, site, List.of(arch));
        for (double[] path : flights) {
            helper.assertTrue(MountHuaPlan.pathWidth(path) == 9, "flight lost its width override");
            int lanes = 0;
            for (int u = (int) path[0] - 8; u <= (int) path[0] + 8; u++) {
                if (MountHuaShape.pathAt(path, u + 0.5, path[1] + 0.5)[0] > MountHuaPlan.pathHalfWidth(path)) continue;
                lanes++;
                int previous = (int) Math.round(site.worldY(path[2]));
                for (int v = (int) path[1]; v < path[4]; v++) {
                    double[] q = MountHuaShape.pathAt(path, u + 0.5, v + 0.5);
                    int y = (int) Math.round(site.worldY(q[1]));
                    helper.assertTrue(Math.abs(y - previous) <= 1, "flight rises more than one block");
                    helper.assertTrue(site.shape().height(u + 0.5, v + 0.5) == q[1], "lane not carved to stair level");
                    var state = plan.get(at(site, u, y, v));
                    helper.assertTrue(state.is(ModBlocks.POLISHED_HUA_GRANITE_STAIRS.get()),
                            "missing stone tread in lane " + u + " at " + v);
                    for (int k = 1; k <= 4; k++) helper.assertTrue(plan.get(at(site, u, y + k, v)).isAir(), "blocked walking lane");
                    helper.assertTrue(!level.getBlockState(at(site, u, y, v)).is(ModBlocks.POLISHED_HUA_GRANITE_STAIRS.get()), "dry-run changed the world");
                    previous = y;
                }
            }
            helper.assertTrue(lanes >= 9 && lanes <= 10, "flight is not nine/ten lanes wide: " + lanes);
            for (int u = (int) path[0] - 8; u <= (int) path[0] + 8; u++) for (int v = (int) path[1]; v < path[4]; v++) {
                if (!MountHuaShape.pathCheek(path, u + 0.5, v + 0.5)) continue;
                int y = (int) Math.round(site.worldY(MountHuaShape.pathAt(path, u + 0.5, v + 0.5)[1]));
                helper.assertTrue(plan.get(at(site, u, y + 1, v))
                        .is(ModBlocks.POLISHED_HUA_GRANITE.get()), "curb is not one block high");
                helper.assertTrue(plan.get(at(site, u, y + 2, v)).isAir(), "curb grew a balustrade");
            }
        }
        helper.assertTrue(plan.get(at(site, edge, landing, middle + 2)).is(HuaGround.HUA_EDGE_STONE.get()), "unchanged kerb not cleaned");
        helper.assertTrue(plan.get(authorBlock).is(HallDecor.PLASTER.get()), "author's block replaced by kerb");
        helper.succeed();
    }

    @GameTest(template = "hua_regrade", batch = "hua_regrade", timeoutTicks = 100)
    public static void accessLandingRefreshesThreeLanesWithoutChangingItsHeight(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        var zone = MountHuaPlan.ZONES.stream().filter(z -> z.id().equals("alchemy")).findFirst().orElseThrow();
        var box = io.github.verycooltimo.murim.sect.SectLayoutPlan.BOXES.stream()
                .filter(b -> b.id().equals("alchemy")).findFirst().orElseThrow();
        double[] approach = java.util.Arrays.stream(MountHuaPlan.PATHS)
                .filter(p -> p[0] == p[3] && p[4] == box.minV() + 0.5 && p[3] >= box.minU()
                        && p[3] < box.minU() + box.width() && p[2] == zone.y() && p[5] == p[2])
                .findFirst().orElseThrow();
        MountHuaSite site = new MountHuaSite(corner.getX() + 60, corner.getZ() - 8, 70, 0, level.getSeed());
        loadArea(level, site, zone);
        int top = (int) Math.round(site.worldY(zone.y()));
        int centre = (int) Math.floor(approach[0]), start = (int) approach[1] + 1;
        for (int u = centre - 1; u <= centre + 1; u++) for (int v = start; v < start + 3; v++) {
            level.setBlock(at(site, u, top - 1, v), Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(at(site, u, top, v), ModBlocks.POLISHED_HUA_GRANITE.get().defaultBlockState(), 2);
            for (int k = 1; k <= 3; k++) level.setBlock(at(site, u, top + k, v), Blocks.OAK_LEAVES.defaultBlockState(), 2);
        }
        BlockPos wall = at(site, centre + 2, top + 1, start);
        level.setBlock(wall, HallDecor.PLASTER.get().defaultBlockState(), 2);
        var plan = HuaRegrade.plan(level, site, List.of(zone));
        for (int u = centre - 1; u <= centre + 1; u++) for (int v = start; v < start + 3; v++) {
            helper.assertTrue(plan.get(at(site, u, top, v)).is(ModBlocks.POLISHED_HUA_GRANITE.get()), "landing floor moved");
            for (int k = 1; k <= 3; k++) helper.assertTrue(plan.get(at(site, u, top + k, v)).isAir(), "access headroom blocked");
        }
        helper.assertTrue(plan.get(wall).is(HallDecor.PLASTER.get()), "access erased an authored wall");
        helper.assertTrue(level.getBlockState(at(site, centre, top + 1, start)).is(Blocks.OAK_LEAVES), "dry-run wrote world");
        plan.apply();
        var repeated = HuaRegrade.plan(level, site, List.of(zone));
        helper.assertTrue(repeated.get(at(site, centre, top + 1, start)).isAir()
                && repeated.get(wall).is(HallDecor.PLASTER.get()), "repeat grading damaged landing or wall");
        helper.succeed();
    }

    @GameTest(template = "hua_regrade", batch = "hua_regrade", timeoutTicks = 100)
    public static void unchangedPadClearsPersistentCrownsButKeepsBuildingsAndOutsideTrees(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        int top = corner.getY() + 5;
        MountHuaSite site = new MountHuaSite(corner.getX() + 55, corner.getZ() - 10, 70, 0, level.getSeed());
        var zone = new MountHuaPlan.Zone("alchemy", "Synthetic foliage pad", -47, 46, 26, 22,
                site.nominal(top), false, 0, 0);
        loadArea(level, site, zone);
        BlockPos tree = at(site, -47, top + 1, 46);
        BlockPos crown = tree.above(14);
        BlockPos house = tree.east(3);
        BlockPos outside = at(site, -63, top + 12, 46);
        var leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
        for (int u = -60; u < -34; u++) for (int v = 35; v < 57; v++) {
            level.setBlock(at(site, u, top, v), Blocks.DIRT.defaultBlockState(), 2);
        }
        level.setBlock(tree, Blocks.OAK_LOG.defaultBlockState(), 2);
        level.setBlock(crown, leaf, 2);
        var authored = java.util.Map.of(
                house, HallDecor.PLASTER.get().defaultBlockState(),
                house.east(), Blocks.OAK_LOG.defaultBlockState(),
                house.east(2), Blocks.STRIPPED_OAK_LOG.defaultBlockState(),
                house.east(3), ModBlocks.PLUM_WOOD.get().defaultBlockState(),
                house.east(4), Blocks.OAK_PLANKS.defaultBlockState(),
                house.east(5), ModBlocks.RED_BEAM.get().defaultBlockState());
        authored.forEach((p, state) -> {
            level.setBlock(p, state, 2);
            // A nearby canopy must not turn a log wall or wooden beam into a natural tree.
            level.setBlock(p.above(2), leaf, 2);
            helper.assertTrue(!HuaRegrade.natural(state), "construction timber classified as foliage");
        });
        // Connected blocks (red beams) reshape as neighbours arrive, in map order; compare to the settled world.
        var buildings = new java.util.HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        authored.keySet().forEach(p -> buildings.put(p, level.getBlockState(p)));
        BlockPos temporaryCrown = crown.north();
        level.setBlock(temporaryCrown, Blocks.OAK_LEAVES.defaultBlockState(), 2);
        level.setBlock(outside, leaf, 2);
        level.setBlock(outside.below(12), Blocks.DIRT.defaultBlockState(), 2);
        var plan = HuaRegrade.plan(level, site, List.of(zone), (x, z) -> top);
        helper.assertTrue(plan.get(tree).isAir() && plan.get(crown).isAir(), "unchanged pad still contains trunk/crown");
        helper.assertTrue(plan.get(house).is(HallDecor.PLASTER.get()), "building lost during foliage cleanup");
        buildings.forEach((p, state) -> helper.assertTrue(plan.get(p).equals(state), "wooden building lost at " + p));
        helper.assertTrue(plan.get(temporaryCrown).isAir(), "non-persistent crown left on pad");
        helper.assertTrue(plan.get(outside).equals(leaf), "outside crown cleared");
        helper.assertTrue(level.getBlockState(crown).equals(leaf), "dry-run changed foliage");
        helper.assertTrue(!plan.treeFits(tree.getX() - 15, tree.getZ(), 13), "crown can intrude from outside pad");
        plan.apply();
        var repeated = HuaRegrade.plan(level, site, List.of(zone), (x, z) -> top);
        helper.assertTrue(repeated.get(crown).isAir() && repeated.get(house).is(HallDecor.PLASTER.get()), "repeat foliage cleanup changed building");
        buildings.forEach((p, state) -> helper.assertTrue(repeated.get(p).equals(state), "repeat cleanup damaged timber at " + p));
        helper.succeed();
    }

    @GameTest(template = "hua_regrade", batch = "hua_regrade", timeoutTicks = 100)
    public static void syntheticRockCutsAndFillsHaveAtMostThreeHighPadEdges(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        var zone = MountHuaPlan.ZONES.stream().filter(z -> z.id().equals("alchemy")).findFirst().orElseThrow();
        MountHuaSite site = new MountHuaSite(corner.getX() + 60, corner.getZ() - 8, 70, 0, level.getSeed());
        loadArea(level, site, zone);
        int x0 = (int) Math.floor(zone.u() - zone.width() / 2.0);
        int v0 = (int) Math.floor(zone.v() - zone.depth() / 2.0);
        int top = (int) Math.round(site.worldY(zone.y()));
        // One side is an old cut, the other an old fill; no architecture to pin either bank.
        for (int u = x0 - 3; u <= x0 + zone.width() + 3; u++) for (int v = v0 - 3; v <= v0 + zone.depth() + 3; v++) {
            int old = top + (u < zone.u() ? 8 : -8);
            for (int y = old - 3; y <= old; y++) level.setBlock(at(site, u, y, v), Blocks.STONE.defaultBlockState(), 2);
        }
        var plan = HuaRegrade.plan(level, site, List.of(zone));
        for (int u = x0; u < x0 + zone.width(); u++) for (int v = v0; v < v0 + zone.depth(); v++) {
            for (int[] d : new int[][] {{1,0},{-1,0},{0,1},{0,-1}}) {
                int nu = u + d[0], nv = v + d[1];
                if (nu >= x0 && nu < x0 + zone.width() && nv >= v0 && nv < v0 + zone.depth()) continue;
                int[] a = site.toWorld(u + 0.5, v + 0.5), b = site.toWorld(nu + 0.5, nv + 0.5);
                helper.assertTrue(Math.abs(plan.newTop(a[0], a[1]) - plan.newTop(b[0], b[1])) <= 3, "tall synthetic pad face");
                helper.assertTrue(plan.get(at(site, u, plan.newTop(a[0], a[1]), v)).blocksMotion(), "missing pad support");
            }
        }
        helper.succeed();
    }

    private static void loadArea(net.minecraft.server.level.ServerLevel level, MountHuaSite site, MountHuaPlan.Zone zone) {
        int margin = HuaRegrade.MARGIN + 2;
        int[] a = site.toWorld(Math.floor(zone.u() - zone.width() / 2.0) - margin,
                Math.floor(zone.v() - zone.depth() / 2.0) - margin);
        int[] b = site.toWorld(Math.ceil(zone.u() + zone.width() / 2.0) + margin,
                Math.ceil(zone.v() + zone.depth() / 2.0) + margin);
        for (int x = Math.min(a[0], b[0]) >> 4; x <= Math.max(a[0], b[0]) >> 4; x++) {
            for (int z = Math.min(a[1], b[1]) >> 4; z <= Math.max(a[1], b[1]) >> 4; z++) level.getChunk(x, z);
        }
    }

    private static BlockPos at(MountHuaSite site, int u, int y, int v) {
        int[] w = site.toWorld(u + 0.5, v + 0.5); return new BlockPos(w[0], y, w[1]);
    }
}
