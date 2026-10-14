package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the weapon stands: a sword goes into the aimed slot (or the nearest free one), an empty hand takes
 * the aimed one, non-swords are refused, the aim follows FACING, breaking drops the stand and every stored sword,
 * and the contents survive a save/load round trip.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/state/BlockBehaviour.java
 * (BlockStateBase#useItemOn, #useWithoutItem, #rotate); BlockEntity.java#saveWithFullMetadata, #loadStatic;
 * gametest/framework/GameTestHelper.java#makeMockPlayer, #assertItemEntityCountIs.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class WeaponRackGameTests {

    private static final String FLOOR = "small_floor";
    private static final BlockPos POS = new BlockPos(1, 2, 1);

    private WeaponRackGameTests() {
    }

    private static WeaponStandBlockEntity place(GameTestHelper helper, WeaponStandBlock block, Direction facing) {
        helper.setBlock(POS, block.defaultBlockState().setValue(WeaponStandBlock.FACING, facing));
        BlockEntity be = helper.getBlockEntity(POS);
        helper.assertTrue(be instanceof WeaponStandBlockEntity, "block entity: " + be);
        return (WeaponStandBlockEntity) be;
    }

    /** A click on the front of the stand at model x {@code modelX} (pixels, facing north), mid height. */
    private static BlockHitResult aim(GameTestHelper helper, float modelX) {
        BlockPos abs = helper.absolutePos(POS);
        BlockState state = helper.getBlockState(POS);
        Direction facing = state.getValue(WeaponStandBlock.FACING);
        double m = modelX / 16.0D;
        // Inverse of WeaponStandBlock#modelX.
        double wx = switch (facing) {
            case EAST -> 0.5D;
            case SOUTH -> 1.0D - m;
            case WEST -> 0.5D;
            default -> m;
        };
        double wz = switch (facing) {
            case EAST -> m;
            case WEST -> 1.0D - m;
            default -> 0.5D;
        };
        Vec3 at = new Vec3(abs.getX() + wx, abs.getY() + 0.5D, abs.getZ() + wz);
        return new BlockHitResult(at, facing, abs, false);
    }

    private static void useWith(GameTestHelper helper, Player player, ItemStack stack, BlockHitResult hit) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockState state = helper.getBlockState(POS);
        var result = state.useItemOn(stack, helper.getLevel(), player, InteractionHand.MAIN_HAND, hit);
        if (result == net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            state.useWithoutItem(helper.getLevel(), player, hit);
        }
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void rackPutsAimedTakesAimed(GameTestHelper helper) {
        WeaponStandBlockEntity rack = place(helper, WeaponRacks.TRAINING_SWORD_RACK.get(), Direction.NORTH);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockHitResult middle = aim(helper, 8.0F);
        useWith(helper, player, new ItemStack(ModItems.WOODEN_SWORD.get()), middle);
        helper.assertTrue(rack.get(1).is(ModItems.WOODEN_SWORD.get()), "aimed slot 1 holds the sword");
        helper.assertTrue(player.getMainHandItem().isEmpty(), "the sword left the hand");
        // Aimed slot taken: the next sword goes to the nearest free one (slot 0 is checked before 2).
        useWith(helper, player, new ItemStack(ModItems.HUASHAN_SWORD.get()), middle);
        helper.assertTrue(rack.get(0).is(ModItems.HUASHAN_SWORD.get()), "nearest free slot 0: " + rack.items());
        // Not a sword: refused, stays in hand.
        useWith(helper, player, new ItemStack(Items.STICK), aim(helper, 12.5F));
        helper.assertTrue(rack.get(2).isEmpty() && player.getMainHandItem().is(Items.STICK), "stick refused");
        // Empty hand takes the aimed sword.
        useWith(helper, player, ItemStack.EMPTY, middle);
        helper.assertTrue(player.getMainHandItem().is(ModItems.WOODEN_SWORD.get()) && rack.get(1).isEmpty(), "took slot 1");
        // Aimed slot empty: the nearest stored one (slot 0) comes out.
        useWith(helper, player, ItemStack.EMPTY, aim(helper, 12.5F));
        helper.assertTrue(player.getMainHandItem().is(ModItems.HUASHAN_SWORD.get()), "took nearest stored slot 0");
        helper.assertTrue(rack.items().stream().allMatch(ItemStack::isEmpty), "rack empty: " + rack.items());
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void rackAimFollowsFacing(GameTestHelper helper) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            WeaponStandBlockEntity rack = place(helper, WeaponRacks.TRAINING_SWORD_RACK.get(), facing);
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            useWith(helper, player, new ItemStack(ModItems.WOODEN_SWORD.get()), aim(helper, 12.5F));
            helper.assertTrue(rack.get(2).is(ModItems.WOODEN_SWORD.get()), facing + ": slot 2 " + rack.items());
            helper.setBlock(POS, Blocks.AIR);
        }
        BlockState east = WeaponRacks.SWORD_STAND.get().defaultBlockState().setValue(WeaponStandBlock.FACING, Direction.EAST);
        helper.assertTrue(east.rotate(Rotation.CLOCKWISE_90).getValue(WeaponStandBlock.FACING) == Direction.SOUTH,
                "rotation keeps facing in step");
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void rackBreakDropsSwords(GameTestHelper helper) {
        place(helper, WeaponRacks.TRAINING_SWORD_RACK.get(), Direction.NORTH).fillDefault();
        helper.getLevel().destroyBlock(helper.absolutePos(POS), true);
        helper.assertBlockPresent(Blocks.AIR, POS);
        helper.runAfterDelay(2, () -> {
            helper.assertItemEntityCountIs(ModItems.WOODEN_SWORD.get(), POS, 3.0D, 3);
            helper.assertItemEntityCountIs(WeaponRacks.TRAINING_SWORD_RACK_ITEM.get(), POS, 3.0D, 1);
            helper.succeed();
        });
    }

    @GameTest(template = FLOOR, batch = "decor")
    public static void standKeepsSwordThroughSave(GameTestHelper helper) {
        WeaponStandBlockEntity stand = place(helper, WeaponRacks.SWORD_STAND.get(), Direction.WEST);
        stand.fillDefault();
        helper.assertTrue(stand.items().size() == 1 && stand.get(0).is(ModItems.HUASHAN_SWORD.get()), "default: " + stand.items());
        var registries = helper.getLevel().registryAccess();
        CompoundTag tag = stand.saveWithFullMetadata(registries);
        BlockEntity loaded = BlockEntity.loadStatic(helper.absolutePos(POS), helper.getBlockState(POS), tag, registries);
        helper.assertTrue(loaded instanceof WeaponStandBlockEntity l && l.get(0).is(ModItems.HUASHAN_SWORD.get()),
                "loaded: " + loaded);
        // The client copy is built from the update tag the same way.
        CompoundTag sync = stand.getUpdateTag(registries);
        helper.assertTrue(sync.contains("Items"), "update tag carries the items: " + sync);
        helper.getLevel().destroyBlock(helper.absolutePos(POS), true);
        helper.runAfterDelay(2, () -> {
            helper.assertItemEntityCountIs(ModItems.HUASHAN_SWORD.get(), POS, 3.0D, 1);
            helper.assertItemEntityCountIs(WeaponRacks.SWORD_STAND_ITEM.get(), POS, 3.0D, 1);
            helper.succeed();
        });
    }
}
