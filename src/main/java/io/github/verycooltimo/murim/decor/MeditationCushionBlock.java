package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Round floor cushion for meditation (sect halls, docs/design/23-mount-hua-sect.md). Right-click with an empty hand
 * (or anything that does not use the block) to sit: an invisible {@link SeatEntity} is spawned on the cushion and the
 * player rides it; sneaking gets up, the seat removes itself. One sitter per cushion.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/state/BlockBehaviour.java#useWithoutItem,
 * world/entity/Entity.java#startRiding.
 */
public class MeditationCushionBlock extends Block implements SeatBlock {

    public static final MapCodec<MeditationCushionBlock> CODEC = simpleCodec(MeditationCushionBlock::new);
    private static final VoxelShape SHAPE = Block.box(1.2, 0, 1.2, 14.8, 3.6, 14.8);

    public MeditationCushionBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /** Seats a player on the cushion at {@code pos}; false when it is taken or the player rides already. */
    public static boolean sit(Level level, BlockPos pos, Player player) {
        return SeatEntity.sit(level, pos, player);
    }

    @Override
    public double seatTop(BlockState state) {
        return 3.6D / 16.0D;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        return sit(level, pos, player) ? InteractionResult.CONSUME : InteractionResult.PASS;
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
