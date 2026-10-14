package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.jetbrains.annotations.Nullable;

/** Pink plum rack: scrolls (0), books (1), mixed (2), sparse (3). 32 states, two halves. */
public class PlumShelfBlock extends ScrollShelfBlock {
    public static final MapCodec<PlumShelfBlock> CODEC = simpleCodec(PlumShelfBlock::new);
    public static final IntegerProperty VARIANT = IntegerProperty.create("variant", 0, 3);

    public PlumShelfBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(VARIANT, 0));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }

    public static int variantAt(BlockPos pos) {
        return (int) Math.floorMod(Mth.getSeed(pos), 4L);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : state.setValue(VARIANT, variantAt(context.getClickedPos()));
    }

    // StoneLanternBlock#setPlacedBy copies the complete state, including VARIANT, to the upper half.
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(VARIANT);
    }
}
