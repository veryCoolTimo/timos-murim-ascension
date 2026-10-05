package io.github.verycooltimo.murim.sect.seal;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block of cold iron (寒鐵): gives way only to a qi strike above Peak ({@link ColdIron}).
 * API: reference/minecraft-src/net/minecraft/world/level/block/state/BlockBehaviour.java#getDestroyProgress
 */
public class ColdIronBlock extends Block {

    public static final MapCodec<ColdIronBlock> CODEC = simpleCodec(ColdIronBlock::new);

    public ColdIronBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return ColdIron.progress(player, level, pos);
    }
}
