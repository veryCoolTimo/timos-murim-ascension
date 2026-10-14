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
 * GameTests of the two-block sect plaque: placing on a wall makes both halves, a blocked second spot refuses the
 * placement, breaking either half removes both and drops exactly one plaque, creative breaking drops nothing.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/item/BlockItem.java#place,
 * reference/minecraft-src/net/minecraft/gametest/framework/GameTestHelper.java#assertItemEntityCountIs/makeMockPlayer
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectPlaqueGameTests {

    private static final String FLOOR = "small_floor";
    private static final BlockPos WALL = new BlockPos(2, 3, 3);
    private static final BlockPos LEFT = new BlockPos(2, 3, 2);
    /** The plaque faces north; the viewer looks south, so their right is west. */
    private static final BlockPos RIGHT = new BlockPos(1, 3, 2);

    private SectPlaqueGameTests() {
    }

    /** Places the plaque the way a player does: clicks the north face of the wall block with the item. */
    private static InteractionResult place(GameTestHelper helper) {
        helper.setBlock(WALL, Blocks.STONE);
        ServerLevel level = helper.getLevel();
        BlockPos wall = helper.absolutePos(WALL);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(wall).relative(Direction.NORTH, 0.5D), Direction.NORTH, wall, false);
        ItemStack stack = new ItemStack(SectPlaques.SECT_PLAQUE_ITEM.get());
        return ((BlockItem) stack.getItem()).place(new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, stack, hit));
    }

    private static void assertPlaced(GameTestHelper helper) {
        BlockState left = helper.getBlockState(LEFT);
        BlockState right = helper.getBlockState(RIGHT);
        helper.assertTrue(left.is(SectPlaques.SECT_PLAQUE.get()) && left.getValue(SectPlaqueBlock.PART) == SectPlaqueBlock.Part.LEFT,
                "left half: " + left);
        helper.assertTrue(right.is(SectPlaques.SECT_PLAQUE.get()) && right.getValue(SectPlaqueBlock.PART) == SectPlaqueBlock.Part.RIGHT,
                "right half: " + right);
        helper.assertTrue(left.getValue(SectPlaqueBlock.FACING) == Direction.NORTH && right.getValue(SectPlaqueBlock.FACING) == Direction.NORTH,
                "facing " + left.getValue(SectPlaqueBlock.FACING) + "/" + right.getValue(SectPlaqueBlock.FACING));
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void placeMakesBothHalves(GameTestHelper helper) {
        helper.assertTrue(place(helper).consumesAction(), "placement refused on a free wall");
        assertPlaced(helper);
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void blockedSecondSpotRefuses(GameTestHelper helper) {
        helper.setBlock(RIGHT, Blocks.OAK_PLANKS);
        helper.assertFalse(place(helper).consumesAction(), "placed with the second spot taken");
        helper.assertBlockPresent(Blocks.AIR, LEFT);
        helper.assertBlockPresent(Blocks.OAK_PLANKS, RIGHT);
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void breakingRightRemovesBothDropsOne(GameTestHelper helper) {
        breakOne(helper, RIGHT);
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void breakingLeftRemovesBothDropsOne(GameTestHelper helper) {
        breakOne(helper, LEFT);
    }

    private static void breakOne(GameTestHelper helper, BlockPos which) {
        place(helper);
        assertPlaced(helper);
        helper.getLevel().destroyBlock(helper.absolutePos(which), true);
        helper.assertBlockPresent(Blocks.AIR, LEFT);
        helper.assertBlockPresent(Blocks.AIR, RIGHT);
        helper.runAfterDelay(2, () -> {
            helper.assertItemEntityCountIs(SectPlaques.SECT_PLAQUE_ITEM.get(), LEFT, 3.0D, 1);
            helper.succeed();
        });
    }

    /** Creative: breaking the right half takes the left one too, and nothing drops. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void creativeBreakDropsNothing(GameTestHelper helper) {
        place(helper);
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        ServerLevel level = helper.getLevel();
        BlockPos right = helper.absolutePos(RIGHT);
        BlockState state = level.getBlockState(right);
        state.getBlock().playerWillDestroy(level, right, state, player);
        level.removeBlock(right, false);
        helper.assertBlockPresent(Blocks.AIR, LEFT);
        helper.assertBlockPresent(Blocks.AIR, RIGHT);
        helper.runAfterDelay(2, () -> {
            helper.assertItemEntityCountIs(SectPlaques.SECT_PLAQUE_ITEM.get(), LEFT, 3.0D, 0);
            helper.succeed();
        });
    }
}
