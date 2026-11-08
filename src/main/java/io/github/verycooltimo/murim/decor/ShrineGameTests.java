package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** GameTests of the shrine set: stacked parts, drops, fixed scroll text, light levels (as HallLightsGameTests). */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class ShrineGameTests {

    private static final String FLOOR = "small_floor";

    private ShrineGameTests() {
    }

    private static InteractionResult click(GameTestHelper helper, BlockItem item, BlockPos support, Direction face) {
        BlockPos pos = helper.absolutePos(support);
        Vec3 at = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return item.place(new BlockPlaceContext(helper.getLevel(), null, InteractionHand.MAIN_HAND, new ItemStack(item),
                new BlockHitResult(at, face, pos, false)));
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void shrineGridDropsOnceFromTopCell(GameTestHelper helper) {
        BlockPos base = new BlockPos(0, 1, 2);
        BlockItem item = ShrineDecor.item(ShrineDecor.ANCESTOR_SHRINE);
        for (int x = 0; x < 2; x++) {
            helper.setBlock(base.east(x), Blocks.STONE_BRICKS);
            for (int y = 2; y <= 4; y++) {
                helper.setBlock(base.east(x).above(y - 1), Blocks.AIR);
            }
        }
        // The old third column may be occupied: a two-wide shrine must leave it untouched.
        helper.setBlock(base.above().east(2), Blocks.STONE_BRICKS);
        helper.assertTrue(click(helper, item, base, Direction.UP).consumesAction(), "placement refused");
        // no player: horizontal direction NORTH, FACING south, viewer's right = east
        for (int c = 0; c < 2; c++) {
            for (int r = 0; r < 3; r++) {
                BlockState s = helper.getBlockState(base.above().east(c).above(r));
                helper.assertTrue(s.is(ShrineDecor.ANCESTOR_SHRINE.get()) && s.getValue(ShrineGridBlock.COL) == c
                        && s.getValue(ShrineGridBlock.PART) == r, "cell " + c + "," + r + ": " + s);
            }
        }
        helper.assertBlockPresent(Blocks.STONE_BRICKS, base.above().east(2));
        BlockPos lower = base.above();
        var lowerShape = helper.getBlockState(lower).getShape(helper.getLevel(), helper.absolutePos(lower));
        helper.assertTrue(lowerShape.toAabbs().stream().anyMatch(b -> b.contains(12.0D / 16, 6.25D / 16, 15.5D / 16)),
                "offering ledge must be at 6.5 px");
        helper.assertTrue(lowerShape.toAabbs().stream().noneMatch(b -> b.contains(12.0D / 16, 10.0D / 16, 12.0D / 16)),
                "niche must be open above the low ledge, without the old tall cabinet");
        BlockPos topPos = base.above(3).east();
        BlockState top = helper.getBlockState(topPos);
        double topY = top.getShape(helper.getLevel(), helper.absolutePos(topPos)).max(Axis.Y);
        helper.assertTrue(topY == 0.5D, "top cell must end at 8 px, was " + topY);
        helper.getLevel().destroyBlock(helper.absolutePos(topPos), true);
        helper.assertBlockPresent(Blocks.AIR, base.above());
        helper.assertBlockPresent(Blocks.AIR, base.above().east(1).above(1));
        helper.succeedWhen(() -> helper.assertItemEntityCountIs(item, base.above(), 6.0D, 1));
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void tinyShrineOfferingsHaveNoCollision(GameTestHelper helper) {
        BlockPos at = new BlockPos(1, 2, 1);
        for (var block : java.util.List.of(ShrineDecor.CANDLE_DRAGON, ShrineDecor.INCENSE_DING)) {
            BlockState state = block.get().defaultBlockState();
            helper.setBlock(at, state);
            helper.assertTrue(state.getCollisionShape(helper.getLevel(), helper.absolutePos(at)).isEmpty(),
                    "tabletop offering has collision: " + block.getId());
            double h = state.getShape(helper.getLevel(), helper.absolutePos(at)).max(Axis.Y);
            double expected = block == ShrineDecor.CANDLE_DRAGON ? 11.0D / 16 : 6.0D / 16;
            helper.assertTrue(h == expected, "offering outline must match its new model: " + h);
        }
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void altarJoinsNeighbours(GameTestHelper helper) {
        BlockItem item = ShrineDecor.item(ShrineDecor.ANCESTOR_ALTAR);
        for (int x = 0; x < 3; x++) {
            helper.assertTrue(click(helper, item, new BlockPos(x, 2, 2), Direction.UP).consumesAction(), "placement refused at " + x);
        }
        // FACING south: viewer's right = east, so the westmost block is the left end
        ShrineJoinBlock.Join[] want = {ShrineJoinBlock.Join.LEFT, ShrineJoinBlock.Join.MID, ShrineJoinBlock.Join.RIGHT};
        for (int x = 0; x < 3; x++) {
            BlockState s = helper.getBlockState(new BlockPos(x, 3, 2));
            helper.assertTrue(s.getValue(ShrineJoinBlock.JOIN) == want[x], "join at " + x + ": " + s);
        }
        helper.getLevel().destroyBlock(helper.absolutePos(new BlockPos(1, 3, 2)), false);
        helper.assertTrue(helper.getBlockState(new BlockPos(0, 3, 2)).getValue(ShrineJoinBlock.JOIN) == ShrineJoinBlock.Join.SINGLE, "left end after the middle is gone");
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void candleStandTwoBlocksLightInUpperPart(GameTestHelper helper) {
        BlockItem item = ShrineDecor.item(ShrineDecor.CANDLE_STAND);
        helper.assertTrue(click(helper, item, new BlockPos(2, 2, 2), Direction.UP).consumesAction(), "placement refused");
        helper.assertTrue(helper.getBlockState(new BlockPos(2, 4, 2)).is(ShrineDecor.CANDLE_STAND.get()), "no upper part");
        helper.succeedWhen(() -> {
            int c = helper.getLevel().getBrightness(LightLayer.BLOCK, helper.absolutePos(new BlockPos(2, 4, 2)));
            helper.assertTrue(c == 13, "candle light " + c);
        });
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void scrollThreeBlocksOnWallKeepsText(GameTestHelper helper) {
        BlockPos wall = new BlockPos(1, 2, 2);
        helper.setBlock(wall, Blocks.STONE_BRICKS);
        helper.setBlock(wall.above(), Blocks.STONE_BRICKS);
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.AIR);
        BlockItem item = ShrineDecor.item(ShrineDecor.SCROLL_1X3);
        helper.assertTrue(click(helper, item, wall, Direction.EAST).consumesAction(), "wall placement refused");
        BlockPos at = new BlockPos(2, 2, 2);
        for (int k = 0; k < 3; k++) {
            BlockState s = helper.getBlockState(at.above(k));
            helper.assertTrue(s.is(ShrineDecor.SCROLL_1X3.get()) && s.getValue(ShrineTallBlock.PART) == k
                    && s.getValue(ShrineTallBlock.FACING) == Direction.EAST, "part " + k + ": " + s);
        }
        BlockState top = helper.getBlockState(at.above(2));
        helper.getLevel().getBlockState(helper.absolutePos(at.above(2))).useWithoutItem(helper.getLevel(), null,
                new BlockHitResult(Vec3.atCenterOf(helper.absolutePos(at.above(2))), Direction.EAST, helper.absolutePos(at.above(2)), false));
        for (int k = 0; k < 3; k++) {
            int v = helper.getBlockState(at.above(k)).getValue(ShrineScrollBlock.VARIANT);
            helper.assertTrue(v == 0, "variant of part " + k + " is " + v);
        }
        helper.assertTrue(top.getValue(ShrineScrollBlock.VARIANT) == 0, "setup");
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void lightLevels(GameTestHelper helper) {
        BlockItem lamp = ShrineDecor.item(ShrineDecor.PAPER_LAMP);
        BlockItem candle = ShrineDecor.item(ShrineDecor.CANDLE_STAND_SMALL);
        helper.assertTrue(click(helper, lamp, new BlockPos(1, 2, 2), Direction.UP).consumesAction(), "lamp refused");
        helper.assertTrue(click(helper, candle, new BlockPos(4, 2, 2), Direction.UP).consumesAction(), "candle refused");
        helper.succeedWhen(() -> {
            int l = helper.getLevel().getBrightness(LightLayer.BLOCK, helper.absolutePos(new BlockPos(1, 4, 2)));
            int c = helper.getLevel().getBrightness(LightLayer.BLOCK, helper.absolutePos(new BlockPos(4, 3, 2)));
            helper.assertTrue(l == 14, "lamp upper light " + l);
            helper.assertTrue(c == 12, "candle light " + c);
        });
    }
}
