package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
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
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real BlockItem placement and neighbour updates for each tabletop family; no client model baking in GameTest. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class TabletopSinkGameTests {
    private static final BlockPos AT = new BlockPos(1, 3, 1);
    // Integer pixel offsets round a fractional tabletop by at most half a pixel.
    private static final double HALF_PIXEL = 0.5D / 16.0D + 1.0e-7D;

    private TabletopSinkGameTests() {
    }

    private record Support(Block block, double topPx) {
    }

    private static Support[] supports() {
        return new Support[] {
                new Support(IntSeating.LOW_DESK.get(), 9),
                new Support(IntSeating.TEA_TABLE.get(), 12.4),
                new Support(IntSeating.LONG_TABLE.get(), 14),
                new Support(IntSeating.BENCH.get(), 9),
                new Support(IntStore.CHEST.get(), 12),
                new Support(IntSeating.TEA_TABLE_ROUND.get(), 12.4),
                new Support(IntSeating.WRITING_DESK.get(), 13),
                new Support(HallFurniture.DINING_TABLE.get(), 10),
                // The OBJ reaches 8.6 px; its existing collision top is rounded to 9.
                new Support(SectOrnaments.TEA_TABLE.get(), 8.6),
                new Support(Blocks.STONE, 16),
                new Support(Blocks.OAK_SLAB, 8),
        };
    }

    private static boolean place(GameTestHelper helper, BlockItem item, BlockPos target, double topPx) {
        BlockPos pos = helper.absolutePos(target);
        return item.place(new BlockPlaceContext(helper.getLevel(), null, InteractionHand.MAIN_HAND, new ItemStack(item),
                new BlockHitResult(new Vec3(pos.getX() + .5, pos.getY() + topPx / 16.0D, pos.getZ() + .5),
                        Direction.UP, pos, false))).consumesAction();
    }

    private static void assertSeated(GameTestHelper helper, BlockState state, double topPx) {
        BlockPos pos = helper.absolutePos(AT);
        VoxelShape selection = state.getShape(helper.getLevel(), pos);
        double bottom = 1 + selection.min(Direction.Axis.Y);
        helper.assertTrue(Math.abs(bottom - topPx / 16.0D) <= HALF_PIXEL,
                "selection floats/sinks: " + state + ", bottom=" + bottom + ", top=" + topPx / 16.0D);
        helper.assertTrue(Math.abs(selection.min(Direction.Axis.Y)
                + state.getValue(TabletopSupport.SINK) / 16.0D) < 1.0e-7D, "shape disagrees with sink: " + state);
        VoxelShape collision = state.getCollisionShape(helper.getLevel(), pos);
        if (!collision.isEmpty()) {
            helper.assertTrue(Math.abs(1 + collision.min(Direction.Axis.Y) - topPx / 16.0D) <= HALF_PIXEL,
                    "collision floats/sinks: " + state);
        }
    }

    /** The tea tray refuses the tea tables: their model already carries a tea set (author 19.10). */
    private static boolean refuses(Block block, Support support) {
        return block instanceof IntTabletopBlock.TeaTray && IntTabletopBlock.TeaTray.ownsTeaSet(support.block().defaultBlockState());
    }

    private static void family(GameTestHelper helper, BlockItem... items) {
        BlockPos below = helper.absolutePos(AT.below());
        BlockPos pos = helper.absolutePos(AT);
        for (BlockItem item : items) {
            Block block = item.getBlock();
            for (Support support : supports()) {
                if (refuses(block, support)) {
                    continue;
                }
                helper.setBlock(AT, Blocks.AIR);
                helper.setBlock(AT.below(), support.block());
                helper.assertTrue(place(helper, item, AT.below(), support.topPx()), "placement refused: " + item + " on " + support);
                BlockState state = helper.getBlockState(AT);
                helper.assertTrue(state.is(block), "wrong placed block");
                assertSeated(helper, state, support.topPx());
                helper.assertTrue(state.getValue(TabletopSupport.SINK) == (int) Math.round(16 - support.topPx()),
                        "wrong sink: " + state + " on " + support);
                for (Rotation rotation : Rotation.values()) {
                    BlockState rotated = state.rotate(rotation);
                    helper.assertTrue(rotated.getValue(TabletopSupport.SINK).equals(state.getValue(TabletopSupport.SINK)),
                            "rotation lost sink");
                    assertSeated(helper, rotated, support.topPx());
                }
                for (Mirror mirror : Mirror.values()) {
                    BlockState mirrored = state.mirror(mirror);
                    helper.assertTrue(mirrored.getValue(TabletopSupport.SINK).equals(state.getValue(TabletopSupport.SINK)),
                            "mirror lost sink");
                    assertSeated(helper, mirrored, support.topPx());
                }
                // Every facing, stack count and appearance keeps its shape offset.
                for (BlockState appearance : block.getStateDefinition().getPossibleStates()) {
                    if (appearance.getValue(TabletopSupport.SINK).equals(state.getValue(TabletopSupport.SINK))) {
                        assertSeated(helper, appearance, support.topPx());
                    }
                }
            }
            // Replace the block below a live prop: updateShape must update only SINK.
            for (Support support : supports()) {
                if (refuses(block, support)) {
                    continue;
                }
                BlockState before = helper.getBlockState(AT);
                helper.getLevel().setBlockAndUpdate(below, support.block().defaultBlockState());
                BlockState after = helper.getBlockState(AT);
                helper.assertTrue(after.is(block), "prop lost on support change: " + item);
                helper.assertTrue(after.setValue(TabletopSupport.SINK, before.getValue(TabletopSupport.SINK)).equals(before),
                        "support update changed appearance/facing/count");
                assertSeated(helper, after, support.topPx());
            }
            helper.getLevel().setBlockAndUpdate(below, Blocks.WHITE_CARPET.defaultBlockState());
            if (block instanceof BookStackBlock) {
                helper.assertBlockPresent(Blocks.AIR, AT);
            } else {
                helper.assertTrue(helper.getBlockState(AT).getValue(TabletopSupport.SINK) == 0, "short support fallback");
                helper.getLevel().setBlockAndUpdate(below, Blocks.AIR.defaultBlockState());
                helper.assertTrue(helper.getBlockState(AT).getValue(TabletopSupport.SINK) == 0, "empty support fallback");
            }
            helper.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        helper.succeed();
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void workshopPropsSitOnTables(GameTestHelper helper) {
        family(helper, IntWorkshop.item(IntWorkshop.TEA_TRAY), IntWorkshop.item(IntWorkshop.TABLEWARE),
                IntWorkshop.item(IntWorkshop.ABACUS_LEDGER), IntWorkshop.item(IntWorkshop.SCALES_MORTAR),
                IntWorkshop.item(IntWorkshop.HERB_JARS), IntWorkshop.item(IntWorkshop.PILL_BOX));
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void booksSitOnTables(GameTestHelper helper) {
        family(helper, HallFurniture.item(HallFurniture.BOOK_STACK));
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void shrineOfferingsSitOnTables(GameTestHelper helper) {
        family(helper, ShrineDecor.item(ShrineDecor.CANDLE_DRAGON), ShrineDecor.item(ShrineDecor.INCENSE_DING),
                ShrineDecor.item(ShrineDecor.INCENSE_TRIPOD_SMALL));
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void bonsaiSitOnTables(GameTestHelper helper) {
        family(helper, IntStore.item(IntStore.BONSAI_PINE), IntStore.item(IntStore.BONSAI_PLUM));
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void swordStandSitsOnTables(GameTestHelper helper) {
        family(helper, WeaponRacks.SWORD_STAND_ITEM.get());
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void weaponRackKeepsItsFloorHeight(GameTestHelper helper) {
        BlockPos below = helper.absolutePos(AT.below());
        BlockPos pos = helper.absolutePos(AT);
        for (Support support : supports()) {
            helper.setBlock(AT, Blocks.AIR);
            helper.setBlock(AT.below(), support.block());
            helper.assertTrue(place(helper, WeaponRacks.TRAINING_SWORD_RACK_ITEM.get(), AT.below(), support.topPx()),
                    "rack placement refused on " + support);
            BlockState state = helper.getBlockState(AT);
            helper.assertTrue(state.is(WeaponRacks.TRAINING_SWORD_RACK.get()), "wrong placed rack");
            helper.assertTrue(state.getValue(TabletopSupport.SINK) == 0, "rack sank on placement");
            for (Support replacement : supports()) {
                helper.getLevel().setBlockAndUpdate(below, replacement.block().defaultBlockState());
                BlockState after = helper.getBlockState(AT);
                helper.assertTrue(after.equals(state), "rack changed with support");
                helper.assertTrue(after.getShape(helper.getLevel(), pos).min(Direction.Axis.Y) == 0,
                        "rack shape lowered");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void booksKeepTheirSinkWhenAddingPiles(GameTestHelper helper) {
        helper.setBlock(AT.below(), IntSeating.LOW_DESK.get());
        BlockItem item = HallFurniture.item(HallFurniture.BOOK_STACK);
        helper.assertTrue(place(helper, item, AT.below(), 9), "first pile");
        for (int count = 2; count <= BookStackBlock.MAX; count++) {
            BlockState before = helper.getBlockState(AT);
            double localTop = before.getShape(helper.getLevel(), helper.absolutePos(AT)).max(Direction.Axis.Y) * 16;
            helper.assertTrue(place(helper, item, AT, localTop), "adding pile " + count);
            BlockState state = helper.getBlockState(AT);
            helper.assertTrue(state.getValue(BookStackBlock.STACKS) == count, "pile count");
            helper.assertTrue(state.getValue(TabletopSupport.SINK) == 7, "lost sink on stacking");
            assertSeated(helper, state, 9);
        }
        helper.getLevel().setBlockAndUpdate(helper.absolutePos(AT.below()), Blocks.WHITE_CARPET.defaultBlockState());
        helper.assertBlockPresent(Blocks.AIR, AT);
        helper.succeed();
    }
}
