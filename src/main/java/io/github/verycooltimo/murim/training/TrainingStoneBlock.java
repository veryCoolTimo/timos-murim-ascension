package io.github.verycooltimo.murim.training;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Training stone: a rounded boulder with a carved grip (canon ch. 66, 339 — stones and carts hauled up the rock).
 * Use it with an empty hand to lift it: the block goes into the hand, and carrying it uphill is the carry exercise
 * ({@link TrainingService}); place it like any block to put it down. The author places these at the sect yard.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/state/BlockBehaviour.java#useWithoutItem, #getShape
 */
public final class TrainingStoneBlock extends Block {

    private static final VoxelShape SHAPE = Block.box(2.0D, 0.0D, 2.0D, 14.0D, 11.0D, 14.0D);

    public TrainingStoneBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(asItem()));
            level.playSound(null, pos, SoundEvents.STONE_BREAK, SoundSource.PLAYERS, 0.7F, 0.6F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
