package io.github.verycooltimo.murim.sect.vault;

import com.mojang.serialization.MapCodec;
import io.github.verycooltimo.murim.decor.PlumShelfBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;

/** Same two-block plum shelf and models; each lower unit owns its removable archive volume.
 * API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/level/block/EntityBlock.java#newBlockEntity
 * and Block.java#useWithoutItem,onRemove; pickup follows VaultNoteShelfBlock.
 */
public final class VaultArchiveShelfBlock extends PlumShelfBlock implements EntityBlock {
    public static final MapCodec<VaultArchiveShelfBlock> CODEC = simpleCodec(VaultArchiveShelfBlock::new);
    public VaultArchiveShelfBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? new VaultChestBlockEntity(pos, state) : null;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                         Player player, BlockHitResult hit) {
        if (player.isSpectator()) return InteractionResult.PASS;
        if (!level.isClientSide) {
            BlockPos lower = state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
            if (level.getBlockEntity(lower) instanceof VaultChestBlockEntity shelf) {
                shelf.giveOriginals(player);
                for (int slot = 0; slot < shelf.getContainerSize(); slot++) {
                    if (shelf.getItem(slot).isEmpty() || io.github.verycooltimo.murim.mastery.Originals.isOriginal(shelf.getItem(slot))) continue;
                    var book = shelf.removeItem(slot, 1);
                    if (!player.getInventory().add(book)) player.drop(book, false);
                    return InteractionResult.CONSUME;
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        net.minecraft.world.Containers.dropContentsOnDestroy(state, next, level, pos);
        super.onRemove(state, level, pos, next, moving);
    }
}
