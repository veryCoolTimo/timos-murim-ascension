package io.github.verycooltimo.murim.sect.vault;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One cell of the treasure scene of the pit (round 8: two linen cloths, one loose heap of coins and gems, a stepped stack of gold bars). Each
 * {@code kind} is its own model (tools/art/vault_pit.py, table in {@link VaultTreasureLayout}) that may reach over the edge of the cell, so the
 * pile is one continuous mound and not a grid of blocks. {@code turn} follows the turn of the site. The heap gives plain block light.
 */
public class VaultTreasureBlock extends Block {

    public static final IntegerProperty LAYERS = IntegerProperty.create("layers", 1, 3);
    public static final IntegerProperty KIND = IntegerProperty.create("kind", 0, VaultTreasureLayout.COUNT - 1);
    public static final IntegerProperty TURN = IntegerProperty.create("turn", 0, 3);

    public VaultTreasureBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(KIND, 0).setValue(TURN, 0).setValue(LAYERS, 3));
    }

    public static BlockState of(Block block, int kind) {
        return block.defaultBlockState().setValue(KIND, kind);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(KIND, TURN, LAYERS);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(TURN, (state.getValue(TURN) + rotation.ordinal()) & 3);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Block.box(0, 0, 0, 16, Math.max(1, VaultTreasureLayout.HEIGHT[state.getValue(KIND)] * state.getValue(LAYERS) / 3.0), 16);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        double h = VaultTreasureLayout.HEIGHT[state.getValue(KIND)] * state.getValue(LAYERS) / 3.0;
        return h <= 2 ? Shapes.empty() : Block.box(0, 0, 0, 16, Math.min(h, 8), 16);
    }

    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, net.minecraft.world.level.Level level,
            BlockPos pos, net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hit) {
        return VaultHarvest.use(level, pos, player);
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
