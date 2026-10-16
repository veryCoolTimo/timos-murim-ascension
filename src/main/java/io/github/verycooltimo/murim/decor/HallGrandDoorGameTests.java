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
 * GameTests of the 4x6 grand hall door: placement of the twenty-four parts, refusal when a spot is taken, breaking any part
 * takes the whole gate with one item back (none in creative), opening and closing with passability.
 *
 * <p>Placement goes through {@code BlockItem#place} with no player: the context's horizontal direction is NORTH
 * (reference/minecraft-src/net/minecraft/world/item/context/UseOnContext.java#getHorizontalDirection), so the gate
 * faces SOUTH and the viewer in front looks north: their right is EAST (+x). The clicked spot is COL 1, so the gate
 * spans x 5..8 when clicked at x 6. Template murim:sect_yard (24x8x24), rows y 2..7.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HallGrandDoorGameTests {

    private static final String YARD = "sect_yard";
    private static final BlockPos CLICK = new BlockPos(6, 2, 6);
    private static final BlockPos ORIGIN = new BlockPos(5, 2, 6);

    private HallGrandDoorGameTests() {
    }

    private static BlockPos part(int col, int row) {
        return ORIGIN.east(col).above(row);
    }

    private static InteractionResult place(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(CLICK);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.SOUTH, pos, false);
        ItemStack stack = new ItemStack(HallGrandDoors.HALL_GRAND_DOOR_ITEM.get());
        return HallGrandDoors.HALL_GRAND_DOOR_ITEM.get().place(new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, stack, hit));
    }

    private static void assertGate(GameTestHelper helper, boolean open) {
        for (int col = 0; col < HallGrandDoorBlock.WIDTH; col++) {
            for (int row = 0; row < HallGrandDoorBlock.HEIGHT; row++) {
                BlockState s = helper.getBlockState(part(col, row));
                helper.assertTrue(s.is(HallGrandDoors.HALL_GRAND_DOOR.get()) && s.getValue(HallGrandDoorBlock.COL) == col
                        && s.getValue(HallGrandDoorBlock.ROW) == row && s.getValue(HallGrandDoorBlock.FACING) == Direction.SOUTH
                        && s.getValue(HallGrandDoorBlock.OPEN) == open, "part " + col + "," + row + " open=" + open + ": " + s);
                boolean passable = s.getCollisionShape(helper.getLevel(), helper.absolutePos(part(col, row))).isEmpty();
                helper.assertTrue(passable == open, "part " + col + "," + row + " passable=" + passable + " but open=" + open);
            }
        }
    }

    private static void assertNoGate(GameTestHelper helper) {
        for (int col = 0; col < HallGrandDoorBlock.WIDTH; col++) {
            for (int row = 0; row < HallGrandDoorBlock.HEIGHT; row++) {
                helper.assertBlockPresent(Blocks.AIR, part(col, row));
            }
        }
    }

    @GameTest(template = YARD, batch = "hall_grand_door")
    public static void gatePlacesSixteenParts(GameTestHelper helper) {
        helper.assertTrue(place(helper).consumesAction(), "placement refused");
        assertGate(helper, false);
        helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(ORIGIN)) instanceof HallGrandDoorBlockEntity,
                "no block entity at the origin");
        helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(part(2, 2))) == null, "block entity off the origin");
        helper.succeed();
    }

    @GameTest(template = YARD, batch = "hall_grand_door")
    public static void gateBlockedSpotRefuses(GameTestHelper helper) {
        helper.setBlock(part(3, 3), Blocks.OAK_PLANKS);
        helper.assertFalse(place(helper).consumesAction(), "placed with a part blocked");
        for (int col = 0; col < HallGrandDoorBlock.WIDTH; col++) {
            for (int row = 0; row < HallGrandDoorBlock.HEIGHT; row++) {
                if (col != 3 || row != 3) {
                    helper.assertBlockPresent(Blocks.AIR, part(col, row));
                }
            }
        }
        helper.assertBlockPresent(Blocks.OAK_PLANKS, part(3, 3));
        helper.succeed();
    }

    @GameTest(template = YARD, batch = "hall_grand_door")
    public static void gateBreakTopRightDropsOne(GameTestHelper helper) {
        breakOne(helper, part(3, 3));
    }

    @GameTest(template = YARD, batch = "hall_grand_door")
    public static void gateBreakMiddleDropsOne(GameTestHelper helper) {
        breakOne(helper, part(2, 1));
    }

    private static void breakOne(GameTestHelper helper, BlockPos which) {
        place(helper);
        assertGate(helper, false);
        helper.getLevel().destroyBlock(helper.absolutePos(which), true);
        assertNoGate(helper);
        helper.runAfterDelay(2, () -> {
            helper.assertItemEntityCountIs(HallGrandDoors.HALL_GRAND_DOOR_ITEM.get(), part(2, 2), 8.0D, 1);
            helper.succeed();
        });
    }

    @GameTest(template = YARD, batch = "hall_grand_door")
    public static void gateCreativeBreakDropsNothing(GameTestHelper helper) {
        place(helper);
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(part(3, 2));
        BlockState state = level.getBlockState(at);
        state.getBlock().playerWillDestroy(level, at, state, player);
        level.removeBlock(at, false);
        assertNoGate(helper);
        helper.runAfterDelay(2, () -> {
            helper.assertItemEntityCountIs(HallGrandDoors.HALL_GRAND_DOOR_ITEM.get(), part(2, 2), 8.0D, 0);
            helper.succeed();
        });
    }

    /**
     * Right-click: solid while the leaves swing open, passable once they are open; closing makes it solid at once;
     * a click during a swing is ignored.
     */
    @GameTest(template = YARD, batch = "hall_grand_door", timeoutTicks = 120)
    public static void gateOpensThenPasses(GameTestHelper helper) {
        place(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.useBlock(part(1, 1), player);
        assertGate(helper, false);
        helper.runAfterDelay(HallGrandDoorBlock.SWING_TICKS / 2, () -> {
            assertGate(helper, false);
            helper.useBlock(part(2, 0), player); // mid-swing: ignored
        });
        helper.runAfterDelay(HallGrandDoorBlock.SWING_TICKS + 3, () -> {
            assertGate(helper, true);
            helper.useBlock(part(3, 3), player);
            assertGate(helper, false);
        });
        helper.runAfterDelay(2 * HallGrandDoorBlock.SWING_TICKS + 8, () -> {
            assertGate(helper, false);
            helper.useBlock(part(0, 2), player);
        });
        helper.runAfterDelay(3 * HallGrandDoorBlock.SWING_TICKS + 12, () -> {
            assertGate(helper, true);
            helper.succeed();
        });
    }

    /** A redstone block next to any part opens the gate; taking it away closes it. */
    @GameTest(template = YARD, batch = "hall_grand_door", timeoutTicks = 120)
    public static void gateRedstoneOpensAndCloses(GameTestHelper helper) {
        place(helper);
        helper.setBlock(part(0, 0).west(), Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(HallGrandDoorBlock.SWING_TICKS + 3, () -> {
            assertGate(helper, true);
            helper.setBlock(part(0, 0).west(), Blocks.AIR);
            assertGate(helper, false);
            helper.succeed();
        });
    }
}
