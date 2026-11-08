package io.github.verycooltimo.murim.sect.vault;

import com.mojang.serialization.MapCodec;
import io.github.verycooltimo.murim.decor.ScrollShelfBlock;
import io.github.verycooltimo.murim.library.ModLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;

/** Bedroom shelf in the same plum-wood style as its neighbour. Contents retain item components.
 * API: decor/ScrollShelfBlock, decor/StoStorageBlockEntity and VaultHarvest's server-side pickup.
 * The lower half owns the inventory; TAKEN is persisted and synchronized by vanilla block states.
 */
public class VaultNoteShelfBlock extends ScrollShelfBlock implements EntityBlock {
    public static final BooleanProperty TAKEN = BooleanProperty.create("taken");
    public static final MapCodec<VaultNoteShelfBlock> CODEC = simpleCodec(VaultNoteShelfBlock::new);

    public VaultNoteShelfBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(TAKEN, true));
    }

    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(TAKEN);
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? new VaultChestBlockEntity(pos, state) : null;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        take(level, pos, player);
        return InteractionResult.CONSUME;
    }

    public static boolean take(Level level, BlockPos pos, Player player) {
        if (level.isClientSide || player.isSpectator()) return false;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof VaultNoteShelfBlock)) return false;
        BlockPos lower = state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
        if (!(level.getBlockEntity(lower) instanceof VaultChestBlockEntity shelf)) return false;
        for (int slot = 0; slot < shelf.getContainerSize(); slot++) {
            if (!(shelf.getItem(slot).getItem() instanceof io.github.verycooltimo.murim.library.HeadNoteItem)) continue;
            var note = shelf.removeItem(slot, 1);
            if (!player.getInventory().add(note)) player.drop(note, false);
            boolean remaining = false;
            for (int i = 0; i < shelf.getContainerSize(); i++) remaining |= shelf.getItem(i).getItem() instanceof io.github.verycooltimo.murim.library.HeadNoteItem;
            for (BlockPos half : java.util.List.of(lower, lower.above())) {
                BlockState current = level.getBlockState(half);
                if (current.getBlock() instanceof VaultNoteShelfBlock) level.setBlock(half, current.setValue(TAKEN, !remaining), Block.UPDATE_ALL);
            }
            shelf.setChanged();
            return true;
        }
        // Preserve access to unrelated items left in an old world's bedside chest.
        if (!shelf.isEmpty()) player.openMenu(shelf);
        return false;
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        net.minecraft.world.Containers.dropContentsOnDestroy(state, next, level, pos);
        super.onRemove(state, level, pos, next, moving);
    }
}
