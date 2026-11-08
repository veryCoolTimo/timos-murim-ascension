package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/** Hanging calligraphy scroll, 2 or 3 blocks tall. The placed or baked text variant stays fixed. */
public class ShrineScrollBlock extends ShrineTallBlock {

    public static final MapCodec<ShrineScrollBlock> CODEC = simpleCodec(p -> new ShrineScrollBlock(p, ShrineSpec.DUMMY));
    public static final IntegerProperty VARIANT = IntegerProperty.create("variant", 0, 3);

    public ShrineScrollBlock(Properties properties, ShrineSpec spec) {
        super(properties, spec);
        registerDefaultState(defaultBlockState().setValue(VARIANT, 0));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, VARIANT);
    }
}
