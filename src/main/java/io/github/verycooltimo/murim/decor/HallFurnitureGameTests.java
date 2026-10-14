package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the hall furniture: the two-block desk and shelf, book piles adding up and dropping as many items, tables
 * joining, the bell's support and strike cooldown, sitting on a cushion. Placement goes through {@link BlockItem#place}
 * with no player (horizontal direction NORTH, so FACING south), as in {@link HallLightsGameTests}.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HallFurnitureGameTests {

    private static final String FLOOR = "small_floor";

    private HallFurnitureGameTests() {
    }

    private static InteractionResult click(GameTestHelper helper, BlockItem item, BlockPos support, Direction face) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(support);
        Vec3 at = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return item.place(new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, new ItemStack(item),
                new BlockHitResult(at, face, pos, false)));
    }

    /** Facing south, the right part goes east; breaking the right part takes the desk, one item back. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void deskTwoBlocksWide(GameTestHelper helper) {
        BlockPos left = new BlockPos(1, 3, 2);
        BlockItem item = HallFurniture.item(HallFurniture.WRITING_DESK);
        helper.assertTrue(click(helper, item, left.below(), Direction.UP).consumesAction(), "placement refused");
        BlockState l = helper.getBlockState(left);
        BlockState r = helper.getBlockState(left.east());
        helper.assertTrue(l.is(HallFurniture.WRITING_DESK.get()) && l.getValue(WritingDeskBlock.PART) == WritingDeskBlock.Part.LEFT
                && l.getValue(WritingDeskBlock.FACING) == Direction.SOUTH, "left: " + l);
        helper.assertTrue(r.is(HallFurniture.WRITING_DESK.get()) && r.getValue(WritingDeskBlock.PART) == WritingDeskBlock.Part.RIGHT, "right: " + r);
        helper.getLevel().destroyBlock(helper.absolutePos(left.east()), true);
        helper.assertBlockPresent(Blocks.AIR, left);
        helper.succeedWhen(() -> helper.assertItemEntityCountIs(item, left, 3.0D, 1));
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void deskNeedsRoomOnTheRight(GameTestHelper helper) {
        BlockPos left = new BlockPos(1, 3, 2);
        helper.setBlock(left.east(), Blocks.OAK_PLANKS);
        helper.assertFalse(click(helper, HallFurniture.item(HallFurniture.WRITING_DESK), left.below(), Direction.UP).consumesAction(), "placed");
        helper.assertBlockPresent(Blocks.AIR, left);
        helper.succeed();
    }

    /** Four clicks make four piles, a fifth does not grow it; breaking gives four books back. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void bookPilesAddUp(GameTestHelper helper) {
        BlockPos at = new BlockPos(2, 3, 2);
        BlockItem item = HallFurniture.item(HallFurniture.BOOK_STACK);
        helper.assertTrue(click(helper, item, at.below(), Direction.UP).consumesAction(), "first pile refused");
        for (int k = 2; k <= BookStackBlock.MAX; k++) {
            helper.assertTrue(click(helper, item, at, Direction.UP).consumesAction(), "pile " + k + " refused");
            int n = helper.getBlockState(at).getValue(BookStackBlock.STACKS);
            helper.assertTrue(n == k, "stacks " + n + ", want " + k);
        }
        click(helper, item, at, Direction.UP);
        helper.assertTrue(helper.getBlockState(at).getValue(BookStackBlock.STACKS) == BookStackBlock.MAX, "grew past 4");
        helper.getLevel().destroyBlock(helper.absolutePos(at), true);
        helper.succeedWhen(() -> helper.assertItemEntityCountIs(item, at, 2.0D, BookStackBlock.MAX));
    }

    /** Three tables in a row join; taking the middle one out frees both ends again. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void tablesJoinInARow(GameTestHelper helper) {
        BlockItem item = HallFurniture.item(HallFurniture.DINING_TABLE);
        for (int x = 1; x <= 3; x++) {
            click(helper, item, new BlockPos(x, 2, 2), Direction.UP);
        }
        BlockState mid = helper.getBlockState(new BlockPos(2, 3, 2));
        helper.assertTrue(mid.getValue(DiningTableBlock.EAST) && mid.getValue(DiningTableBlock.WEST)
                && !mid.getValue(DiningTableBlock.NORTH) && !mid.getValue(DiningTableBlock.SOUTH), "middle: " + mid);
        BlockState end = helper.getBlockState(new BlockPos(1, 3, 2));
        helper.assertTrue(end.getValue(DiningTableBlock.EAST) && !end.getValue(DiningTableBlock.WEST), "west end: " + end);
        helper.getLevel().destroyBlock(helper.absolutePos(new BlockPos(2, 3, 2)), false);
        helper.assertFalse(helper.getBlockState(new BlockPos(1, 3, 2)).getValue(DiningTableBlock.EAST), "west end still joined");
        helper.assertFalse(helper.getBlockState(new BlockPos(3, 3, 2)).getValue(DiningTableBlock.WEST), "east end still joined");
        helper.succeed();
    }

    /** Hangs only under a block, rings once per strike window, drops when its beam goes. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void bellHangsAndRings(GameTestHelper helper) {
        BlockItem item = HallFurniture.item(HallFurniture.SECT_BELL);
        helper.assertFalse(click(helper, item, new BlockPos(2, 2, 2), Direction.UP).consumesAction(), "stood on the floor");
        BlockPos beam = new BlockPos(2, 5, 2);
        BlockPos at = beam.below();
        helper.setBlock(beam, Blocks.STRIPPED_SPRUCE_LOG);
        helper.assertTrue(click(helper, item, beam, Direction.DOWN).consumesAction(), "refused under the beam");
        helper.assertBlockPresent(HallFurniture.SECT_BELL.get(), at);
        ServerLevel level = helper.getLevel();
        helper.assertTrue(SectBellBlock.ring(level, helper.absolutePos(at), Direction.NORTH, null, true), "first strike silent");
        helper.assertFalse(SectBellBlock.ring(level, helper.absolutePos(at), Direction.NORTH, null, true), "rang again at once");
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(SectBellBlock.ring(level, helper.absolutePos(at), Direction.EAST, null, true), "silent after the cooldown");
            level.destroyBlock(helper.absolutePos(beam), false);
            helper.assertBlockPresent(Blocks.AIR, at);
            helper.succeedWhen(() -> helper.assertItemEntityCountIs(item, at, 3.0D, 1));
        });
    }

    /** Right-click seats the player on an invisible seat; the seat goes with the cushion. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void cushionSeatsAPlayer(GameTestHelper helper) {
        BlockPos at = new BlockPos(2, 3, 2);
        helper.setBlock(at, HallFurniture.MEDITATION_CUSHION.get());
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(Vec3.atCenterOf(helper.absolutePos(at.north())));
        ServerLevel level = helper.getLevel();
        helper.assertTrue(MeditationCushionBlock.sit(level, helper.absolutePos(at), player), "did not sit");
        helper.assertTrue(player.getVehicle() instanceof SeatEntity, "vehicle " + player.getVehicle());
        Player other = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.assertFalse(MeditationCushionBlock.sit(level, helper.absolutePos(at), other), "two on one cushion");
        helper.runAfterDelay(3, () -> {
            // A mock player is not ticked: place it the way rideTick would.
            player.getVehicle().positionRider(player);
            double seatY = player.getY() - helper.absolutePos(at).getY();
            helper.assertTrue(seatY > -0.6D && seatY < 0.0D, "rider y offset " + seatY);
            helper.setBlock(at, Blocks.AIR);
            helper.succeedWhen(() -> {
                List<SeatEntity> seats = level.getEntitiesOfClass(SeatEntity.class, new AABB(helper.absolutePos(at)).inflate(1.0D));
                helper.assertTrue(seats.isEmpty(), "seat left behind: " + seats.size());
                helper.assertFalse(player.isPassenger(), "still seated");
            });
        });
    }

    /** Scroll shelf: two halves, the upper set on placement, one item back. */
    @GameTest(template = FLOOR, batch = "decor")
    public static void shelfTwoBlocksTall(GameTestHelper helper) {
        BlockPos lower = new BlockPos(2, 3, 2);
        BlockItem item = HallFurniture.item(HallFurniture.SCROLL_SHELF);
        helper.assertTrue(click(helper, item, lower.below(), Direction.UP).consumesAction(), "placement refused");
        BlockState up = helper.getBlockState(lower.above());
        helper.assertTrue(up.is(HallFurniture.SCROLL_SHELF.get()) && up.getValue(StoneLanternBlock.HALF) == DoubleBlockHalf.UPPER, "upper: " + up);
        int variant = PlumShelfBlock.variantAt(helper.absolutePos(lower));
        helper.assertTrue(helper.getBlockState(lower).getValue(PlumShelfBlock.VARIANT) == variant
                && up.getValue(PlumShelfBlock.VARIANT) == variant, "pink shelf halves have different fills");
        helper.getLevel().destroyBlock(helper.absolutePos(lower.above()), true);
        helper.assertBlockPresent(Blocks.AIR, lower);
        helper.succeedWhen(() -> helper.assertItemEntityCountIs(item, lower, 3.0D, 1));
    }

    private static BlockPos placeLibraryBookcase(GameTestHelper helper) {
        BlockPos lower = new BlockPos(2, 3, 2);
        BlockItem item = HallFurniture.item(HallFurniture.LIBRARY_BOOKCASE);
        helper.assertTrue(click(helper, item, lower.below(), Direction.UP).consumesAction(), "placement refused");
        BlockState lo = helper.getBlockState(lower);
        BlockState up = helper.getBlockState(lower.above());
        helper.assertTrue(lo.getValue(StoneLanternBlock.HALF) == DoubleBlockHalf.LOWER, "lower half missing");
        helper.assertTrue(up.is(HallFurniture.LIBRARY_BOOKCASE.get())
                && up.getValue(StoneLanternBlock.HALF) == DoubleBlockHalf.UPPER, "upper: " + up);
        int variant = LibraryBookcaseBlock.variantAt(helper.absolutePos(lower));
        helper.assertTrue(lo.getValue(LibraryBookcaseBlock.VARIANT) == variant
                && up.getValue(LibraryBookcaseBlock.VARIANT) == variant, "halves have different layouts");
        int slot = LibraryBookcaseBlock.bookSlotAt(helper.absolutePos(lower), false);
        helper.assertTrue(lo.getValue(LibraryBookcaseBlock.BOOK_SLOT) == slot
                && up.getValue(LibraryBookcaseBlock.BOOK_SLOT) == slot, "halves have different manual slots");
        helper.assertTrue(lo.getValue(StoneLanternBlock.FACING) == up.getValue(StoneLanternBlock.FACING), "facing differs");
        helper.assertTrue(HallFurniture.LIBRARY_BOOKCASE.get().getStateDefinition().getPossibleStates().size() == 448,
                "bookcase state count grew");
        return lower;
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void libraryBookcaseBreakUpperDropsOne(GameTestHelper helper) {
        BlockPos lower = placeLibraryBookcase(helper);
        helper.getLevel().destroyBlock(helper.absolutePos(lower.above()), true);
        helper.assertBlockPresent(Blocks.AIR, lower);
        helper.assertBlockPresent(Blocks.AIR, lower.above());
        helper.succeedWhen(() -> helper.assertItemEntityCountIs(HallFurniture.item(HallFurniture.LIBRARY_BOOKCASE), lower, 3.0D, 1));
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void libraryBookcaseBreakLowerDropsOne(GameTestHelper helper) {
        BlockPos lower = placeLibraryBookcase(helper);
        helper.getLevel().destroyBlock(helper.absolutePos(lower), true);
        helper.assertBlockPresent(Blocks.AIR, lower.above());
        helper.succeedWhen(() -> helper.assertItemEntityCountIs(HallFurniture.item(HallFurniture.LIBRARY_BOOKCASE), lower, 3.0D, 1));
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void libraryBookcaseNeedsRoomAbove(GameTestHelper helper) {
        BlockPos lower = new BlockPos(2, 3, 2);
        helper.setBlock(lower.above(), Blocks.OAK_PLANKS);
        helper.assertFalse(click(helper, HallFurniture.item(HallFurniture.LIBRARY_BOOKCASE), lower.below(), Direction.UP)
                .consumesAction(), "placed through occupied upper block");
        helper.assertBlockPresent(Blocks.AIR, lower);
        helper.assertBlockPresent(Blocks.OAK_PLANKS, lower.above());
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void libraryBookcaseCreativeBreakDropsNothing(GameTestHelper helper) {
        BlockPos lower = placeLibraryBookcase(helper);
        ServerLevel level = helper.getLevel();
        BlockPos up = helper.absolutePos(lower.above());
        BlockState state = level.getBlockState(up);
        state.getBlock().playerWillDestroy(level, up, state, helper.makeMockPlayer(GameType.CREATIVE));
        level.removeBlock(up, false);
        helper.assertBlockPresent(Blocks.AIR, lower);
        helper.runAfterDelay(2, () -> {
            helper.assertItemEntityCountIs(HallFurniture.item(HallFurniture.LIBRARY_BOOKCASE), lower, 3.0D, 0);
            helper.succeed();
        });
    }
    private static BlockPos placeTallBookcase(GameTestHelper helper) {
        BlockPos lower = new BlockPos(2, 3, 2);
        BlockItem item = HallFurniture.item(HallFurniture.LIBRARY_BOOKCASE_TALL);
        // Explicit support; the 8-high yard leaves headroom for all three parts.
        helper.setBlock(lower.below(), net.minecraft.world.level.block.Blocks.STONE);
        helper.assertTrue(click(helper, item, lower.below(), Direction.UP).consumesAction(), "tall placement refused");
        int variant = LibraryBookcaseBlock.variantAt(helper.absolutePos(lower));
        for (TallLibraryBookcaseBlock.Part part : TallLibraryBookcaseBlock.Part.values()) {
            BlockState state = helper.getBlockState(lower.above(part.ordinal()));
            helper.assertTrue(state.is(HallFurniture.LIBRARY_BOOKCASE_TALL.get())
                    && state.getValue(TallLibraryBookcaseBlock.PART) == part
                    && state.getValue(LibraryBookcaseBlock.VARIANT) == variant
                    && state.getValue(TallLibraryBookcaseBlock.BOOK_SLOT) == LibraryBookcaseBlock.bookSlotAt(helper.absolutePos(lower), true)
                    && state.getValue(TallLibraryBookcaseBlock.FACING) == Direction.SOUTH, "bad tall rack part " + part);
        }
        helper.assertTrue(HallFurniture.LIBRARY_BOOKCASE_TALL.get().getStateDefinition().getPossibleStates().size() == 1008,
                "tall state explosion");
        return lower;
    }

    private static void breakTall(GameTestHelper helper, int dy) {
        BlockPos lower = placeTallBookcase(helper);
        helper.getLevel().destroyBlock(helper.absolutePos(lower.above(dy)), true);
        for (int y = 0; y < 3; y++) helper.assertBlockPresent(Blocks.AIR, lower.above(y));
        helper.succeedWhen(() -> helper.assertItemEntityCountIs(
                HallFurniture.item(HallFurniture.LIBRARY_BOOKCASE_TALL), lower, 4.0D, 1));
    }

    @GameTest(template = "sect_yard", batch = "decor")
    public static void tallBookcaseBreakLower(GameTestHelper helper) { breakTall(helper, 0); }
    @GameTest(template = "sect_yard", batch = "decor")
    public static void tallBookcaseBreakMiddle(GameTestHelper helper) { breakTall(helper, 1); }
    @GameTest(template = "sect_yard", batch = "decor")
    public static void tallBookcaseBreakUpper(GameTestHelper helper) { breakTall(helper, 2); }

    @GameTest(template = "sect_yard", batch = "decor")
    public static void tallBookcaseRefusesBlockedTop(GameTestHelper helper) {
        BlockPos lower = new BlockPos(2, 3, 2);
        helper.setBlock(lower.below(), Blocks.STONE);
        helper.setBlock(lower.above(2), Blocks.STONE);
        helper.assertFalse(click(helper, HallFurniture.item(HallFurniture.LIBRARY_BOOKCASE_TALL),
                lower.below(), Direction.UP).consumesAction(), "placed through occupied top");
        helper.assertBlockPresent(Blocks.AIR, lower);
        helper.assertBlockPresent(Blocks.AIR, lower.above());
        helper.succeed();
    }

    @GameTest(template = "sect_yard", batch = "decor")
    public static void tallBookcaseCreativeBreak(GameTestHelper helper) {
        BlockPos lower = placeTallBookcase(helper);
        ServerLevel level = helper.getLevel();
        BlockPos top = helper.absolutePos(lower.above(2));
        BlockState state = level.getBlockState(top);
        state.getBlock().playerWillDestroy(level, top, state, helper.makeMockPlayer(GameType.CREATIVE));
        level.removeBlock(top, false);
        for (int y = 0; y < 3; y++) helper.assertBlockPresent(Blocks.AIR, lower.above(y));
        helper.runAfterDelay(2, () -> {
            helper.assertItemEntityCountIs(HallFurniture.item(HallFurniture.LIBRARY_BOOKCASE_TALL), lower, 4.0D, 0);
            helper.succeed();
        });
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void legacyManualShelfItemPlacesLibraryBookcase(GameTestHelper helper) {
        BlockPos lower = new BlockPos(2, 3, 2);
        helper.assertTrue(click(helper, io.github.verycooltimo.murim.library.ModLibrary.MANUAL_SHELF_ITEM.get(),
                lower.below(), Direction.UP).consumesAction(), "legacy item placement refused");
        helper.assertBlockPresent(HallFurniture.LIBRARY_BOOKCASE.get(), lower);
        helper.assertBlockPresent(HallFurniture.LIBRARY_BOOKCASE.get(), lower.above());
        helper.assertTrue(HallFurniture.LIBRARY_BOOKCASE.get().asItem() == HallFurniture.item(HallFurniture.LIBRARY_BOOKCASE),
                "legacy item replaced canonical block item");
        helper.succeed();
    }
}
