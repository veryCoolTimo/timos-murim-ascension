package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.jetbrains.annotations.Nullable;

/**
 * The grey iron band block with a row of round studs from the sect gate (author's refs docs/design/reference/decor/
 * door_ref_2.png, thin_frame_3.png; «блок с пипкой — их 4 в ряд», round 3: «сделать всё-таки блоком»): a full iron
 * cube, four round studs across the middle of each side, plain top and bottom. Studs on a side covered by a
 * neighbour are culled, so a row of bands reads as one band without seams. All four sides are alike; FACING is kept
 * only so worlds with the earlier thin band still load.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/HorizontalDirectionalBlock.java.
 */
public class StuddedBandBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<StuddedBandBlock> CODEC = simpleCodec(StuddedBandBlock::new);


    public StuddedBandBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}
