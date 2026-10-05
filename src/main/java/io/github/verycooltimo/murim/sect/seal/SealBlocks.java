package io.github.verycooltimo.murim.sect.seal;

import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The two marker blocks the author places in his builds (they are ordinary blocks without block entities, so
 * {@code /murim capture} keeps them in the templates as they are, turned with the template).
 *
 * <ul>
 *   <li>{@link VaultSeal} — the engraved plate of the vault door: touch it to begin the trial; it governs the cold
 *       iron doors within {@link SealAccess#RANGE}.</li>
 *   <li>{@link PenanceSeal} — the stone the sentenced sits on in the penance cave; it governs the cave door.</li>
 * </ul>
 * API: reference/minecraft-src/net/minecraft/world/level/block/HorizontalDirectionalBlock.java,
 * CarvedPumpkinBlock#getStateForPlacement (facing the player).
 */
public final class SealBlocks {

    private SealBlocks() {
    }

    /** The engraved seal of the vault door: faces the one who placed it. */
    public static class VaultSeal extends HorizontalDirectionalBlock {

        public static final MapCodec<VaultSeal> CODEC = simpleCodec(VaultSeal::new);

        public VaultSeal(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (player instanceof ServerPlayer sp) {
                VaultTrial.begin(sp, pos);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    /** The penance stone: where the sentenced is kept. Touching it tells how much is left. */
    public static class PenanceSeal extends Block {

        public static final MapCodec<PenanceSeal> CODEC = simpleCodec(PenanceSeal::new);

        public PenanceSeal(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (player instanceof ServerPlayer sp) {
                sp.displayClientMessage(PenanceService.status(sp).copy().withStyle(ChatFormatting.GRAY), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }
}
