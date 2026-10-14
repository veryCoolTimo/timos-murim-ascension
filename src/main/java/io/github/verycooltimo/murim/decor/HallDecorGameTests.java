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
 * GameTests of the framed panel's edge connections and of the 2x2 round window's placement and breaking.
 *
 * <p>Placement goes through {@link BlockItem#place} with no player: the context's horizontal direction is then NORTH
 * (reference/minecraft-src/net/minecraft/world/item/context/UseOnContext.java#getHorizontalDirection), so every
 * block faces SOUTH and the viewer in front looks north: their right is EAST (+x).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HallDecorGameTests {

    private static final String FLOOR = "small_floor";

    private HallDecorGameTests() {
    }

    private static InteractionResult place(GameTestHelper helper, BlockItem item, BlockPos rel) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(rel);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.SOUTH, pos, false);
        ItemStack stack = new ItemStack(item);
        return item.place(new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, stack, hit));
    }

    private static void assertPanel(GameTestHelper helper, BlockPos rel, boolean up, boolean down, boolean left, boolean right) {
        assertPanel(helper, HallDecor.FRAMED_PANEL.get(), rel, up, down, left, right);
    }

    private static void assertPanel(GameTestHelper helper, FramedPanelBlock type, BlockPos rel, boolean up, boolean down,
                                    boolean left, boolean right) {
        BlockState s = helper.getBlockState(rel);
        helper.assertTrue(s.is(type), "no " + type + " at " + rel + ": " + s);
        helper.assertTrue(s.getValue(FramedPanelBlock.FACING) == Direction.SOUTH, "facing " + s);
        helper.assertTrue(s.getValue(FramedPanelBlock.UP) == up && s.getValue(FramedPanelBlock.DOWN) == down
                && s.getValue(FramedPanelBlock.LEFT) == left && s.getValue(FramedPanelBlock.RIGHT) == right,
                "edges at " + rel + " want up=" + up + " down=" + down + " left=" + left + " right=" + right + ", got " + s);
    }

    /** A 3x2 patch: every panel joins its in-plane neighbours, so only the outer edges keep the frame. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void panelRectangleJoins(GameTestHelper helper) {
        BlockItem item = HallDecor.item(HallDecor.FRAMED_PANEL);
        for (int y = 3; y <= 4; y++) {
            for (int x = 1; x <= 3; x++) {
                helper.assertTrue(place(helper, item, new BlockPos(x, y, 2)).consumesAction(), "placement refused");
            }
        }
        // viewer's left = west (x 1), right = east (x 3)
        assertPanel(helper, new BlockPos(1, 3, 2), true, false, false, true);
        assertPanel(helper, new BlockPos(2, 3, 2), true, false, true, true);
        assertPanel(helper, new BlockPos(3, 3, 2), true, false, true, false);
        assertPanel(helper, new BlockPos(1, 4, 2), false, true, false, true);
        assertPanel(helper, new BlockPos(2, 4, 2), false, true, true, true);
        assertPanel(helper, new BlockPos(3, 4, 2), false, true, true, false);
        // breaking the middle of the bottom row frames the hole again
        helper.getLevel().destroyBlock(helper.absolutePos(new BlockPos(2, 3, 2)), false);
        assertPanel(helper, new BlockPos(1, 3, 2), true, false, false, false);
        assertPanel(helper, new BlockPos(3, 3, 2), true, false, false, false);
        assertPanel(helper, new BlockPos(2, 4, 2), false, false, true, true);
        helper.succeed();
    }

    /** Panels of another facing, or across the wall's depth, never join. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void panelOtherFacingStaysFramed(GameTestHelper helper) {
        BlockItem item = HallDecor.item(HallDecor.FRAMED_PANEL);
        place(helper, item, new BlockPos(2, 3, 2));
        helper.setBlock(new BlockPos(3, 3, 2), HallDecor.FRAMED_PANEL.get().defaultBlockState().setValue(FramedPanelBlock.FACING, Direction.EAST));
        place(helper, item, new BlockPos(2, 3, 3));
        assertPanel(helper, new BlockPos(2, 3, 2), false, false, false, false);
        assertPanel(helper, new BlockPos(2, 3, 3), false, false, false, false);
        helper.succeed();
    }

    /**
     * A 2x2 framed_panel (x 1-2) next to a 1x2 framed_panel_b (x 3), placed in mixed order: each joins its own kind,
     * the shared edge keeps both frames (author 06.10: «чтобы можно было разделять»).
     */
    @GameTest(template = FLOOR, batch = "decor")
    public static void panelTypesStaySeparate(GameTestHelper helper) {
        BlockItem a = HallDecor.item(HallDecor.FRAMED_PANEL);
        BlockItem b = HallDecor.item(HallDecor.FRAMED_PANEL_B);
        place(helper, b, new BlockPos(3, 3, 2));
        place(helper, a, new BlockPos(2, 3, 2));
        place(helper, a, new BlockPos(1, 3, 2));
        place(helper, a, new BlockPos(2, 4, 2));
        place(helper, b, new BlockPos(3, 4, 2));
        place(helper, a, new BlockPos(1, 4, 2));
        FramedPanelBlock pa = HallDecor.FRAMED_PANEL.get();
        FramedPanelBlock pb = HallDecor.FRAMED_PANEL_B.get();
        assertPanel(helper, pa, new BlockPos(1, 3, 2), true, false, false, true);
        assertPanel(helper, pa, new BlockPos(2, 3, 2), true, false, true, false);
        assertPanel(helper, pa, new BlockPos(1, 4, 2), false, true, false, true);
        assertPanel(helper, pa, new BlockPos(2, 4, 2), false, true, true, false);
        assertPanel(helper, pb, new BlockPos(3, 3, 2), true, false, false, false);
        assertPanel(helper, pb, new BlockPos(3, 4, 2), false, true, false, false);
        // swapping a block's type re-splits its neighbours
        helper.getLevel().destroyBlock(helper.absolutePos(new BlockPos(2, 4, 2)), false);
        place(helper, b, new BlockPos(2, 4, 2));
        assertPanel(helper, pb, new BlockPos(2, 4, 2), false, false, false, true);
        assertPanel(helper, pb, new BlockPos(3, 4, 2), false, true, true, false);
        assertPanel(helper, pa, new BlockPos(2, 3, 2), false, false, true, false);
        assertPanel(helper, pa, new BlockPos(1, 4, 2), false, true, false, false);
        helper.succeed();
    }

    private static final BlockPos BL = new BlockPos(1, 3, 2);
    private static final BlockPos BR = new BlockPos(2, 3, 2);
    private static final BlockPos TL = new BlockPos(1, 4, 2);
    private static final BlockPos TR = new BlockPos(2, 4, 2);

    private static void assertWindow(GameTestHelper helper) {
        BlockPos[] at = {BL, BR, TL, TR};
        RoundWindowBlock.Part[] parts = {RoundWindowBlock.Part.BOTTOM_LEFT, RoundWindowBlock.Part.BOTTOM_RIGHT,
                RoundWindowBlock.Part.TOP_LEFT, RoundWindowBlock.Part.TOP_RIGHT};
        for (int i = 0; i < 4; i++) {
            BlockState s = helper.getBlockState(at[i]);
            helper.assertTrue(s.is(HallDecor.ROUND_WINDOW.get()) && s.getValue(RoundWindowBlock.PART) == parts[i]
                    && s.getValue(RoundWindowBlock.FACING) == Direction.SOUTH, parts[i] + " at " + at[i] + ": " + s);
        }
    }

    private static void assertNoWindow(GameTestHelper helper) {
        for (BlockPos p : new BlockPos[]{BL, BR, TL, TR}) {
            helper.assertBlockPresent(Blocks.AIR, p);
        }
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void windowPlacesFourQuarters(GameTestHelper helper) {
        helper.assertTrue(place(helper, HallDecor.item(HallDecor.ROUND_WINDOW), BL).consumesAction(), "placement refused");
        assertWindow(helper);
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void windowBlockedSpotRefuses(GameTestHelper helper) {
        helper.setBlock(TR, Blocks.OAK_PLANKS);
        helper.assertFalse(place(helper, HallDecor.item(HallDecor.ROUND_WINDOW), BL).consumesAction(), "placed with a quarter blocked");
        helper.assertBlockPresent(Blocks.AIR, BL);
        helper.assertBlockPresent(Blocks.AIR, BR);
        helper.assertBlockPresent(Blocks.AIR, TL);
        helper.assertBlockPresent(Blocks.OAK_PLANKS, TR);
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void windowBreakTopRightDropsOne(GameTestHelper helper) {
        breakOne(helper, TR);
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void windowBreakBottomLeftDropsOne(GameTestHelper helper) {
        breakOne(helper, BL);
    }

    private static void breakOne(GameTestHelper helper, BlockPos which) {
        place(helper, HallDecor.item(HallDecor.ROUND_WINDOW), BL);
        assertWindow(helper);
        helper.getLevel().destroyBlock(helper.absolutePos(which), true);
        assertNoWindow(helper);
        helper.runAfterDelay(2, () -> {
            helper.assertItemEntityCountIs(HallDecor.item(HallDecor.ROUND_WINDOW), BL, 4.0D, 1);
            helper.succeed();
        });
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void windowCreativeBreakDropsNothing(GameTestHelper helper) {
        place(helper, HallDecor.item(HallDecor.ROUND_WINDOW), BL);
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        ServerLevel level = helper.getLevel();
        BlockPos tr = helper.absolutePos(TR);
        BlockState state = level.getBlockState(tr);
        state.getBlock().playerWillDestroy(level, tr, state, player);
        level.removeBlock(tr, false);
        assertNoWindow(helper);
        helper.runAfterDelay(2, () -> {
            helper.assertItemEntityCountIs(HallDecor.item(HallDecor.ROUND_WINDOW), BL, 4.0D, 0);
            helper.succeed();
        });
    }
}
