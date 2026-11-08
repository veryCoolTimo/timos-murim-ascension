package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * What the vanilla features leave hanging over the reshaped mountain goes (HuaFloaters, docs/adr/0022): bamboo, a flower and snow on air, a vine
 * with nothing to hold, a tree whose logs touch no ground. What stands on ground stays: a rooted tree, bamboo, a tall flower, a beam without leaves
 * (a baked house's log), a persistent leaf, a plain block; and nothing outside the swept columns is touched.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HuaFloatersGameTests {

    private HuaFloatersGameTests() {
    }

    private static void box(ServerLevel level, BlockPos o, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    level.setBlock(o.offset(x, y, z), state, 2 | 16);
                }
            }
        }
    }

    private static void tree(ServerLevel level, BlockPos base, int trunk) {
        BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false);
        box(level, base, -2, trunk - 2, -2, 2, trunk, 2, leaf);
        box(level, base, 0, 0, 0, 0, trunk, 0, Blocks.OAK_LOG.defaultBlockState());
    }

    @GameTest(template = "sect_yard", batch = "hua_floaters", timeoutTicks = 100)
    public static void floatingVegetationGoesAndRootedStays(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO).above(40);
        // the ground: a stone slab y = 0 under x 0..59, z 0..9 (and nothing outside)
        box(level, o, 0, 0, 0, 59, 0, 9, Blocks.STONE.defaultBlockState());
        // floating: bamboo stack, a flower, snow, a vine on a lone leaf block
        box(level, o, 2, 6, 2, 2, 9, 2, Blocks.BAMBOO.defaultBlockState());
        level.setBlock(o.offset(4, 6, 2), Blocks.DANDELION.defaultBlockState(), 2 | 16);
        level.setBlock(o.offset(6, 6, 2), Blocks.SNOW.defaultBlockState(), 2 | 16);
        // a floating tree, trunk 5 high, 8 blocks over the ground; a vine hangs from it
        tree(level, o.offset(10, 8, 5), 5);
        level.setBlock(o.offset(13, 12, 5), Blocks.VINE.defaultBlockState().setValue(net.minecraft.world.level.block.VineBlock.WEST, true), 2 | 16);
        // rooted: bamboo on the ground, a tall flower, a tree on the ground, a leaf block of a tree touching the trunk top
        box(level, o, 20, 1, 2, 20, 4, 2, Blocks.BAMBOO.defaultBlockState());
        level.setBlock(o.offset(22, 1, 2), Blocks.ROSE_BUSH.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 2 | 16);
        level.setBlock(o.offset(22, 2, 2), Blocks.ROSE_BUSH.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2 | 16);
        tree(level, o.offset(30, 1, 5), 5);
        // never touched: a beam of a house (stripped log, no leaves) in the air, a persistent leaf in the air, a plain block in the air
        level.setBlock(o.offset(40, 6, 2), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(), 2 | 16);
        level.setBlock(o.offset(42, 6, 2), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), 2 | 16);
        level.setBlock(o.offset(44, 6, 2), Blocks.DIRT.defaultBlockState(), 2 | 16);
        // outside the swept columns (x >= 50): a floating bamboo and a floating tree stay
        level.setBlock(o.offset(52, 6, 2), Blocks.BAMBOO.defaultBlockState(), 2 | 16);
        tree(level, o.offset(55, 8, 6), 3);

        int removed = HuaFloaters.sweep(level, o.getX() - 2, o.getZ() - 6, o.getX() + 49, o.getZ() + 16,
                o.getX() - 8, o.getZ() - 12, o.getX() + 60, o.getZ() + 22, (x, z) -> x < o.getX() + 50);

        helper.assertTrue(level.getBlockState(o.offset(2, 6, 2)).isAir() && level.getBlockState(o.offset(2, 9, 2)).isAir(), "the floating bamboo stack is still there");
        helper.assertTrue(level.getBlockState(o.offset(4, 6, 2)).isAir(), "the floating flower is still there");
        helper.assertTrue(level.getBlockState(o.offset(6, 6, 2)).isAir(), "the floating snow is still there");
        helper.assertTrue(level.getBlockState(o.offset(10, 8, 5)).isAir() && level.getBlockState(o.offset(10, 13, 5)).isAir(), "the floating trunk is still there");
        helper.assertTrue(level.getBlockState(o.offset(9, 12, 4)).isAir() && level.getBlockState(o.offset(12, 11, 7)).isAir(), "the crown of the floating tree is still there");
        helper.assertTrue(level.getBlockState(o.offset(13, 12, 5)).isAir(), "the vine of the floating tree is still there");
        helper.assertTrue(level.getBlockState(o.offset(20, 1, 2)).is(Blocks.BAMBOO) && level.getBlockState(o.offset(20, 4, 2)).is(Blocks.BAMBOO), "rooted bamboo went");
        helper.assertTrue(level.getBlockState(o.offset(22, 1, 2)).is(Blocks.ROSE_BUSH) && level.getBlockState(o.offset(22, 2, 2)).is(Blocks.ROSE_BUSH), "a rooted tall flower went");
        helper.assertTrue(level.getBlockState(o.offset(30, 1, 5)).is(Blocks.OAK_LOG) && level.getBlockState(o.offset(30, 6, 5)).is(Blocks.OAK_LOG), "the rooted trunk went");
        helper.assertTrue(level.getBlockState(o.offset(28, 5, 3)).is(Blocks.OAK_LEAVES) && level.getBlockState(o.offset(32, 6, 7)).is(Blocks.OAK_LEAVES), "the crown of the rooted tree went");
        helper.assertTrue(level.getBlockState(o.offset(40, 6, 2)).is(Blocks.STRIPPED_SPRUCE_LOG), "a beam without leaves went");
        helper.assertTrue(level.getBlockState(o.offset(42, 6, 2)).is(Blocks.OAK_LEAVES), "a persistent leaf went");
        helper.assertTrue(level.getBlockState(o.offset(44, 6, 2)).is(Blocks.DIRT), "a plain block went");
        helper.assertTrue(level.getBlockState(o.offset(52, 6, 2)).is(Blocks.BAMBOO), "bamboo outside the swept columns went");
        helper.assertTrue(level.getBlockState(o.offset(55, 8, 6)).is(Blocks.OAK_LOG), "a tree outside the swept columns went");
        helper.assertTrue(removed >= 80, "removed only " + removed);
        helper.succeed();
    }
}
