package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.registry.ModBlocks;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;

/**
 * Plum log and plum wood (dark bark): an axe strips both to {@code murim:stripped_plum_log}, keeping
 * the axis, the way vanilla logs strip.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/common/extensions/IBlockExtension.java#getToolModifiedState,
 * ItemAbilities#AXE_STRIP
 */
public class PlumLogBlock extends RotatedPillarBlock {

    public PlumLogBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockState getToolModifiedState(BlockState state, UseOnContext context, ItemAbility itemAbility, boolean simulate) {
        if (ItemAbilities.AXE_STRIP == itemAbility && context.getItemInHand().canPerformAction(itemAbility)) {
            return ModBlocks.STRIPPED_PLUM_LOG.get().defaultBlockState().setValue(AXIS, state.getValue(AXIS));
        }
        return super.getToolModifiedState(state, context, itemAbility, simulate);
    }
}
