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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the roof kit's states: the ridge joins along its axis and opens its ends again, a ridge end next to a
 * ridge faces it, the slab keeps its axis when doubled, the corner's facing follows the look direction.
 *
 * <p>Placement through {@link BlockItem#place} with no player: the horizontal direction is then NORTH
 * (reference/minecraft-src/net/minecraft/world/item/context/UseOnContext.java#getHorizontalDirection), axis Z.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class RoofGameTests {

    private static final String FLOOR = "small_floor";

    private RoofGameTests() {
    }

    private static InteractionResult place(GameTestHelper helper, BlockItem item, BlockPos rel, Direction face) {
        BlockPos pos = helper.absolutePos(rel);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
        return item.place(new BlockPlaceContext(helper.getLevel(), null, InteractionHand.MAIN_HAND, new ItemStack(item), hit));
    }

    /** Three ridges in a row along z: inner ends joined, outer ends open; breaking the middle opens both sides. */
    /** Author 16.10: a ridge placed on top of a gable panel afterwards was not noticed. */
    @GameTest(template = FLOOR, batch = "roof")
    public static void gablePanelBecomesPeakUnderARidgePlacedLater(GameTestHelper helper) {
        BlockPos panel = new BlockPos(2, 3, 2);
        helper.assertTrue(place(helper, RoofTiles.item(RoofTiles.ROOF_GABLE_PANEL), panel, Direction.UP).consumesAction(),
                "panel placement refused");
        helper.assertTrue(place(helper, RoofTiles.item(RoofTiles.ROOF_RIDGE), panel.above(), Direction.UP).consumesAction(),
                "ridge placement refused");
        BlockState state = helper.getBlockState(panel);
        helper.assertTrue(state.getValue(RoofBlocks.GablePanel.PROFILE) == RoofBlocks.PanelProfile.PEAK,
                "panel did not notice the ridge: " + state);
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "roof")
    public static void ridgeJoinsAlongAxis(GameTestHelper helper) {
        BlockItem item = RoofTiles.item(RoofTiles.ROOF_RIDGE);
        for (int z = 1; z <= 3; z++) {
            helper.assertTrue(place(helper, item, new BlockPos(2, 3, z), Direction.UP).consumesAction(), "placement refused");
        }
        BlockState a = helper.getBlockState(new BlockPos(2, 3, 1));
        BlockState b = helper.getBlockState(new BlockPos(2, 3, 2));
        BlockState c = helper.getBlockState(new BlockPos(2, 3, 3));
        helper.assertTrue(b.getValue(RoofBlocks.AXIS) == Direction.Axis.Z, "axis " + b);
        helper.assertTrue(!a.getValue(RoofBlocks.Ridge.NEGATIVE) && a.getValue(RoofBlocks.Ridge.POSITIVE), "first " + a);
        helper.assertTrue(b.getValue(RoofBlocks.Ridge.NEGATIVE) && b.getValue(RoofBlocks.Ridge.POSITIVE), "middle " + b);
        helper.assertTrue(c.getValue(RoofBlocks.Ridge.NEGATIVE) && !c.getValue(RoofBlocks.Ridge.POSITIVE), "last " + c);
        helper.getLevel().destroyBlock(helper.absolutePos(new BlockPos(2, 3, 2)), false);
        helper.assertTrue(!helper.getBlockState(new BlockPos(2, 3, 1)).getValue(RoofBlocks.Ridge.POSITIVE), "first still joined");
        helper.assertTrue(!helper.getBlockState(new BlockPos(2, 3, 3)).getValue(RoofBlocks.Ridge.NEGATIVE), "last still joined");
        // a ridge end at the open south end faces north, toward the ridge, and closes it
        helper.assertTrue(place(helper, RoofTiles.item(RoofTiles.ROOF_RIDGE_END), new BlockPos(2, 3, 4), Direction.UP)
                .consumesAction(), "end refused");
        BlockState end = helper.getBlockState(new BlockPos(2, 3, 4));
        helper.assertTrue(end.getValue(RoofBlocks.RidgeEnd.FACING) == Direction.NORTH, "end facing " + end);
        helper.assertTrue(helper.getBlockState(new BlockPos(2, 3, 3)).getValue(RoofBlocks.Ridge.POSITIVE), "end not joined");
        helper.succeed();
    }

    /**
     * A course beside a hip of another profile stays straight when the block above it changes (roof v5 10.10: an
     * update from above used the stair rules and turned it into a second hip).
     */
    @GameTest(template = FLOOR, batch = "roof")
    public static void courseStaysStraightOnUpdateFromAbove(GameTestHelper helper) {
        BlockState course = RoofTiles.ROOF_TILE_COURSE.get().defaultBlockState();
        BlockPos a = new BlockPos(2, 3, 2);
        helper.setBlock(new BlockPos(2, 3, 1), course.setValue(RoofBlocks.Slope.FACING, Direction.SOUTH)
                .setValue(RoofBlocks.Course.PROFILE, RoofBlocks.CourseProfile.GENTLE));
        helper.setBlock(new BlockPos(3, 3, 2), course.setValue(RoofBlocks.Slope.FACING, Direction.SOUTH)
                .setValue(RoofBlocks.Course.PROFILE, RoofBlocks.CourseProfile.LOW_HIGH));
        helper.setBlock(a, course.setValue(RoofBlocks.Slope.FACING, Direction.EAST)
                .setValue(RoofBlocks.Course.PROFILE, RoofBlocks.CourseProfile.GENTLE));
        helper.setBlock(a.above(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        BlockState s = helper.getBlockState(a);
        helper.assertTrue(s.getValue(RoofBlocks.Slope.SHAPE) == net.minecraft.world.level.block.state.properties.StairsShape.STRAIGHT,
                "course " + s);
        helper.succeed();
    }

    /** A second slab on a bottom slab becomes double and keeps the first slab's axis. */
    @GameTest(template = FLOOR, batch = "roof")
    public static void slabKeepsAxis(GameTestHelper helper) {
        BlockPos rel = new BlockPos(2, 3, 2);
        helper.setBlock(rel, RoofTiles.ROOF_TILE_SLAB.get().defaultBlockState().setValue(RoofBlocks.AXIS, Direction.Axis.X));
        helper.assertTrue(place(helper, RoofTiles.item(RoofTiles.ROOF_TILE_SLAB), rel, Direction.UP).consumesAction(), "refused");
        BlockState s = helper.getBlockState(rel);
        helper.assertTrue(s.getValue(RoofBlocks.Slab.TYPE) == SlabType.DOUBLE && s.getValue(RoofBlocks.AXIS) == Direction.Axis.X,
                "slab " + s);
        helper.succeed();
    }

    /** A rising eave turns toward a corner piece beside it: facing north (no player), a corner to the west = left. */
    @GameTest(template = FLOOR, batch = "roof")
    public static void eaveRiseTurnsToCorner(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 3, 2), RoofTiles.ROOF_CORNER.get().defaultBlockState());
        helper.setBlock(new BlockPos(4, 3, 2), RoofTiles.ROOF_CORNER.get().defaultBlockState());
        BlockItem item = RoofTiles.item(RoofTiles.ROOF_EAVE_RISE);
        helper.assertTrue(place(helper, item, new BlockPos(2, 3, 2), Direction.UP).consumesAction(), "refused");
        helper.assertTrue(place(helper, item, new BlockPos(3, 3, 2), Direction.UP).consumesAction(), "refused");
        BlockState w = helper.getBlockState(new BlockPos(2, 3, 2));
        BlockState e = helper.getBlockState(new BlockPos(3, 3, 2));
        helper.assertTrue(w.getValue(RoofBlocks.EaveRise.SIDE) == RoofBlocks.Side.LEFT, "west " + w);
        helper.assertTrue(e.getValue(RoofBlocks.EaveRise.SIDE) == RoofBlocks.Side.RIGHT, "east " + e);
        // low tiles beside each other at a right angle form a hip like stairs do
        helper.setBlock(new BlockPos(2, 4, 4), RoofTiles.ROOF_TILE_LOW.get().defaultBlockState()
                .setValue(RoofBlocks.LowSlope.FACING, Direction.NORTH));
        double height = helper.getBlockState(new BlockPos(2, 4, 4))
                .getShape(helper.getLevel(), helper.absolutePos(new BlockPos(2, 4, 4))).max(Direction.Axis.Y);
        // A smooth OBJ's inscribed voxel mask stops up to one model pixel below its high edge.
        helper.assertTrue(height <= 0.5D && height >= 7.0D / 16, "low tile height " + height);
        helper.succeed();
    }

    private static void setUpdated(GameTestHelper helper, BlockPos rel, BlockState state) {
        BlockPos pos = helper.absolutePos(rel);
        helper.getLevel().setBlock(pos, state, 3);
        helper.getLevel().setBlock(pos, net.minecraft.world.level.block.Block.updateFromNeighbourShapes(state, helper.getLevel(), pos), 3);
    }

    /**
     * Two stairs rows back to back draw a ridge over their apex by themselves: open at the run's ends; a real
     * roof_ridge one block up beside the run closes that end, and joins the run in turn.
     */
    @GameTest(template = FLOOR, batch = "roof")
    public static void apexRidgeOnBackToBackSlopes(GameTestHelper helper) {
        BlockState south = RoofTiles.ROOF_TILE_STAIRS.get().defaultBlockState().setValue(RoofBlocks.Slope.FACING, Direction.SOUTH);
        BlockState north = RoofTiles.ROOF_TILE_STAIRS.get().defaultBlockState().setValue(RoofBlocks.Slope.FACING, Direction.NORTH);
        for (int x = 1; x <= 2; x++) {
            setUpdated(helper, new BlockPos(x, 3, 1), south);
            setUpdated(helper, new BlockPos(x, 3, 2), north);
        }
        // facing south, left = east: the west one is open on its right (west), the east one on its left (east)
        helper.assertTrue(helper.getBlockState(new BlockPos(1, 3, 1)).getValue(RoofBlocks.Slope.RIDGE) == RoofBlocks.ApexRidge.OPEN_RIGHT,
                "west " + helper.getBlockState(new BlockPos(1, 3, 1)));
        helper.assertTrue(helper.getBlockState(new BlockPos(2, 3, 1)).getValue(RoofBlocks.Slope.RIDGE) == RoofBlocks.ApexRidge.OPEN_LEFT,
                "east " + helper.getBlockState(new BlockPos(2, 3, 1)));
        helper.assertTrue(helper.getBlockState(new BlockPos(1, 3, 2)).getValue(RoofBlocks.Slope.RIDGE) != RoofBlocks.ApexRidge.NONE,
                "partner " + helper.getBlockState(new BlockPos(1, 3, 2)));
        // a ridge block east of the run, one up, on a tile block
        helper.setBlock(new BlockPos(3, 3, 1), RoofTiles.ROOF_TILES.get().defaultBlockState());
        helper.assertTrue(place(helper, RoofTiles.item(RoofTiles.ROOF_RIDGE), new BlockPos(3, 4, 1), Direction.UP).consumesAction(), "ridge");
        helper.setBlock(new BlockPos(3, 4, 1), helper.getBlockState(new BlockPos(3, 4, 1)).setValue(RoofBlocks.AXIS, Direction.Axis.X));
        setUpdated(helper, new BlockPos(3, 4, 1), helper.getBlockState(new BlockPos(3, 4, 1)));
        BlockState ridge = helper.getBlockState(new BlockPos(3, 4, 1));
        helper.assertTrue(ridge.getValue(RoofBlocks.Ridge.NEGATIVE), "ridge does not join the apex run " + ridge);
        helper.assertTrue(helper.getBlockState(new BlockPos(2, 3, 1)).getValue(RoofBlocks.Slope.RIDGE) == RoofBlocks.ApexRidge.RUN,
                "run not closed by the ridge " + helper.getBlockState(new BlockPos(2, 3, 1)));
        // a single stairs block facing nothing has no ridge
        setUpdated(helper, new BlockPos(1, 3, 4), south);
        helper.assertTrue(helper.getBlockState(new BlockPos(1, 3, 4)).getValue(RoofBlocks.Slope.RIDGE) == RoofBlocks.ApexRidge.NONE, "lone");
        helper.succeed();
    }

    /** Big-roof timber: placed facing the way the placer looks (north here), the architrave 10 px deep across it. */
    @GameTest(template = FLOOR, batch = "roof")
    public static void bigRoofTimberPlaces(GameTestHelper helper) {
        helper.assertTrue(place(helper, RoofTiles.item(RoofTiles.ROOF_BRACKET_SET), new BlockPos(1, 3, 1), Direction.UP)
                .consumesAction(), "bracket refused");
        helper.assertTrue(place(helper, RoofTiles.item(RoofTiles.ROOF_ARCHITRAVE), new BlockPos(2, 3, 1), Direction.UP)
                .consumesAction(), "architrave refused");
        helper.assertTrue(place(helper, RoofTiles.item(RoofTiles.ROOF_WALL_RIDGE), new BlockPos(3, 3, 1), Direction.UP)
                .consumesAction(), "wall ridge refused");
        helper.assertTrue(helper.getBlockState(new BlockPos(1, 3, 1)).getValue(RoofBlocks.Facing.FACING) == Direction.NORTH, "bracket");
        BlockState beam = helper.getBlockState(new BlockPos(2, 3, 1));
        helper.assertTrue(beam.getShape(helper.getLevel(), helper.absolutePos(new BlockPos(2, 3, 1))).bounds().getZsize() == 10 / 16.0D,
                "architrave depth " + beam);
        helper.succeed();
    }

    /** A plaster gable placed beside a roof slope copies the slope's facing and shape (low upper half here). */
    @GameTest(template = FLOOR, batch = "roof")
    public static void gableCopiesSlope(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 3, 2), RoofTiles.ROOF_TILE_LOW.get().defaultBlockState()
                .setValue(RoofBlocks.LowSlope.FACING, Direction.EAST)
                .setValue(RoofBlocks.LowSlope.HALF, net.minecraft.world.level.block.state.properties.Half.TOP));
        helper.assertTrue(place(helper, RoofTiles.item(RoofTiles.ROOF_GABLE), new BlockPos(2, 3, 1), Direction.UP).consumesAction(), "refused");
        BlockState g = helper.getBlockState(new BlockPos(2, 3, 1));
        helper.assertTrue(g.getValue(RoofBlocks.Gable.FACING) == Direction.EAST && g.getValue(RoofBlocks.Gable.SHAPE)
                == RoofBlocks.GableShape.LOW_UPPER, "gable " + g);
        helper.succeed();
    }

    /** The corner lies behind the placer: looking south-east puts it at the north-west (FACING east). */
    @GameTest(template = FLOOR, batch = "roof")
    public static void cornerFollowsLook(GameTestHelper helper) {
        helper.assertTrue(RoofBlocks.Corner.facingFor(new Vec3(1, 0, 1)) == Direction.EAST, "SE");
        helper.assertTrue(RoofBlocks.Corner.facingFor(new Vec3(-1, 0, 1)) == Direction.SOUTH, "SW");
        helper.assertTrue(RoofBlocks.Corner.facingFor(new Vec3(-1, 0, -1)) == Direction.WEST, "NW");
        helper.assertTrue(RoofBlocks.Corner.facingFor(new Vec3(1, 0, -1)) == Direction.NORTH, "NE");
        helper.succeed();
    }

    /** Split horn pieces retain their ownership when rotated/mirrored or updated next to tiles. */
    @GameTest(template = FLOOR, batch = "roof")
    public static void v7HornOwnershipSurvivesTransformsAndUpdates(GameTestHelper helper) {
        BlockState tip = RoofTiles.ROOF_V7_RIDGE_END.get().defaultBlockState()
                .setValue(RoofV7Blocks.RidgeEnd.PART, RoofV7Blocks.HornPart.TIP)
                .setValue(RoofV7Blocks.RidgeEnd.FACING, Direction.EAST);
        BlockState rotated = tip.rotate(net.minecraft.world.level.block.Rotation.CLOCKWISE_90);
        helper.assertTrue(rotated.getValue(RoofV7Blocks.RidgeEnd.FACING) == Direction.SOUTH
                && rotated.getValue(RoofV7Blocks.RidgeEnd.PART) == RoofV7Blocks.HornPart.TIP, "tip rotation");
        BlockState mirrored = tip.mirror(net.minecraft.world.level.block.Mirror.FRONT_BACK);
        helper.assertTrue(mirrored.getValue(RoofV7Blocks.RidgeEnd.FACING) == Direction.WEST
                && mirrored.getValue(RoofV7Blocks.RidgeEnd.PART) == RoofV7Blocks.HornPart.TIP, "tip mirror");
        BlockPos pos = new BlockPos(2, 3, 2);
        helper.setBlock(pos, tip);
        helper.setBlock(pos.above(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        helper.assertTrue(helper.getBlockState(pos).equals(tip), "neighbour update changed horn owner");
        helper.assertTrue(RoofTiles.ROOF_V7_RIDGE_END.get().getStateDefinition().getPossibleStates().size() == 8,
                "horn state count");
        helper.assertTrue(RoofTiles.ROOF_V7_PANEL.get().getStateDefinition().getPossibleStates().size() == 88,
                "panel state count");
        helper.succeed();
    }

    private static double roofHeightAt(net.minecraft.world.phys.shapes.VoxelShape shape, double x, double z) {
        return shape.toAabbs().stream().filter(box -> x >= box.minX && x < box.maxX && z >= box.minZ && z < box.maxZ)
                .mapToDouble(box -> box.maxY).max().orElse(0);
    }

    /** Former shells now collide/occlude beneath the visible skin, with matching state rotations. */
    @GameTest(template = FLOOR, batch = "roof")
    public static void solidRoofCoreAndMasks(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 3, 2);
        BlockState gentle = RoofTiles.ROOF_TILE_COURSE.get().defaultBlockState()
                .setValue(RoofBlocks.Slope.FACING, Direction.EAST)
                .setValue(RoofBlocks.Course.PROFILE, RoofBlocks.CourseProfile.GENTLE);
        for (net.minecraft.world.level.block.Rotation rotation : net.minecraft.world.level.block.Rotation.values()) {
            BlockState state = gentle.rotate(rotation);
            helper.setBlock(pos, state);
            net.minecraft.world.phys.shapes.VoxelShape collision = state.getCollisionShape(helper.getLevel(), helper.absolutePos(pos));
            net.minecraft.world.phys.shapes.VoxelShape occlusion = state.getOcclusionShape(helper.getLevel(), helper.absolutePos(pos));
            Direction facing = state.getValue(RoofBlocks.Slope.FACING);
            double high = roofHeightAt(collision, .5 + .375 * facing.getStepX(), .5 + .375 * facing.getStepZ());
            double low = roofHeightAt(collision, .5 - .375 * facing.getStepX(), .5 - .375 * facing.getStepZ());
            helper.assertTrue(high > low, "mask rises away from model facing " + state);
            helper.assertTrue(collision.min(Direction.Axis.Y) == 0, "core does not reach floor " + state);
            helper.assertTrue(!net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(collision, occlusion,
                    net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME), "collision/occlusion differ " + state);
            helper.assertTrue(collision.toAabbs().stream().anyMatch(box -> box.contains(.5, .0625, .5)),
                    "empty core below tile " + state);
            helper.assertTrue(!collision.toAabbs().stream().anyMatch(box -> box.contains(.5, .75, .5)),
                    "invisible full-block collision above slope " + state);
        }
        BlockState left = RoofTiles.ROOF_EAVE_RISE.get().defaultBlockState()
                .setValue(RoofBlocks.EaveRise.FACING, Direction.EAST).setValue(RoofBlocks.EaveRise.SIDE, RoofBlocks.Side.LEFT);
        BlockState right = left.setValue(RoofBlocks.EaveRise.SIDE, RoofBlocks.Side.RIGHT);
        helper.assertTrue(net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(
                left.getShape(helper.getLevel(), helper.absolutePos(pos)),
                right.getShape(helper.getLevel(), helper.absolutePos(pos)),
                net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME), "rise sides use one mask");
        helper.succeed();
    }

}
