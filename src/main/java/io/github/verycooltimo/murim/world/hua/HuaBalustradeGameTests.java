package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTest of the diagonal balustrade (author 06.10): a staircase line connects diagonally and not cardinally, an L-corner
 * stays an L, removing the middle block or closing a cardinal gap updates the diagonal neighbours. In {@code small_floor}
 * the dirt and grass sit at relative y 1..2 (helper coordinates start one below the structure), so the row stands at y 3.
 * API: reference/minecraft-src/net/minecraft/world/level/block/Block.java#updateFromNeighbourShapes
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HuaBalustradeGameTests {

    private HuaBalustradeGameTests() {
    }

    /** Places a balustrade the way placement would see it: the state is fitted to its neighbours. */
    private static void place(GameTestHelper helper, BlockPos rel) {
        BlockPos abs = helper.absolutePos(rel);
        BlockState state = Block.updateFromNeighbourShapes(ModBlocks.HUA_BALUSTRADE.get().defaultBlockState(), helper.getLevel(), abs);
        helper.getLevel().setBlock(abs, state, Block.UPDATE_ALL);
    }

    private static BlockState at(GameTestHelper helper, BlockPos rel) {
        return helper.getBlockState(rel);
    }

    private static boolean cardinal(BlockState s) {
        return s.getValue(FenceBlock.NORTH) || s.getValue(FenceBlock.EAST) || s.getValue(FenceBlock.SOUTH) || s.getValue(FenceBlock.WEST);
    }

    private static String diag(BlockState s) {
        StringBuilder b = new StringBuilder();
        for (HuaBalustradeBlock.Diagonal d : HuaBalustradeBlock.Diagonal.values()) {
            if (s.getValue(d.property)) {
                b.append(d.name()).append(' ');
            }
        }
        return b.toString().trim();
    }

    private static void expect(GameTestHelper helper, BlockPos rel, String diagonals, boolean cardinal) {
        BlockState s = at(helper, rel);
        helper.assertTrue(s.getBlock() instanceof HuaBalustradeBlock, "no balustrade at " + rel);
        helper.assertTrue(diagonals.equals(diag(s)), rel + ": diagonals [" + diag(s) + "], expected [" + diagonals + "]; state " + s
                + "; E " + at(helper, rel.east()) + "; S " + at(helper, rel.south()));
        helper.assertTrue(cardinal(s) == cardinal, rel + ": cardinal arms " + cardinal(s) + ", expected " + cardinal);
    }

    /** Four blocks in a staircase line (x+1, z+1 each): diagonal arms only; middle removed and closed gap update neighbours. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void staircaseLineConnectsDiagonally(GameTestHelper helper) {
        BlockPos a = new BlockPos(0, 3, 0);
        BlockPos b = new BlockPos(1, 3, 1);
        BlockPos c = new BlockPos(2, 3, 2);
        BlockPos d = new BlockPos(3, 3, 3);
        for (BlockPos p : new BlockPos[] {a, b, c, d}) {
            place(helper, p);
        }
        expect(helper, a, "SOUTH_EAST", false);
        expect(helper, b, "SOUTH_EAST NORTH_WEST", false);
        expect(helper, c, "SOUTH_EAST NORTH_WEST", false);
        expect(helper, d, "NORTH_WEST", false);

        // Removing the middle: both diagonal neighbours drop the arm that pointed at it.
        helper.getLevel().setBlock(helper.absolutePos(b), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        expect(helper, a, "", false);
        expect(helper, c, "SOUTH_EAST", false);

        // Putting it back reconnects; then a balustrade in the cardinal cell between b and c turns that pair into an L.
        place(helper, b);
        expect(helper, b, "SOUTH_EAST NORTH_WEST", false);
        place(helper, new BlockPos(2, 3, 1));
        expect(helper, b, "NORTH_WEST", true);
        expect(helper, c, "SOUTH_EAST", true);
        expect(helper, a, "SOUTH_EAST", false);
        helper.succeed();
    }

    /** An L-corner: the two ends are diagonal neighbours but connect through the corner, so no diagonal arm appears. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void lCornerStaysCardinal(GameTestHelper helper) {
        BlockPos a = new BlockPos(0, 3, 0);
        BlockPos corner = new BlockPos(1, 3, 0);
        BlockPos b = new BlockPos(1, 3, 1);
        place(helper, a);
        place(helper, b);
        expect(helper, a, "SOUTH_EAST", false);
        place(helper, corner);
        expect(helper, a, "", true);
        expect(helper, corner, "", true);
        expect(helper, b, "", true);
        helper.succeed();
    }

    private static void expectSlope(GameTestHelper helper, BlockPos rel, HuaBalustradeBlock.Slope up, HuaBalustradeBlock.Slope down,
                                    boolean north) {
        BlockState s = at(helper, rel);
        helper.assertTrue(s.getBlock() instanceof HuaBalustradeBlock, "no balustrade at " + rel);
        helper.assertTrue(s.getValue(HuaBalustradeBlock.SLOPE_UP) == up && s.getValue(HuaBalustradeBlock.SLOPE_DOWN) == down
                && s.getValue(FenceBlock.NORTH) == north, rel + ": expected up " + up + " down " + down + " north " + north + "; state " + s);
    }

    /**
     * A 1:1 stair line of four, each one lower to the south: rising arms point north, falling arms south, no flat arms.
     * A plain solid block on the same level (the wall the upper one stands on) does not stop the slope; a balustrade
     * there does: the flat arm wins.
     */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void stairLineSlopes(GameTestHelper helper) {
        HuaBalustradeBlock.Slope none = HuaBalustradeBlock.Slope.NONE;
        HuaBalustradeBlock.Slope n = HuaBalustradeBlock.Slope.NORTH;
        HuaBalustradeBlock.Slope s = HuaBalustradeBlock.Slope.SOUTH;
        BlockPos top = new BlockPos(1, 6, 0);
        BlockPos second = new BlockPos(1, 5, 1);
        BlockPos third = new BlockPos(1, 4, 2);
        BlockPos bottom = new BlockPos(1, 3, 3);
        for (BlockPos p : new BlockPos[] {top, second, third, bottom}) {
            place(helper, p);
        }
        expectSlope(helper, top, none, s, false);
        expectSlope(helper, second, n, s, false);
        expectSlope(helper, third, n, s, false);
        expectSlope(helper, bottom, n, none, false);
        helper.assertTrue(!cardinal(at(helper, second)) && diag(at(helper, second)).isEmpty(), "second has flat or diagonal arms");

        // Solid wall under the top block, level with the second: the slope stays, the flat arm into the wall is off.
        helper.setBlock(new BlockPos(1, 5, 0), Blocks.STONE);
        expectSlope(helper, second, n, s, false);
        expectSlope(helper, top, none, s, false);

        // A balustrade there instead: the flat side wins, both ends of that slope drop it.
        place(helper, new BlockPos(1, 5, 0));
        expectSlope(helper, second, none, s, true);
        expectSlope(helper, top, none, none, false);

        // Removing the second block clears the slopes that met it.
        helper.getLevel().setBlock(helper.absolutePos(second), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        expectSlope(helper, third, none, s, false);
        helper.succeed();
    }
}
