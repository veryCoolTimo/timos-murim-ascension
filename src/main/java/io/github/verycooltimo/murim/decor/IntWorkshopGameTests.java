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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Runtime checks for paired removal, placement obstruction, switching and collision; uses the existing floor template. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class IntWorkshopGameTests {
    private static final String FLOOR = "small_floor";
    private static final BlockPos AT = new BlockPos(1, 3, 1);

    private IntWorkshopGameTests() {
    }

    private static boolean place(GameTestHelper helper, BlockItem item, BlockPos support) {
        BlockPos pos = helper.absolutePos(support);
        return item.place(new BlockPlaceContext(helper.getLevel(), null, InteractionHand.MAIN_HAND, new ItemStack(item),
                new BlockHitResult(Vec3.atCenterOf(pos).add(0, .5, 0), Direction.UP, pos, false))).consumesAction();
    }

    private static void click(GameTestHelper helper, Player player, BlockPos pos) {
        BlockPos abs = helper.absolutePos(pos);
        helper.getBlockState(pos).useWithoutItem(helper.getLevel(), player,
                new BlockHitResult(Vec3.atCenterOf(abs), Direction.NORTH, abs, false));
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void cabinetUpperBreakDropsOneItem(GameTestHelper helper) {
        helper.setBlock(AT.below(), Blocks.STONE);
        helper.assertTrue(place(helper, IntWorkshop.item(IntWorkshop.PILL_CABINET), AT.below()), "cabinet placement");
        BlockState low = helper.getBlockState(AT);
        BlockState high = helper.getBlockState(AT.above());
        helper.assertTrue(low.getValue(StoScreenBlock.HALF) == DoubleBlockHalf.LOWER
                && high.getValue(StoScreenBlock.HALF) == DoubleBlockHalf.UPPER, "cabinet halves");
        helper.assertTrue(low.getValue(StoScreenBlock.FACING) == high.getValue(StoScreenBlock.FACING), "paired facing");
        helper.assertTrue(high.getShape(helper.getLevel(), helper.absolutePos(AT.above())).max(Direction.Axis.Y) == 1,
                "upper drawer panel must be a full block tall");
        helper.getLevel().destroyBlock(helper.absolutePos(AT.above()), true);
        helper.assertBlockPresent(Blocks.AIR, AT);
        helper.succeedWhen(() -> helper.assertItemEntityCountIs(IntWorkshop.item(IntWorkshop.PILL_CABINET), AT, 4, 1));
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void cabinetNeedsRoomAbove(GameTestHelper helper) {
        helper.setBlock(AT.below(), Blocks.STONE);
        helper.setBlock(AT.above(), Blocks.STONE);
        helper.assertTrue(!place(helper, IntWorkshop.item(IntWorkshop.PILL_CABINET), AT.below()), "placed through ceiling");
        helper.assertBlockPresent(Blocks.AIR, AT);
        helper.assertBlockPresent(Blocks.STONE, AT.above());
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void heatersToggleTheirLight(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        IntHeaterBlock[] heaters = {IntWorkshop.ALCHEMY_FURNACE.get(), IntWorkshop.KITCHEN_STOVE.get()};
        for (int i = 0; i < heaters.length; i++) {
            BlockPos pos = AT.east(i * 2);
            helper.setBlock(pos, heaters[i].defaultBlockState());
            helper.assertTrue(helper.getBlockState(pos).getLightEmission() == 0, "unlit light");
            click(helper, player, pos);
            helper.assertTrue(helper.getBlockState(pos).getValue(IntHeaterBlock.LIT)
                    && helper.getBlockState(pos).getLightEmission() == 10, "lit light");
            click(helper, player, pos);
            helper.assertTrue(!helper.getBlockState(pos).getValue(IntHeaterBlock.LIT)
                    && helper.getBlockState(pos).getLightEmission() == 0, "switch off");
        }
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void tabletopVariantsHaveNoCollisionAndAreNotCycledByRightClick(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        IntTabletopBlock[] blocks = {IntWorkshop.TABLEWARE.get(), IntWorkshop.HERB_JARS.get(), IntWorkshop.TEA_TRAY.get(),
                IntWorkshop.ABACUS_LEDGER.get(), IntWorkshop.SCALES_MORTAR.get(), IntWorkshop.PILL_BOX.get()};
        for (IntTabletopBlock block : blocks) {
            helper.setBlock(AT, block.defaultBlockState());
            BlockState start = helper.getBlockState(AT);
            helper.assertTrue(start.getCollisionShape(helper.getLevel(), helper.absolutePos(AT)).isEmpty(), "tabletop collision");
            helper.assertTrue(!start.getShape(helper.getLevel(), helper.absolutePos(AT)).isEmpty(), "no selection shape");
            IntegerProperty variant = block.variantProperty();
            if (variant != null) {
                // Author 19.10: a right click on the tea blocks changes nothing, whatever the variant is.
                int count = variant.getPossibleValues().size();
                for (int v = 0; v < count; v++) {
                    helper.setBlock(AT, block.defaultBlockState().setValue(variant, v));
                    BlockState before = helper.getBlockState(AT);
                    for (int step = 0; step < 4; step++) {
                        click(helper, player, AT);
                    }
                    helper.assertTrue(helper.getBlockState(AT) == before, "a right click changed " + block + ": " + before + " -> " + helper.getBlockState(AT));
                }
            }
        }
        helper.succeed();
    }

    /** The lacquer pill box (24.10): it drops itself when mined and has a small selection shape inside its block (the lowering on
     *  tables of every height is covered with the other props by TabletopSinkGameTests.workshopPropsSitOnTables). */
    @GameTest(template = FLOOR, batch = "decor")
    public static void pillBoxDropsItselfAndStaysInsideTheBlock(GameTestHelper helper) {
        helper.setBlock(AT.below(), Blocks.STONE);
        helper.assertTrue(place(helper, IntWorkshop.item(IntWorkshop.PILL_BOX), AT.below()), "pill box placement");
        BlockState state = helper.getBlockState(AT);
        helper.assertTrue(state.is(IntWorkshop.PILL_BOX.get()), "pill box block");
        net.minecraft.world.phys.shapes.VoxelShape shape = state.getShape(helper.getLevel(), helper.absolutePos(AT));
        helper.assertTrue(shape.min(Direction.Axis.X) >= 0 && shape.max(Direction.Axis.X) <= 1
                && shape.min(Direction.Axis.Z) >= 0 && shape.max(Direction.Axis.Z) <= 1, "shape leaves the block");
        helper.assertTrue(shape.max(Direction.Axis.Y) < 0.4, "a pill box is a small prop, not a block-tall one");
        helper.getLevel().destroyBlock(helper.absolutePos(AT), true);
        helper.assertBlockPresent(Blocks.AIR, AT);
        helper.succeedWhen(() -> helper.assertItemEntityCountIs(IntWorkshop.item(IntWorkshop.PILL_BOX), AT, 4, 1));
    }

    /** The look is chosen when the block is placed and differs from place to place (so a row of trays is not one tray repeated). */
    @GameTest(template = FLOOR, batch = "decor")
    public static void tabletopVariantIsChosenAtPlacement(GameTestHelper helper) {
        IntTabletopBlock[] blocks = {IntWorkshop.TABLEWARE.get(), IntWorkshop.HERB_JARS.get(), IntWorkshop.TEA_TRAY.get()};
        for (IntTabletopBlock block : blocks) {
            IntegerProperty variant = block.variantProperty();
            helper.assertTrue(variant != null, "no variants on " + block);
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (int x = 0; x < 24; x++) {
                BlockPos at = helper.absolutePos(new BlockPos(x, 3, 1 + (x % 5)));
                BlockPlaceContext context = new BlockPlaceContext(helper.getLevel(), null, InteractionHand.MAIN_HAND,
                        new ItemStack(block), new BlockHitResult(Vec3.atCenterOf(at).add(0, -.5, 0), Direction.UP, at.below(), false));
                BlockState placed = block.getStateForPlacement(context);
                helper.assertTrue(placed != null, "not placeable: " + block);
                seen.add(placed.getValue(variant));
                // the same place always gives the same look: placing again does not re-roll
                helper.assertTrue(block.getStateForPlacement(context).getValue(variant) == placed.getValue(variant), "the variant changes between two placements at one spot");
            }
            helper.assertTrue(seen.size() == variant.getPossibleValues().size(), block + " shows only " + seen + " over 24 places");
        }
        helper.succeed();
    }
}
