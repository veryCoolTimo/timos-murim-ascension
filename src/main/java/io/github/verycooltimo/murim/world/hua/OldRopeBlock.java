package io.github.verycooltimo.murim.world.hua;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * {@code murim:old_rope} — a weathered hemp rope hanging from the rope posts at the three hardest (5-block)
 * steps of the South Peak climb, in place of the iron chain. No collision: the climber stands in the rope's
 * column, leans against the rock and goes up ({@link #isLadder}). It hangs free like the chain did — the
 * generator places it and nothing makes it fall.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/ChainBlock.java (shape),
 * reference/neoforge-src/net/neoforged/neoforge/common/extensions/IBlockExtension.java#isLadder
 */
public class OldRopeBlock extends Block {

    public static final MapCodec<OldRopeBlock> CODEC = simpleCodec(OldRopeBlock::new);
    private static final VoxelShape SHAPE = Block.box(6.5, 0.0, 6.5, 9.5, 16.0, 9.5);

    public OldRopeBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public boolean isLadder(BlockState state, LevelReader level, BlockPos pos, LivingEntity entity) {
        return true;
    }
}
