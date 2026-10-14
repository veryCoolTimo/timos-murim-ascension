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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the four-block sect plaque: placing makes the whole row (the clicked spot is part 1), a blocked
 * spot anywhere refuses it, breaking any part removes all four and drops exactly one plaque, creative drops none.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/item/BlockItem.java#place,
 * reference/minecraft-src/net/minecraft/gametest/framework/GameTestHelper.java#assertItemEntityCountIs/makeMockPlayer
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectPlaqueWideGameTests {

    private static final String FLOOR = "small_floor";
    private static final BlockPos WALL = new BlockPos(3, 3, 3);
    /** Facing north the viewer looks south: their right is west (-x). Part k sits at x = 4 - k. */
    private static BlockPos part(int k) {
        return new BlockPos(4 - k, 3, 2);
    }

    private SectPlaqueWideGameTests() {
    }

    private static InteractionResult place(GameTestHelper helper) {
        helper.setBlock(WALL, Blocks.STONE);
        ServerLevel level = helper.getLevel();
        BlockPos wall = helper.absolutePos(WALL);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(wall).relative(Direction.NORTH, 0.5D), Direction.NORTH, wall, false);
        ItemStack stack = new ItemStack(SectPlaques.SECT_PLAQUE_WIDE_ITEM.get());
        return ((BlockItem) stack.getItem()).place(new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, stack, hit));
    }

    private static void assertPlaced(GameTestHelper helper) {
        for (int k = 0; k < SectPlaqueWideBlock.PARTS; k++) {
            BlockState s = helper.getBlockState(part(k));
            helper.assertTrue(s.is(SectPlaques.SECT_PLAQUE_WIDE.get()) && s.getValue(SectPlaqueWideBlock.PART) == k
                    && s.getValue(SectPlaqueWideBlock.FACING) == Direction.NORTH, "part " + k + ": " + s);
        }
    }

    private static void assertGone(GameTestHelper helper) {
        for (int k = 0; k < SectPlaqueWideBlock.PARTS; k++) {
            helper.assertBlockPresent(Blocks.AIR, part(k));
        }
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void wideMakesFourParts(GameTestHelper helper) {
        helper.assertTrue(place(helper).consumesAction(), "placement refused on a free wall");
        assertPlaced(helper);
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void wideBlockedSpotRefuses(GameTestHelper helper) {
        helper.setBlock(part(3), Blocks.OAK_PLANKS);
        helper.assertFalse(place(helper).consumesAction(), "placed with the last spot taken");
        helper.assertBlockPresent(Blocks.AIR, part(1));
        helper.assertBlockPresent(Blocks.OAK_PLANKS, part(3));
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void wideBreakingEndDropsOne(GameTestHelper helper) {
        breakOne(helper, 3);
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void wideBreakingAnchorDropsOne(GameTestHelper helper) {
        breakOne(helper, SectPlaqueWideBlock.ANCHOR);
    }

    private static void breakOne(GameTestHelper helper, int k) {
        place(helper);
        assertPlaced(helper);
        helper.getLevel().destroyBlock(helper.absolutePos(part(k)), true);
        assertGone(helper);
        helper.runAfterDelay(2, () -> {
            helper.assertItemEntityCountIs(SectPlaques.SECT_PLAQUE_WIDE_ITEM.get(), part(1), 4.0D, 1);
            helper.succeed();
        });
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void wideCreativeBreakDropsNothing(GameTestHelper helper) {
        place(helper);
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        ServerLevel level = helper.getLevel();
        BlockPos end = helper.absolutePos(part(3));
        BlockState state = level.getBlockState(end);
        state.getBlock().playerWillDestroy(level, end, state, player);
        level.removeBlock(end, false);
        assertGone(helper);
        helper.runAfterDelay(2, () -> {
            helper.assertItemEntityCountIs(SectPlaques.SECT_PLAQUE_WIDE_ITEM.get(), part(1), 4.0D, 0);
            helper.succeed();
        });
    }
}
