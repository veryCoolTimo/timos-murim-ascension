package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Hanging calligraphy scroll, 2 or 3 blocks tall. Four texts; right-click with an empty hand shows the next one on all
 * parts. API: reference/minecraft-src/net/minecraft/world/level/block/LeverBlock.java#useWithoutItem.
 */
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
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            BlockPos base = pos.below(state.getValue(PART));
            int next = (state.getValue(VARIANT) + 1) % 4;
            for (int k = 0; k < spec.height(); k++) {
                BlockState s = level.getBlockState(base.above(k));
                if (s.is(this)) {
                    level.setBlock(base.above(k), s.setValue(VARIANT, next), Block.UPDATE_ALL);
                }
            }
            level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.6F, 1.0F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, VARIANT);
    }
}
