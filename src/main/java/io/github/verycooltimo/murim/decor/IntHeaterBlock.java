package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Decorative, switchable hearths; no inventory or recipe processing. Both models fit inside one block. */
public final class IntHeaterBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<IntHeaterBlock> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            propertiesCodec(), com.mojang.serialization.Codec.BOOL.fieldOf("kitchen").forGetter(b -> b.kitchen)
    ).apply(i, IntHeaterBlock::new));
    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    private final boolean kitchen;

    public IntHeaterBlock(Properties properties, boolean kitchen) {
        super(properties);
        this.kitchen = kitchen;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIT, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player.isShiftKeyDown() || !player.getAbilities().mayBuild) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            level.setBlock(pos, state.cycle(LIT), Block.UPDATE_ALL);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(LIT) && random.nextInt(8) == 0) {
            level.addParticle(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + (kitchen ? 0.88 : 1.01),
                    pos.getZ() + 0.5, 0, 0.012, 0);
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction f = state.getValue(FACING);
        if (kitchen) {
            return Shapes.or(StoShapes.turn(0, 0, 0, 16, 12, 16, f),
                    StoShapes.turn(2, 12, 2, 14, 13.5, 14, f));
        }
        return Shapes.or(StoShapes.turn(2, 3, 2, 14, 12, 14, f),
                StoShapes.turn(3, 12, 3, 13, 15, 13, f), StoShapes.turn(7, 15, 7, 9, 16, 9, f),
                StoShapes.turn(6.8, 0, 2.3, 9.2, 3.5, 5, f),
                StoShapes.turn(3, 0, 10, 5.4, 3.5, 12.7, f), StoShapes.turn(10.6, 0, 10, 13, 3.5, 12.7, f));
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
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
        builder.add(FACING, LIT);
    }
}
