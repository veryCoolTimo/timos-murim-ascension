package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the hall lights and lattice windows: lantern mounts picked from the clicked face, dropping when the
 * support goes, the two-block stone lantern's halves, light levels, window joins.
 *
 * <p>Placement goes through {@link BlockItem#place} with no player, clicking a face of a support block, so the
 * context's clicked position is the block in front of that face (reference/minecraft-src/net/minecraft/world/item/
 * context/BlockPlaceContext.java constructor: relative(face) unless the clicked block is replaceable); the horizontal
 * direction is then NORTH (UseOnContext#getHorizontalDirection with a null player). Block light is read through
 * {@link ServerLevel#getBrightness} once the light engine has caught up ({@code succeedWhen}).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HallLightsGameTests {

    private static final String FLOOR = "small_floor";

    private HallLightsGameTests() {
    }

    /** Clicks {@code face} of the block at {@code support} with {@code item}. */
    private static InteractionResult click(GameTestHelper helper, BlockItem item, BlockPos support, Direction face) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(support);
        Vec3 at = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(at, face, pos, false);
        return item.place(new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, new ItemStack(item), hit));
    }

    private static int blockLight(GameTestHelper helper, BlockPos rel) {
        return helper.getLevel().getBrightness(LightLayer.BLOCK, helper.absolutePos(rel));
    }

    private static void assertLantern(GameTestHelper helper, BlockPos rel, HangingLanternBlock.Attach attach, Direction facing) {
        BlockState s = helper.getBlockState(rel);
        helper.assertTrue(s.is(HallLights.ROUND_SILK_LANTERN.get()) || s.is(HallLights.SQUARE_WOOD_LANTERN.get()), "no lantern at " + rel + ": " + s);
        helper.assertTrue(s.getValue(HangingLanternBlock.ATTACH) == attach, "attach at " + rel + " want " + attach + ": " + s);
        if (facing != null) {
            helper.assertTrue(s.getValue(HangingLanternBlock.FACING) == facing, "facing at " + rel + " want " + facing + ": " + s);
        }
    }

    /** Under a block: hangs from the ceiling, gives light 15 there, drops when the block above is broken. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void lanternHangsAndDropsWithItsBeam(GameTestHelper helper) {
        BlockPos beam = new BlockPos(2, 4, 2);
        BlockPos at = new BlockPos(2, 3, 2);
        helper.setBlock(beam, Blocks.STRIPPED_SPRUCE_LOG);
        BlockItem item = HallLights.item(HallLights.ROUND_SILK_LANTERN);
        helper.assertTrue(click(helper, item, beam, Direction.DOWN).consumesAction(), "placement under the beam refused");
        assertLantern(helper, at, HangingLanternBlock.Attach.CEILING, null);
        helper.succeedWhen(() -> {
            helper.assertTrue(blockLight(helper, at) == 15, "block light at the lantern " + blockLight(helper, at) + ", want 15");
            helper.assertTrue(blockLight(helper, at.west(2)) == 13, "block light 2 west " + blockLight(helper, at.west(2)) + ", want 13");
            if (helper.getBlockState(beam).is(Blocks.STRIPPED_SPRUCE_LOG)) {
                helper.getLevel().destroyBlock(helper.absolutePos(beam), false);
            }
            helper.assertBlockPresent(Blocks.AIR, at);
            helper.assertItemEntityCountIs(item, at, 3.0D, 1);
        });
    }

    /** On a wall side: bracket mount, FACING away from the wall; on a floor top: standing. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void lanternPicksWallAndFloor(GameTestHelper helper) {
        BlockItem item = HallLights.item(HallLights.SQUARE_WOOD_LANTERN);
        BlockPos wall = new BlockPos(1, 3, 2);
        helper.setBlock(wall, Blocks.STONE_BRICKS);
        helper.assertTrue(click(helper, item, wall, Direction.EAST).consumesAction(), "wall placement refused");
        assertLantern(helper, new BlockPos(2, 3, 2), HangingLanternBlock.Attach.WALL, Direction.EAST);
        BlockPos plinth = new BlockPos(4, 2, 2);
        helper.setBlock(plinth, Blocks.STONE_BRICKS);
        helper.assertTrue(click(helper, item, plinth, Direction.UP).consumesAction(), "floor placement refused");
        assertLantern(helper, new BlockPos(4, 3, 2), HangingLanternBlock.Attach.FLOOR, null);
        // nothing to hold it: air clicked in the open is refused
        BlockPos open = new BlockPos(3, 3, 4);
        ServerLevel level = helper.getLevel();
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(helper.absolutePos(open)), Direction.SOUTH, helper.absolutePos(open), false);
        helper.assertFalse(item.place(new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, new ItemStack(item), hit)).consumesAction(),
                "placed in the air with no support");
        // the wall goes: the bracket lantern drops
        helper.getLevel().destroyBlock(helper.absolutePos(wall), false);
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(2, 3, 2));
        helper.succeed();
    }

    /** Two halves, light 14 from the fire box (upper half), either half takes the whole lantern, one item back. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void stoneLanternTwoBlocksTall(GameTestHelper helper) {
        BlockPos base = new BlockPos(2, 2, 2);
        BlockPos lower = base.above();
        BlockPos upper = lower.above();
        BlockItem item = HallLights.item(HallLights.STONE_LANTERN);
        helper.assertTrue(click(helper, item, base, Direction.UP).consumesAction(), "placement refused");
        BlockState lo = helper.getBlockState(lower);
        BlockState up = helper.getBlockState(upper);
        helper.assertTrue(lo.is(HallLights.STONE_LANTERN.get()) && lo.getValue(StoneLanternBlock.HALF) == DoubleBlockHalf.LOWER, "lower: " + lo);
        helper.assertTrue(up.is(HallLights.STONE_LANTERN.get()) && up.getValue(StoneLanternBlock.HALF) == DoubleBlockHalf.UPPER
                && up.getValue(StoneLanternBlock.FACING) == lo.getValue(StoneLanternBlock.FACING), "upper: " + up);
        helper.assertTrue(lo.getLightEmission() == 0 && up.getLightEmission() == 14, "emission lower " + lo.getLightEmission()
                + " upper " + up.getLightEmission());
        helper.succeedWhen(() -> {
            helper.assertTrue(blockLight(helper, upper) == 14, "block light in the fire box " + blockLight(helper, upper));
            if (helper.getBlockState(upper).is(HallLights.STONE_LANTERN.get())) {
                helper.getLevel().destroyBlock(helper.absolutePos(upper), true);
            }
            helper.assertBlockPresent(Blocks.AIR, lower);
            helper.assertBlockPresent(Blocks.AIR, upper);
            helper.assertItemEntityCountIs(item, lower, 3.0D, 1);
        });
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void stoneLanternNeedsRoomAbove(GameTestHelper helper) {
        BlockPos base = new BlockPos(2, 2, 2);
        helper.setBlock(base.above(2), Blocks.OAK_PLANKS);
        helper.assertFalse(click(helper, HallLights.item(HallLights.STONE_LANTERN), base, Direction.UP).consumesAction(), "placed under a block");
        helper.assertBlockPresent(Blocks.AIR, base.above());
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void stoneLanternCreativeBreakDropsNothing(GameTestHelper helper) {
        BlockPos base = new BlockPos(2, 2, 2);
        click(helper, HallLights.item(HallLights.STONE_LANTERN), base, Direction.UP);
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        ServerLevel level = helper.getLevel();
        BlockPos up = helper.absolutePos(base.above(2));
        BlockState state = level.getBlockState(up);
        state.getBlock().playerWillDestroy(level, up, state, player);
        level.removeBlock(up, false);
        helper.assertBlockPresent(Blocks.AIR, base.above());
        helper.runAfterDelay(2, () -> {
            helper.assertItemEntityCountIs(HallLights.item(HallLights.STONE_LANTERN), base.above(), 3.0D, 0);
            helper.succeed();
        });
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void smallStoneLanternGivesLight(GameTestHelper helper) {
        BlockPos base = new BlockPos(2, 2, 2);
        helper.assertTrue(click(helper, HallLights.item(HallLights.SMALL_STONE_LANTERN), base, Direction.UP).consumesAction(), "refused");
        helper.succeedWhen(() -> helper.assertTrue(blockLight(helper, base.above()) == 14, "light " + blockLight(helper, base.above())));
    }

    /** A 3-wide row of open windows joins into one frame; a paper window next to it keeps its own frame. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void latticeWindowsJoinInARow(GameTestHelper helper) {
        BlockItem open = HallWindows.item(HallWindows.LATTICE_WINDOW);
        BlockItem paper = HallWindows.item(HallWindows.LATTICE_WINDOW_PAPER);
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 4; x++) {
            BlockPos p = helper.absolutePos(new BlockPos(x, 3, 2));
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(p), Direction.SOUTH, p, false);
            BlockItem it = x == 4 ? paper : open;
            helper.assertTrue(it.place(new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, new ItemStack(it), hit)).consumesAction(), "refused " + x);
        }
        // facing SOUTH: the viewer looks north, their right is east (+x)
        boolean[][] want = {{false, true}, {true, true}, {true, false}, {false, false}}; // {left, right}
        for (int x = 1; x <= 4; x++) {
            BlockState s = helper.getBlockState(new BlockPos(x, 3, 2));
            helper.assertTrue(s.getValue(LatticeWindowBlock.FACING) == Direction.SOUTH, "facing " + s);
            helper.assertTrue(s.getValue(LatticeWindowBlock.LEFT) == want[x - 1][0] && s.getValue(LatticeWindowBlock.RIGHT) == want[x - 1][1]
                    && !s.getValue(LatticeWindowBlock.UP) && !s.getValue(LatticeWindowBlock.DOWN), "edges at x " + x + ": " + s);
        }
        helper.succeed();
    }
}
