package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Bronze tripod ding with an octagonal bowl, lid, two handles and three burning sticks (light 3).
 *
 * <p>The smoke is ambient block decor drawn the vanilla way: {@link #animateTick} runs only on the client (called from
 * ClientLevel#doAnimateTick for blocks near the player), as the campfire and the torch do; no game state. Tip
 * positions: INCENSE_TIPS in tools/art/temple_bronze.py.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/Block.java#animateTick, TorchBlock.java#animateTick,
 * core/particles/ParticleTypes.java#WHITE_SMOKE.
 */
public class IncenseBurnerBlock extends Block {

    public static final MapCodec<IncenseBurnerBlock> CODEC = simpleCodec(IncenseBurnerBlock::new);
    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(2.6, 3.6, 2.6, 13.4, 10.85, 13.4),
            Block.box(1.9, 7.4, 6.2, 2.8, 12, 9.8), Block.box(13.2, 7.4, 6.2, 14.1, 12, 9.8),
            Block.box(3.1, 0, 10, 5.8, 4.8, 12.3), Block.box(10.2, 0, 10, 12.9, 4.8, 12.3),
            Block.box(7.3, 0, 2.8, 8.7, 4.8, 5.7), Block.box(7.05, 10.8, 7.05, 8.95, 12.25, 8.95));
    /** Stick tips, px. */
    private static final double[][] TIPS = {{6.0, 16.0, 7.0}, {8.0, 16.0, 9.0}, {10.0, 16.0, 7.0}};

    public IncenseBurnerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        for (double[] t : TIPS) {
            if (random.nextInt(3) == 0) {
                level.addParticle(ParticleTypes.WHITE_SMOKE, pos.getX() + t[0] / 16.0D, pos.getY() + t[1] / 16.0D,
                        pos.getZ() + t[2] / 16.0D, (random.nextDouble() - 0.5D) * 0.004D, 0.012D + random.nextDouble() * 0.01D,
                        (random.nextDouble() - 0.5D) * 0.004D);
            }
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }
}
