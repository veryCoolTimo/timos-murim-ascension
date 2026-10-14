package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Loadable old IDs; old states retain facing/half and become the canonical rack with their original filling. */
public class LegacyPlumShelfBlock extends ScrollShelfBlock {
    public static final MapCodec<LegacyPlumShelfBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            propertiesCodec(), Codec.intRange(1, 3).fieldOf("variant").forGetter(block -> block.variant)
    ).apply(instance, LegacyPlumShelfBlock::new));

    private final int variant;

    public LegacyPlumShelfBlock(Properties properties, int variant) {
        super(properties);
        this.variant = variant;
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }

    public BlockState canonical(BlockState state) {
        return HallFurniture.SCROLL_SHELF.get().defaultBlockState()
                .setValue(FACING, state.getValue(FACING)).setValue(HALF, state.getValue(HALF))
                .setValue(PlumShelfBlock.VARIANT, variant);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, level, pos, oldState, moved);
        // Commands and structure templates can still place an alias after a chunk has loaded.
        if (!level.isClientSide) level.scheduleTick(pos, this, 1);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        BlockPos partner = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        BlockState other = level.getBlockState(partner);
        // Convert both halves before any neighbour checks can remove the temporarily mismatched rack: UPDATE_KNOWN_SHAPE
        // skips the shape updates that otherwise turn the still-legacy partner into air.
        level.setBlock(pos, canonical(state), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        if (other.is(this) && other.getValue(HALF) != state.getValue(HALF)
                && other.getValue(FACING) == state.getValue(FACING)) {
            level.setBlock(partner, canonical(other), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        level.updateNeighborsAt(pos, HallFurniture.SCROLL_SHELF.get());
        level.updateNeighborsAt(partner, HallFurniture.SCROLL_SHELF.get());
    }
}
