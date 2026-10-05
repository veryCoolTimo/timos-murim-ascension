package io.github.verycooltimo.murim.sect.seal;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Cold iron bars: the grille of the penance cave and of the vault corridor. Connect like iron bars.
 * API: reference/minecraft-src/net/minecraft/world/level/block/IronBarsBlock.java
 */
public class ColdIronBarsBlock extends IronBarsBlock {

    public static final MapCodec<ColdIronBarsBlock> CODEC = simpleCodec(ColdIronBarsBlock::new);

    public ColdIronBarsBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<? extends IronBarsBlock> codec() {
        return CODEC;
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return ColdIron.progress(player, level, pos);
    }
}
