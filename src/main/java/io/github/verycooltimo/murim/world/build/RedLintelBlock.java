package io.github.verycooltimo.murim.world.build;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** A plain rectangular lintel: placing consecutive segments makes a long continuous beam. */
public final class RedLintelBlock extends RedBeamBlock {
    public static final MapCodec<RedLintelBlock> CODEC = simpleCodec(RedLintelBlock::new);
    public RedLintelBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends Block> codec() { return CODEC; }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        if (!(TimberPlacement.clickedState(context).getBlock() instanceof RedBeamBlock)) {
            state = state.setValue(SIDE, Side.CENTER).setValue(LEVEL, Level3.MIDDLE);
        }
        return connect(state, context.getLevel(), context.getClickedPos());
    }
}
