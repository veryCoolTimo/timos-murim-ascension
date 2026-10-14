package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.build.PillarBridgeBlockEntity;
import io.github.verycooltimo.murim.world.build.RedPillarBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Integration checks for the new double-height item and shared pillar bridge entity. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HallJoineryGameTests {

    private HallJoineryGameTests() {
    }

    private static InteractionResult placeDoor(GameTestHelper helper, BlockPos support) {
        BlockPos absolute = helper.absolutePos(support);
        var item = HallJoinery.HALL_DOOR_ITEM.get();
        var hit = new BlockHitResult(Vec3.atCenterOf(absolute).add(0, 0.5, 0), Direction.UP, absolute, false);
        return item.place(new BlockPlaceContext(helper.getLevel(), null, InteractionHand.MAIN_HAND,
                new ItemStack(item), hit));
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void doorsPairOpenAndDropOnce(GameTestHelper helper) {
        BlockPos a = new BlockPos(2, 3, 2);
        BlockPos b = a.east();
        helper.setBlock(a.below(), Blocks.STONE);
        helper.setBlock(b.below(), Blocks.STONE);
        helper.assertTrue(placeDoor(helper, a.below()).consumesAction(), "first door refused");
        helper.assertTrue(placeDoor(helper, b.below()).consumesAction(), "second door refused");
        var sa = helper.getBlockState(a);
        var sb = helper.getBlockState(b);
        helper.assertTrue(sa.getValue(DoorBlock.FACING) == sb.getValue(DoorBlock.FACING)
                && sa.getValue(DoorBlock.HINGE) != sb.getValue(DoorBlock.HINGE), "pair did not choose opposite hinges");
        HallJoinery.HALL_DOOR.get().setOpen(null, helper.getLevel(), sa, helper.absolutePos(a), true);
        helper.assertTrue(helper.getBlockState(a).getValue(DoorBlock.OPEN)
                && helper.getBlockState(a.above()).getValue(DoorBlock.OPEN), "halves did not open together");
        helper.getLevel().destroyBlock(helper.absolutePos(a), true);
        helper.assertBlockPresent(Blocks.AIR, a.above());
        helper.assertItemEntityCountIs(HallJoinery.HALL_DOOR_ITEM.get(), a, 2.0D, 1);
        helper.succeed();
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void blockedUpperHalfRefusesDoor(GameTestHelper helper) {
        BlockPos a = new BlockPos(2, 3, 2);
        helper.setBlock(a.below(), Blocks.STONE);
        helper.setBlock(a.above(), Blocks.STONE);
        helper.assertFalse(placeDoor(helper, a.below()).consumesAction(), "placed through an occupied upper half");
        helper.assertBlockPresent(Blocks.AIR, a);
        helper.assertBlockPresent(Blocks.STONE, a.above());
        helper.succeed();
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void thickPostsJoinAndHaveValidBridgeEntities(GameTestHelper helper) {
        for (var block : java.util.List.of(HallJoinery.THICK_RED_PILLAR.get(), HallJoinery.THICK_WHITE_PILLAR.get())) {
            BlockPos p = new BlockPos(2, 2, 2);
            helper.setBlock(p, block.defaultBlockState());
            helper.setBlock(p.above(), RedPillarBlock.connect(block.defaultBlockState(), helper.getLevel(),
                    helper.absolutePos(p.above())));
            helper.assertTrue(helper.getBlockState(p).getValue(RedPillarBlock.UP) == RedPillarBlock.Joint.RUN
                    && helper.getBlockState(p.above()).getValue(RedPillarBlock.DOWN) == RedPillarBlock.Joint.RUN,
                    "stack did not form a run");
            helper.setBlock(p.east(), block.defaultBlockState());
            helper.setBlock(p, helper.getBlockState(p).setValue(RedPillarBlock.BRIDGED, true));
            var entity = helper.getLevel().getBlockEntity(helper.absolutePos(p));
            helper.assertTrue(entity instanceof PillarBridgeBlockEntity && entity.getType().isValid(helper.getBlockState(p)),
                    "shared bridge entity does not support the thick post");
            var bridge = (PillarBridgeBlockEntity) entity;
            bridge.set(Direction.Axis.X, RedPillarBlock.Bridge.HIGH_MID);
            helper.assertTrue(bridge.get(Direction.Axis.X) == RedPillarBlock.Bridge.HIGH_MID, "bridge not stored");
            helper.assertTrue(block.getStateDefinition().getPossibleStates().size() <= 4096, "too many post states");
            // Clear both materials between checks; changing material alone is deliberately not a run.
            helper.setBlock(p, Blocks.AIR);
            helper.setBlock(p.above(), Blocks.AIR);
            helper.setBlock(p.east(), Blocks.AIR);
        }
        helper.assertTrue(HallJoinery.HALL_DOOR.get().getStateDefinition().getPossibleStates().size() <= 4096,
                "too many door states");
        helper.succeed();
    }
}
