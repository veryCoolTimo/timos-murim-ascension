package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** GameTests of the sto_* interior pieces: the bed is a real BedBlock, the screen is two blocks, rugs and dividers join, storage keeps items. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class IntStoreGameTests {
    private static final String FLOOR = "small_floor";

    private IntStoreGameTests() {
    }

    private static boolean place(GameTestHelper helper, BlockItem item, BlockPos support) {
        BlockPos pos = helper.absolutePos(support);
        Vec3 at = Vec3.atCenterOf(pos).add(0, 0.5, 0);
        return item.place(new BlockPlaceContext(helper.getLevel(), null, InteractionHand.MAIN_HAND, new ItemStack(item),
                new BlockHitResult(at, Direction.UP, pos, false))).consumesAction();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void bedIsTwoBlocksAndABed(GameTestHelper helper) {
        BlockPos foot = new BlockPos(1, 3, 1);
        helper.assertTrue(place(helper, IntStore.item(IntStore.BED), foot.below()), "placement refused");
        BlockState f = helper.getBlockState(foot);
        helper.assertTrue(f.getBlock() instanceof BedBlock && f.getValue(BedBlock.PART) == BedPart.FOOT, "foot: " + f);
        BlockPos headPos = foot.relative(f.getValue(BedBlock.FACING));
        BlockState h = helper.getBlockState(headPos);
        helper.assertTrue(h.is(IntStore.BED.get()) && h.getValue(BedBlock.PART) == BedPart.HEAD, "head: " + h);
        helper.assertTrue(h.isBed(helper.getLevel(), helper.absolutePos(headPos), null), "not a bed for sleeping");
        helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(headPos)) == null, "bed has a block entity");
        helper.getLevel().destroyBlock(helper.absolutePos(foot), true);
        helper.assertBlockPresent(Blocks.AIR, headPos);
        helper.succeedWhen(() -> helper.assertItemEntityCountIs(IntStore.item(IntStore.BED), foot, 4.0D, 1));
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void screenIsTwoBlocksTall(GameTestHelper helper) {
        BlockPos low = new BlockPos(1, 3, 1);
        helper.assertTrue(place(helper, IntStore.item(IntStore.SCREEN), low.below()), "placement refused");
        helper.assertTrue(helper.getBlockState(low).getValue(StoScreenBlock.HALF) == DoubleBlockHalf.LOWER, "lower");
        helper.assertTrue(helper.getBlockState(low.above()).getValue(StoScreenBlock.HALF) == DoubleBlockHalf.UPPER, "upper");
        helper.getLevel().destroyBlock(helper.absolutePos(low.above()), true);
        helper.assertBlockPresent(Blocks.AIR, low);
        helper.succeedWhen(() -> helper.assertItemEntityCountIs(IntStore.item(IntStore.SCREEN), low, 4.0D, 1));
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void rugsAndDividersJoin(GameTestHelper helper) {
        BlockPos a = new BlockPos(1, 3, 1);
        helper.assertTrue(place(helper, IntStore.item(IntStore.RUG_RED), a.below()), "rug a");
        helper.assertTrue(place(helper, IntStore.item(IntStore.RUG_RED), a.east().below()), "rug b");
        helper.assertTrue(helper.getBlockState(a).getValue(StoRugBlock.EAST), "a east");
        helper.assertTrue(helper.getBlockState(a.east()).getValue(StoRugBlock.WEST), "b west");
        helper.assertFalse(helper.getBlockState(a).getValue(StoRugBlock.WEST), "a west");
        BlockPos d = new BlockPos(1, 3, 3);
        helper.assertTrue(place(helper, IntStore.item(IntStore.DIVIDER), d.below()), "divider a");
        helper.assertTrue(place(helper, IntStore.item(IntStore.DIVIDER), d.east().below()), "divider b");
        helper.assertTrue(helper.getBlockState(d).getValue(StoDividerBlock.LEFT) || helper.getBlockState(d).getValue(StoDividerBlock.RIGHT), "joined");
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void storageKeepsItems(GameTestHelper helper) {
        BlockPos c = new BlockPos(1, 3, 1);
        BlockPos k = new BlockPos(3, 3, 1);
        helper.assertTrue(place(helper, IntStore.item(IntStore.CHEST), c.below()), "chest");
        helper.assertTrue(place(helper, IntStore.item(IntStore.CABINET), k.below()), "cabinet");
        StoStorageBlockEntity chest = (StoStorageBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(c));
        StoStorageBlockEntity cab = (StoStorageBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(k));
        helper.assertTrue(chest != null && chest.getContainerSize() == 27, "chest size");
        helper.assertTrue(cab != null && cab.getContainerSize() == 36, "cabinet size");
        chest.setItem(5, new ItemStack(Items.DIAMOND, 3));
        helper.assertTrue(chest.getItem(5).getCount() == 3, "item kept");
        helper.getLevel().destroyBlock(helper.absolutePos(c), true);
        helper.succeedWhen(() -> helper.assertItemEntityCountIs(Items.DIAMOND, c, 4.0D, 3));
    }
}
