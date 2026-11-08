package io.github.verycooltimo.murim.sect.vault;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * One quarter of a big 2 x 2 framed lacquer panel on a wall of the pit hall (round 10: «larger panels, fewer gold lines, plain dark lacquer between the
 * pillars»). {@code piece} 0..3 = top left, top right, bottom left, bottom right as seen from the room; {@code facing} = the side of the room.
 */
public class VaultPanelBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<VaultPanelBlock> CODEC = simpleCodec(VaultPanelBlock::new);
    public static final IntegerProperty PIECE = IntegerProperty.create("piece", 0, 3);

    public VaultPanelBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH).setValue(PIECE, 0));
    }

    public static BlockState of(Block block, int piece, Direction facing) {
        return block.defaultBlockState().setValue(PIECE, piece).setValue(FACING, facing);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PIECE);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state;
    }
}
