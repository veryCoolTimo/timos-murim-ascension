package io.github.verycooltimo.murim.sect.vault;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/** The one ring of inlay round the treasure on the floor of the pit: {@code kind} 0 a stripe along x, 1 along z, 2 the corner knot (tools/art/vault_hall.py). */
public class VaultPitBorderBlock extends Block {

    public static final IntegerProperty KIND = IntegerProperty.create("kind", 0, 2);

    public VaultPitBorderBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(KIND, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(KIND);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        int k = state.getValue(KIND);
        return k < 2 && (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) ? state.setValue(KIND, 1 - k) : state;
    }
}
