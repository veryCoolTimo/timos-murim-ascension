package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Placement/click APIs and floor template follow ShrineGameTests. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class WallPaintingGameTests {
    private static final String FLOOR = "small_floor";

    private WallPaintingGameTests() {
    }

    private static InteractionResult click(GameTestHelper helper, BlockItem item, BlockPos support, Direction face) {
        BlockPos pos = helper.absolutePos(support);
        Vec3 at = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return item.place(new BlockPlaceContext(helper.getLevel(), null, InteractionHand.MAIN_HAND, new ItemStack(item),
                new BlockHitResult(at, face, pos, false)));
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void paintingPlacementClickAndSupport(GameTestHelper helper) {
        BlockPos base = new BlockPos(2, 3, 2);
        BlockItem item = (BlockItem) WallPaintingBlock.PAINTING.get().asItem();
        for (Direction facing : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            for (int c = 0; c < 4; c++) {
                for (int r = 0; r < 2; r++) {
                    BlockPos pos = WallPaintingBlock.part(base, facing, c, r);
                    helper.setBlock(pos, Blocks.AIR);
                    helper.setBlock(pos.relative(facing.getOpposite()), Blocks.STONE_BRICKS);
                }
            }
            helper.assertTrue(WallPaintingBlock.fits(helper.getLevel(), helper.absolutePos(base), facing), "setup must fit");
            helper.assertTrue(click(helper, item, base.relative(facing.getOpposite()), facing).consumesAction(), "placement refused");
            for (int c = 0; c < 4; c++) {
                for (int r = 0; r < 2; r++) {
                    // Independent expected coordinates also check the public part helper's handedness.
                    BlockPos pos = base.relative(facing.getClockWise(), 1 - c).above(r);
                    BlockState before = helper.getBlockState(pos);
                    helper.assertTrue(before.is(WallPaintingBlock.PAINTING.get())
                            && before.getValue(WallPaintingBlock.COL) == c && before.getValue(WallPaintingBlock.ROW) == r
                            && before.getValue(WallPaintingBlock.FACING) == facing, "wrong part: " + before);
                    BlockPos absolute = helper.absolutePos(pos);
                    InteractionResult result = before.useWithoutItem(helper.getLevel(), null,
                            new BlockHitResult(Vec3.atCenterOf(absolute), facing, absolute, false));
                    helper.assertTrue(result == InteractionResult.PASS && !result.consumesAction(), "painting consumed empty-hand click");
                }
            }
            // Check all eight after every click, including changes to other cells.
            for (int c = 0; c < 4; c++) {
                for (int r = 0; r < 2; r++) {
                    BlockState expected = WallPaintingBlock.PAINTING.get().defaultBlockState()
                            .setValue(WallPaintingBlock.FACING, facing).setValue(WallPaintingBlock.COL, c).setValue(WallPaintingBlock.ROW, r);
                    helper.assertTrue(helper.getBlockState(WallPaintingBlock.part(base, facing, c, r)).equals(expected), "click changed painting");
                }
            }
            helper.getLevel().destroyBlock(helper.absolutePos(WallPaintingBlock.part(base, facing, 0, 1)), false);
            helper.assertBlockPresent(Blocks.AIR, WallPaintingBlock.part(base, facing, 0, 1));
            for (int c = 0; c < 4; c++) {
                for (int r = 0; r < 2; r++) {
                    BlockPos pos = WallPaintingBlock.part(base, facing, c, r);
                    BlockPos support = helper.absolutePos(pos.relative(facing.getOpposite()));
                    BlockState state = helper.getBlockState(pos);
                    helper.assertTrue(!state.is(WallPaintingBlock.PAINTING.get())
                            || helper.getLevel().getBlockState(support).isFaceSturdy(helper.getLevel(), support, facing), "floating part after break");
                    helper.getLevel().destroyBlock(support, false);
                    helper.assertBlockPresent(Blocks.AIR, pos);
                }
            }
        }
        helper.succeed();
    }
}
