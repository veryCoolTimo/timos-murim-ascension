package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Four-pixel archive upright. Full-height ends meet vertically, without caps or additional states. */
public final class LibraryPostBlock extends Block {
    public static final MapCodec<LibraryPostBlock> CODEC = simpleCodec(LibraryPostBlock::new);
    private static final VoxelShape SHAPE = Block.box(6, 0, 6, 10, 16, 10);

    public LibraryPostBlock(Properties properties) { super(properties); }

    @Override
    protected MapCodec<? extends Block> codec() { return CODEC; }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
