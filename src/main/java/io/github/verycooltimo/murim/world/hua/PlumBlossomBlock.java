package io.github.verycooltimo.murim.world.hua;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.ParticleUtils;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Plum blossom on bare branches (Mount Hua is the Plum Blossom sect). A leaves-like block: no
 * occlusion, persistent when placed by the generator, and — like vanilla cherry leaves — it lets a
 * petal fall now and then (client display tick, only near the player, the vanilla way).
 * Three colour variants share the class: pink, pale/white, deep red.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/CherryLeavesBlock.java#animateTick
 */
public class PlumBlossomBlock extends LeavesBlock {

    public static final MapCodec<PlumBlossomBlock> CODEC = simpleCodec(PlumBlossomBlock::new);

    public PlumBlossomBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<? extends LeavesBlock> codec() {
        return CODEC;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        super.animateTick(state, level, pos, random);
        // Sparser than cherry leaves (1 in 10): plum branches carry few blossoms.
        if (random.nextInt(16) == 0) {
            BlockPos below = pos.below();
            BlockState under = level.getBlockState(below);
            if (!isFaceFull(under.getCollisionShape(level, below), Direction.UP)) {
                ParticleUtils.spawnParticleBelow(level, pos, random, ParticleTypes.CHERRY_LEAVES);
            }
        }
    }
}
