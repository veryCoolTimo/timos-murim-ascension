package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of {@link ThinWallBlock}: facing from the clicked face, Shift for the centred wall, straight runs, the
 * stair-rule inner and outer corners, the centred L and T, and the thin collision shape.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/Block.java#updateFromNeighbourShapes (the state a
 * block would take among its neighbours), item/context/UseOnContext.java#isSecondaryUseActive.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class ThinWallGameTests {

    private static final String FLOOR = "small_floor";

    private ThinWallGameTests() {
    }

    private static ThinWallBlock wall() {
        return HallDecor.THIN_WALL_PLASTER.get();
    }

    private static void put(GameTestHelper helper, BlockPos rel, Direction facing, boolean centre) {
        BlockState s = wall().defaultBlockState().setValue(ThinWallBlock.FACING, facing).setValue(ThinWallBlock.CENTER, centre);
        helper.setBlock(rel, Block.updateFromNeighbourShapes(s, helper.getLevel(), helper.absolutePos(rel)));
    }

    private static void assertShape(GameTestHelper helper, BlockPos rel, StairsShape want) {
        BlockState s = helper.getBlockState(rel);
        helper.assertTrue(s.getValue(ThinWallBlock.SHAPE) == want, "shape at " + rel + " want " + want + ", got " + s);
    }

    private static void assertArms(GameTestHelper helper, BlockPos rel, boolean n, boolean e, boolean s, boolean w) {
        BlockState st = helper.getBlockState(rel);
        helper.assertTrue(st.getValue(ThinWallBlock.NORTH) == n && st.getValue(ThinWallBlock.EAST) == e
                        && st.getValue(ThinWallBlock.SOUTH) == s && st.getValue(ThinWallBlock.WEST) == w,
                "arms at " + rel + " want n=" + n + " e=" + e + " s=" + s + " w=" + w + ", got " + st);
    }

    /** Clicking the south face of the spot (no player) puts the wall in its north half; three in a line stay straight. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void thinWallStraightRun(GameTestHelper helper) {
        BlockItem item = HallDecor.item(HallDecor.THIN_WALL_PLASTER);
        for (int x = 1; x <= 3; x++) {
            BlockPos pos = helper.absolutePos(new BlockPos(x, 3, 2));
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.SOUTH, pos, false);
            helper.assertTrue(item.place(new BlockPlaceContext(helper.getLevel(), null, InteractionHand.MAIN_HAND,
                    new ItemStack(item), hit)).consumesAction(), "placement refused");
        }
        for (int x = 1; x <= 3; x++) {
            BlockState s = helper.getBlockState(new BlockPos(x, 3, 2));
            helper.assertTrue(s.getValue(ThinWallBlock.FACING) == Direction.NORTH && !s.getValue(ThinWallBlock.CENTER), "placed " + s);
            assertShape(helper, new BlockPos(x, 3, 2), StairsShape.STRAIGHT);
        }
        AABB box = helper.getBlockState(new BlockPos(2, 3, 2)).getShape(helper.getLevel(), helper.absolutePos(new BlockPos(2, 3, 2))).bounds();
        helper.assertTrue(box.minZ == 0 && box.maxZ == 0.5 && box.minX == 0 && box.maxX == 1 && box.maxY == 1, "thin shape " + box);
        helper.succeed();
    }

    /** Room corner: a run along x (north half) turning south along the east half makes the 3/4 inner shape. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void thinWallInnerCorner(GameTestHelper helper) {
        put(helper, new BlockPos(1, 3, 2), Direction.NORTH, false);
        put(helper, new BlockPos(2, 3, 3), Direction.EAST, false);
        put(helper, new BlockPos(2, 3, 4), Direction.EAST, false);
        put(helper, new BlockPos(2, 3, 2), Direction.NORTH, false);
        assertShape(helper, new BlockPos(2, 3, 2), StairsShape.INNER_RIGHT);
        assertShape(helper, new BlockPos(1, 3, 2), StairsShape.STRAIGHT);
        AABB box = helper.getBlockState(new BlockPos(2, 3, 2)).getShape(helper.getLevel(), helper.absolutePos(new BlockPos(2, 3, 2))).bounds();
        helper.assertTrue(box.maxZ == 1 && box.maxX == 1 && box.minX == 0, "inner shape " + box);
        helper.succeed();
    }

    /** Outer corner: the run arrives from the west and turns north along the west half; from the east it stays straight. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void thinWallOuterCorner(GameTestHelper helper) {
        put(helper, new BlockPos(1, 3, 2), Direction.NORTH, false);
        put(helper, new BlockPos(2, 3, 1), Direction.WEST, false);
        put(helper, new BlockPos(2, 3, 2), Direction.NORTH, false);
        assertShape(helper, new BlockPos(2, 3, 2), StairsShape.OUTER_LEFT);
        // the same turn with the run arriving from the east: the full north half is already the clean L
        put(helper, new BlockPos(2, 4, 2), Direction.NORTH, false);
        put(helper, new BlockPos(1, 4, 1), Direction.WEST, false);
        put(helper, new BlockPos(1, 4, 2), Direction.NORTH, false);
        assertShape(helper, new BlockPos(1, 4, 2), StairsShape.STRAIGHT);
        helper.succeed();
    }

    /** Shift places the centred wall; centred walls make an L at a turn and a T where a run meets a lone wall. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void thinWallCentredCorners(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setShiftKeyDown(true);
        BlockItem item = HallDecor.item(HallDecor.THIN_WALL_PLASTER);
        BlockPos pos = helper.absolutePos(new BlockPos(1, 3, 2));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.SOUTH, pos, false);
        helper.assertTrue(item.place(new BlockPlaceContext(helper.getLevel(), player, InteractionHand.MAIN_HAND,
                new ItemStack(item), hit)).consumesAction(), "placement refused");
        BlockState placed = helper.getBlockState(new BlockPos(1, 3, 2));
        helper.assertTrue(placed.getValue(ThinWallBlock.CENTER), "Shift did not centre: " + placed);
        Direction run = placed.getValue(ThinWallBlock.FACING);
        helper.assertTrue(run.getAxis() == Direction.Axis.Z, "centred wall should run across the view (x): " + placed);
        // L: (1)-(2) along x, then (2,z3),(2,z4) along z
        put(helper, new BlockPos(2, 3, 2), Direction.NORTH, true);
        put(helper, new BlockPos(2, 3, 3), Direction.EAST, true);
        put(helper, new BlockPos(2, 3, 4), Direction.EAST, true);
        assertArms(helper, new BlockPos(1, 3, 2), false, true, false, true);
        assertArms(helper, new BlockPos(2, 3, 2), false, false, true, true);
        assertArms(helper, new BlockPos(2, 3, 3), true, false, true, false);
        // T: a lone wall along x with a run coming from the south keeps its whole line
        put(helper, new BlockPos(3, 4, 2), Direction.NORTH, true);
        put(helper, new BlockPos(3, 4, 3), Direction.EAST, true);
        assertArms(helper, new BlockPos(3, 4, 2), false, true, true, true);
        AABB box = helper.getBlockState(new BlockPos(2, 3, 2)).getShape(helper.getLevel(), helper.absolutePos(new BlockPos(2, 3, 2))).bounds();
        helper.assertTrue(box.minX == 0 && box.maxX == 0.75 && box.minZ == 0.25 && box.maxZ == 1, "L shape " + box);
        helper.succeed();
    }
}
