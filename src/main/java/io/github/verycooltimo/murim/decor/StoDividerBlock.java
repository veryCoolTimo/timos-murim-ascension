package io.github.verycooltimo.murim.decor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/** Lattice room divider: a {@link FoldingScreenBlock} (joins neighbours like a fence) that needs no floor, so it stacks. */
public class StoDividerBlock extends FoldingScreenBlock {
    public StoDividerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected boolean canSurvive(BlockState s, LevelReader level, BlockPos pos) {
        return true;
    }
}
