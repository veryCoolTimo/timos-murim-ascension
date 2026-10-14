package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModBlocks;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the plum trees (world/hua/PlumTree): every variant grows on the 34×34 camp floor with a plum
 * log at the foot, all its wood joined to the foot (log steps, no floating limbs), a crown of blossom above the
 * trunk and the expected height; a young sapling grows into a tree with bone meal. Trees are cleared afterwards
 * so they never spill into neighbouring tests.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class PlumTreeGameTests {

    private static final int R = 15;

    private PlumTreeGameTests() {
    }

    @GameTest(template = "camp_floor", timeoutTicks = 40)
    public static void courtyardPlumGrows(GameTestHelper helper) {
        check(helper, PlumTree.Variant.COURTYARD, 9, 13);
    }

    @GameTest(template = "camp_floor", timeoutTicks = 40)
    public static void cliffPlumGrows(GameTestHelper helper) {
        check(helper, PlumTree.Variant.CLIFF, 7, 13);
    }

    @GameTest(template = "camp_floor", timeoutTicks = 40)
    public static void youngPlumGrows(GameTestHelper helper) {
        check(helper, PlumTree.Variant.YOUNG, 4, 8);
    }

    @GameTest(template = "camp_floor", timeoutTicks = 40)
    public static void youngSaplingGrowsWithBoneMeal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos foot = foot(helper);
        openLid(level, foot);
        level.setBlock(foot.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        BlockState sapling = ModBlocks.YOUNG_PLUM_SAPLING.get().defaultBlockState();
        level.setBlock(foot, sapling, 3);
        helper.assertTrue(sapling.canSurvive(level, foot), "sapling cannot stand on grass");
        PlumSaplingBlock block = ModBlocks.YOUNG_PLUM_SAPLING.get();
        block.advance(level, foot, level.getBlockState(foot), level.random);
        helper.assertTrue(level.getBlockState(foot).getValue(PlumSaplingBlock.STAGE) == 1, "first bone meal: stage 1");
        boolean grew = block.advance(level, foot, level.getBlockState(foot), level.random);
        helper.assertTrue(grew && level.getBlockState(foot).is(ModBlocks.PLUM_LOG.get()), "sapling did not become a tree");
        clear(level, foot);
        helper.succeed();
    }

    private static void check(GameTestHelper helper, PlumTree.Variant variant, int minHeight, int maxHeight) {
        ServerLevel level = helper.getLevel();
        BlockPos foot = foot(helper);
        openLid(level, foot);
        for (long seed = 1; seed <= 4; seed++) {
            clear(level, foot);
            boolean ok = PlumTree.grow(level, foot, variant, variant.defaultPalette(), seed * 7919, Direction.EAST, 2);
            helper.assertTrue(ok, variant + ": foot blocked");
            helper.assertTrue(level.getBlockState(foot).is(ModBlocks.PLUM_LOG.get()), variant + ": no plum log at the foot");
            Set<BlockPos> wood = new HashSet<>();
            int blossom = 0;
            int top = 0;
            for (BlockPos p : BlockPos.betweenClosed(foot.offset(-R, -1, -R), foot.offset(R, 18, R))) {
                BlockState s = level.getBlockState(p);
                if (PlumTree.isPlumWood(s)) {
                    wood.add(p.immutable());
                } else if (s.getBlock() instanceof PlumBlossomBlock) {
                    blossom++;
                    top = Math.max(top, p.getY() - foot.getY() + 1);
                }
            }
            helper.assertTrue(wood.size() >= 4, variant + " seed " + seed + ": only " + wood.size() + " wood blocks");
            helper.assertTrue(blossom >= 15, variant + " seed " + seed + ": only " + blossom + " blossoms");
            helper.assertTrue(top >= minHeight && top <= maxHeight + 1,
                    variant + " seed " + seed + ": height " + top + " outside " + minHeight + ".." + maxHeight);
            // Every limb joins the foot through faces (diagonal log steps leave no corner-only joints).
            Set<BlockPos> seen = new HashSet<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(foot);
            seen.add(foot);
            while (!queue.isEmpty()) {
                BlockPos p = queue.poll();
                for (Direction d : Direction.values()) {
                    BlockPos n = p.relative(d);
                    if (wood.contains(n) && seen.add(n)) {
                        queue.add(n);
                    }
                }
            }
            helper.assertTrue(seen.size() == wood.size(),
                    variant + " seed " + seed + ": " + (wood.size() - seen.size()) + " wood blocks not joined to the trunk: "
                            + wood.stream().filter(w -> !seen.contains(w)).map(w -> w.subtract(foot).toShortString() + " "
                            + level.getBlockState(w.below()).getBlock()).toList());
        }
        clear(level, foot);
        helper.succeed();
    }

    /** The test frame puts a barrier lid over the 10-high camp floor; the taller trees need room above it. */
    private static void openLid(ServerLevel level, BlockPos foot) {
        for (BlockPos p : BlockPos.betweenClosed(foot.offset(-R, 1, -R), foot.offset(R, 18, R))) {
            if (level.getBlockState(p).is(Blocks.BARRIER)) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    /** First air above the floor at the centre of the camp floor. */
    private static BlockPos foot(GameTestHelper helper) {
        BlockPos p = helper.absolutePos(new BlockPos(17, 1, 17));
        ServerLevel level = helper.getLevel();
        while (!level.getBlockState(p).isAir() && p.getY() < helper.absolutePos(BlockPos.ZERO).getY() + 9) {
            p = p.above();
        }
        return p;
    }

    private static void clear(ServerLevel level, BlockPos foot) {
        for (BlockPos p : BlockPos.betweenClosed(foot.offset(-R, 0, -R), foot.offset(R, 18, R))) {
            BlockState s = level.getBlockState(p);
            if (PlumTree.isPlumWood(s) || s.getBlock() instanceof PlumBlossomBlock || s.is(Blocks.PINK_PETALS)
                    || s.getBlock() instanceof PlumSaplingBlock) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }
}
