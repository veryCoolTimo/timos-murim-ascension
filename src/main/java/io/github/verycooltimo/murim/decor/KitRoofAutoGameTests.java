package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModBlocks;
import io.github.verycooltimo.murim.world.build.RedBeamBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class KitRoofAutoGameTests {
    private KitRoofAutoGameTests() {}

    private static BlockState place(GameTestHelper h, BlockItem item, BlockPos rel) {
        return place(h, item, rel, Direction.NORTH);
    }

    private static BlockState place(GameTestHelper h, BlockItem item, BlockPos rel, Direction facing) {
        BlockPos pos = h.absolutePos(rel);
        BlockPlaceContext context = new BlockPlaceContext(h.getLevel(), null, InteractionHand.MAIN_HAND, new ItemStack(item),
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)) {
            @Override
            public Direction getHorizontalDirection() {
                return facing;
            }
        };
        h.assertTrue(item.place(context).consumesAction(), "placement refused " + item);
        return h.getBlockState(rel);
    }

    @GameTest(template = "small_floor", batch = "roof")
    public static void everyRoofSlopeChoosesBothCorners(GameTestHelper h) {
        BlockPos p = new BlockPos(2, 3, 2);
        java.util.List<BlockState> defaults = new java.util.ArrayList<>();
        defaults.add(RoofTiles.ROOF_TILE_STAIRS.get().defaultBlockState());
        defaults.add(RoofTiles.ROOF_TILE_LOW.get().defaultBlockState());
        for (RoofBlocks.CourseProfile profile : RoofBlocks.CourseProfile.values()) {
            defaults.add(RoofTiles.ROOF_TILE_COURSE.get().defaultBlockState().setValue(RoofBlocks.Course.PROFILE, profile));
        }
        for (BlockState base : defaults) {
            Block block = base.getBlock();
            for (Direction f : Direction.Plane.HORIZONTAL) {
                for (Half half : Half.values()) {
                    for (boolean left : new boolean[] {true, false}) {
                        BlockState self = base.setValue(StairBlock.FACING, f).setValue(StairBlock.HALF, half);
                        BlockState turn = self.setValue(StairBlock.FACING, left ? f.getCounterClockWise() : f.getClockWise());
                        h.setBlock(p, self);
                        h.setBlock(p.relative(f), turn);
                        h.assertTrue(h.getBlockState(p).getValue(StairBlock.SHAPE) == (left ? StairsShape.OUTER_LEFT : StairsShape.OUTER_RIGHT), "outer " + h.getBlockState(p));
                        h.setBlock(p.relative(f), Blocks.AIR);
                        h.assertTrue(h.getBlockState(p).getValue(StairBlock.SHAPE) == StairsShape.STRAIGHT, "outer did not reopen");
                        h.setBlock(p.relative(f.getOpposite()), turn);
                        h.assertTrue(h.getBlockState(p).getValue(StairBlock.SHAPE) == (left ? StairsShape.INNER_LEFT : StairsShape.INNER_RIGHT), "inner " + h.getBlockState(p));
                        h.setBlock(p.relative(f.getOpposite()), turn.setValue(StairBlock.HALF, half == Half.BOTTOM ? Half.TOP : Half.BOTTOM));
                        h.assertTrue(h.getBlockState(p).getValue(StairBlock.SHAPE) == StairsShape.STRAIGHT, "opposite half made a corner");
                        h.setBlock(p.relative(f.getOpposite()), Blocks.AIR);
                    }
                }
            }
            h.setBlock(p, Blocks.AIR);
            // Item placement must also compute the corner, before any later neighbour change.
            h.setBlock(p.north(), block.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
            BlockState placed = place(h, (BlockItem) block.asItem(), p);
            h.assertTrue(placed.getValue(StairBlock.SHAPE) == StairsShape.OUTER_LEFT, "placed slope not a corner " + placed);
            h.setBlock(p.north(), Blocks.AIR);
            h.setBlock(p, Blocks.AIR);
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "roof")
    public static void incompatibleRoofProfilesStayStraight(GameTestHelper h) {
        BlockPos p = new BlockPos(2, 3, 2);
        BlockState course = RoofTiles.ROOF_TILE_COURSE.get().defaultBlockState();
        h.setBlock(p, course);
        h.setBlock(p.north(), course.setValue(StairBlock.FACING, Direction.WEST).setValue(RoofBlocks.Course.PROFILE, RoofBlocks.CourseProfile.FLAT));
        h.assertTrue(h.getBlockState(p).getValue(StairBlock.SHAPE) == StairsShape.STRAIGHT, "different course profile made corner");
        h.setBlock(p, RoofTiles.ROOF_TILE_STAIRS.get().defaultBlockState());
        h.setBlock(p.north(), RoofTiles.ROOF_TILE_LOW.get().defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
        h.assertTrue(h.getBlockState(p).getValue(StairBlock.SHAPE) == StairsShape.STRAIGHT, "low roof turned steep roof");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "roof")
    public static void ridgeEndsUpdateForStairsAndBothLowHalves(GameTestHelper h) {
        for (Block block : new Block[] {RoofTiles.ROOF_TILE_STAIRS.get(), RoofTiles.ROOF_TILE_LOW.get()}) {
            for (Half half : Half.values()) {
                if (block == RoofTiles.ROOF_TILE_STAIRS.get() && half == Half.TOP) continue;
                BlockState south = block.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH).setValue(StairBlock.HALF, half);
                BlockState north = south.setValue(StairBlock.FACING, Direction.NORTH);
                BlockPos p = new BlockPos(1, 3, 1);
                for (int x = 1; x <= 3; x++) {
                    h.setBlock(new BlockPos(x, 3, 1), south);
                    h.setBlock(new BlockPos(x, 3, 2), north);
                }
                h.assertTrue(h.getBlockState(p).getValue(RoofBlocks.Slope.RIDGE) == RoofBlocks.ApexRidge.OPEN_RIGHT, "west end");
                h.assertTrue(h.getBlockState(p.east()).getValue(RoofBlocks.Slope.RIDGE) == RoofBlocks.ApexRidge.RUN, "middle ridge");
                h.assertTrue(h.getBlockState(p.east(2)).getValue(RoofBlocks.Slope.RIDGE) == RoofBlocks.ApexRidge.OPEN_LEFT, "east end");
                if (block == RoofTiles.ROOF_TILE_STAIRS.get() || half == Half.TOP) {
                    BlockPos end = new BlockPos(4, 4, 1);
                    BlockState endState = place(h, RoofTiles.item(RoofTiles.ROOF_RIDGE_END), end);
                    h.assertTrue(endState.getValue(RoofBlocks.RidgeEnd.FACING) == Direction.WEST, "end ignored apex direction");
                    h.assertTrue(h.getBlockState(p.east(2)).getValue(RoofBlocks.Slope.RIDGE) == RoofBlocks.ApexRidge.RUN, "diagonal ridge end did not close");
                    h.setBlock(end, Blocks.AIR);
                    h.assertTrue(h.getBlockState(p.east(2)).getValue(RoofBlocks.Slope.RIDGE) == RoofBlocks.ApexRidge.OPEN_LEFT, "removed ridge end did not reopen");
                }
                h.setBlock(p.east(), Blocks.AIR);
                h.setBlock(p.east().south(), Blocks.AIR);
                h.assertTrue(h.getBlockState(p).getValue(RoofBlocks.Slope.RIDGE) == RoofBlocks.ApexRidge.OPEN_BOTH, "isolated ridge ends");
                h.setBlock(p.south(), Blocks.AIR);
                h.assertTrue(h.getBlockState(p).getValue(RoofBlocks.Slope.RIDGE) == RoofBlocks.ApexRidge.NONE, "missing opposing slope still has ridge");
                for (int x = 1; x <= 3; x++) for (int z = 1; z <= 2; z++) h.setBlock(new BlockPos(x, 3, z), Blocks.AIR);
            }
        }
        // Gentle lower courses never create an apex ridge merely by facing each other.
        h.setBlock(new BlockPos(2, 3, 1), RoofTiles.ROOF_TILE_COURSE.get().defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        h.setBlock(new BlockPos(2, 3, 2), RoofTiles.ROOF_TILE_COURSE.get().defaultBlockState());
        h.assertTrue(h.getBlockState(new BlockPos(2, 3, 1)).getValue(RoofBlocks.Slope.RIDGE) == RoofBlocks.ApexRidge.NONE, "low course made apex ridge");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "roof")
    public static void ridgeEndChoosesAxisWhenRidgeArrivesLater(GameTestHelper h) {
        BlockPos p = new BlockPos(2, 3, 2);
        h.setBlock(p, RoofTiles.ROOF_RIDGE_END.get().defaultBlockState().setValue(RoofBlocks.RidgeEnd.FACING, Direction.NORTH));
        h.setBlock(p.west(), RoofTiles.ROOF_RIDGE.get().defaultBlockState().setValue(RoofBlocks.AXIS, Direction.Axis.X));
        h.assertTrue(h.getBlockState(p).getValue(RoofBlocks.RidgeEnd.FACING) == Direction.WEST, "end ignored later ridge");
        h.assertTrue(h.getBlockState(p.west()).getValue(RoofBlocks.Ridge.POSITIVE), "ridge did not join reoriented end");
        h.setBlock(p, Blocks.AIR);
        h.assertTrue(!h.getBlockState(p.west()).getValue(RoofBlocks.Ridge.POSITIVE), "ridge kept removed end");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "roof")
    public static void eavesAndCornersFollowNeighboursInEitherOrder(GameTestHelper h) {
        BlockPos corner = new BlockPos(2, 3, 2);
        int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {2, 0, 1}, {1, 2, 0}, {2, 1, 0}};
        for (Direction f : Direction.Plane.HORIZONTAL) {
            Direction cw = f.getClockWise();
            BlockPos[] positions = {corner, corner.relative(f), corner.relative(cw)};
            Direction[] facings = {f.getOpposite(), cw, f};
            // Each edge can be straight or rising; cover both sides and all six placement orders.
            for (int rises = 0; rises < 4; rises++) {
                BlockItem[] items = {RoofTiles.item(RoofTiles.ROOF_CORNER),
                        (rises & 1) == 0 ? RoofTiles.item(RoofTiles.ROOF_EAVE) : RoofTiles.item(RoofTiles.ROOF_EAVE_RISE),
                        (rises & 2) == 0 ? RoofTiles.item(RoofTiles.ROOF_EAVE) : RoofTiles.item(RoofTiles.ROOF_EAVE_RISE)};
                for (int[] order : orders) {
                    boolean[] placed = new boolean[3];
                    String scenario = "facing=" + f + " rises=" + rises + " order=" + java.util.Arrays.toString(order);
                    for (int i : order) {
                        // setBlock writes a literal state; it does not run getStateForPlacement on that block.
                        // In particular, a rise beside an unchanged corner must compute SIDE during item placement.
                        place(h, items[i], positions[i], facings[i]);
                        placed[i] = true;
                        if (placed[0] && (placed[1] || placed[2])) {
                            h.assertTrue(h.getBlockState(corner).getValue(RoofBlocks.Corner.FACING) == f,
                                    "corner ignored eaves " + scenario);
                            for (int edge = 1; edge <= 2; edge++) {
                                if (!placed[edge]) continue;
                                BlockState eave = h.getBlockState(positions[edge]);
                                h.assertTrue(eave.getValue(RoofBlocks.Eave.FACING) == facings[edge], "eave turned " + scenario);
                                if (eave.getBlock() instanceof RoofBlocks.EaveRise) {
                                    h.assertTrue(eave.getValue(RoofBlocks.EaveRise.SIDE)
                                                    == (edge == 1 ? RoofBlocks.Side.RIGHT : RoofBlocks.Side.LEFT),
                                            "rise points away from corner edge=" + edge + " " + scenario + " " + eave);
                                }
                            }
                        }
                    }
                    for (BlockPos pos : positions) h.setBlock(pos, Blocks.AIR);
                }
            }
            // Replacement next to an already oriented corner: no corner state change can notify the new rise.
            BlockPos rise = corner.relative(f);
            place(h, RoofTiles.item(RoofTiles.ROOF_EAVE), rise, cw);
            place(h, RoofTiles.item(RoofTiles.ROOF_CORNER), corner, f.getOpposite());
            h.setBlock(rise, Blocks.AIR);
            BlockState replacement = place(h, RoofTiles.item(RoofTiles.ROOF_EAVE_RISE), rise, cw);
            h.assertTrue(replacement.getValue(RoofBlocks.EaveRise.SIDE) == RoofBlocks.Side.RIGHT,
                    "replacement ignored existing corner " + f + " " + replacement);
            h.setBlock(corner, Blocks.AIR);
            BlockPos otherCorner = rise.relative(f);
            place(h, RoofTiles.item(RoofTiles.ROOF_CORNER), otherCorner, f.getOpposite());
            h.assertTrue(h.getBlockState(rise).getValue(RoofBlocks.EaveRise.SIDE) == RoofBlocks.Side.LEFT,
                    "rise ignored later corner on other side " + f);
            h.setBlock(otherCorner, Blocks.AIR);
            h.setBlock(rise, Blocks.AIR);
        }
        BlockPos lip = new BlockPos(3, 3, 3);
        h.setBlock(lip, RoofTiles.ROOF_EAVE.get().defaultBlockState());
        h.setBlock(lip.east(), RoofTiles.ROOF_TILE_LOW.get().defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        h.assertTrue(h.getBlockState(lip).getValue(RoofBlocks.Eave.FACING) == Direction.EAST, "eave ignored new inward slope");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void kitStateCountsRemainBounded(GameTestHelper h) {
        for (Block block : new Block[] {ModBlocks.SIMPLE_BRACKET.get(), ModBlocks.RED_LINTEL.get(), ModBlocks.RED_BEAM.get(),
                RoofTiles.ROOF_TILE_STAIRS.get(), RoofTiles.ROOF_TILE_LOW.get(), RoofTiles.ROOF_TILE_COURSE.get()}) {
            h.assertTrue(block.getStateDefinition().getPossibleStates().size() <= 4096, "too many baked states " + block);
        }
        h.succeed();
    }
}
