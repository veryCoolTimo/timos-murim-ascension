package io.github.verycooltimo.murim.sect.seal;

import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import io.github.verycooltimo.murim.sect.PlayerJournal;

/**
 * The penance marker block the author places in his builds (they are ordinary blocks without block entities, so
 * {@code /murim dev loc capture} keeps them in the templates as they are, turned with the template).
 *
 * <ul>
 *   <li>{@link PenanceSeal} — the stone the sentenced sits on in the penance cave; it governs the cave door.</li>
 * </ul>
 * API: reference/minecraft-src/net/minecraft/world/level/block/HorizontalDirectionalBlock.java,
 * CarvedPumpkinBlock#getStateForPlacement (facing the player).
 */
public final class SealBlocks {

    private SealBlocks() {
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
                PlayerJournal.record(sp, PenanceService.status(sp).copy().withStyle(ChatFormatting.GRAY));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }
}
