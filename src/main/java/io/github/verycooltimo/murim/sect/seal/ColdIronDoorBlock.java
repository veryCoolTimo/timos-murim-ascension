package io.github.verycooltimo.murim.sect.seal;

import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Cold iron door of the vault and the penance cave. Redstone does not move it (a lever by the vault is no key);
 * a hand opens it only for whom the nearest seal lets through ({@link SealAccess#mayOpen}), and it shuts itself
 * after {@link ColdIronRules#DOOR_OPEN_TICKS}. Creative opens any.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/DoorBlock.java (#useWithoutItem, #setOpen,
 * #neighborChanged), BlockSetType#IRON (not by hand, not by wind charge).
 */
public class ColdIronDoorBlock extends DoorBlock {

    public static final MapCodec<ColdIronDoorBlock> CODEC = simpleCodec(ColdIronDoorBlock::new);

    public ColdIronDoorBlock(Properties properties) {
        super(BlockSetType.IRON, properties);
    }

    @Override
    public MapCodec<? extends DoorBlock> codec() {
        return CODEC;
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return ColdIron.progress(player, level, pos);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player.isCreative() || player instanceof ServerPlayer sp && SealAccess.mayOpen(sp, pos)) {
            open(level, pos, state, !isOpen(state), ColdIronRules.DOOR_OPEN_TICKS);
            return InteractionResult.CONSUME;
        }
        level.playSound(null, pos, SoundEvents.CHAIN_HIT, SoundSource.BLOCKS, 0.8F, 0.6F);
        player.displayClientMessage(Component.translatable(SealAccess.lockedKey(level, pos)).withStyle(ChatFormatting.GRAY), true);
        return InteractionResult.CONSUME;
    }

    /** Opens (shutting itself after {@code ticks}) or shuts the door at {@code pos}, either half. */
    public void open(Level level, BlockPos pos, BlockState state, boolean open, int ticks) {
        BlockPos lower = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        BlockState l = level.getBlockState(lower);
        if (!l.is(this)) {
            return;
        }
        setOpen(null, level, l, lower, open);
        if (open) {
            level.scheduleTick(lower, this, ticks);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER && isOpen(state)) {
            setOpen(null, level, state, pos, false);
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        // Redstone does not move cold iron.
    }
}
