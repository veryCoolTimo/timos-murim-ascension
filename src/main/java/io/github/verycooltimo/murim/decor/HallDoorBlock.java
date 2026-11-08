package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;

/** Paper-backed Hua lattice above two lacquer panels. Vanilla placement, hinges, hand and redstone opening. */
public final class HallDoorBlock extends DoorBlock {

    public static final MapCodec<HallDoorBlock> CODEC = simpleCodec(HallDoorBlock::new);

    public HallDoorBlock(Properties properties) {
        super(BlockSetType.OAK, properties);
    }

    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, net.minecraft.world.entity.player.Player player,
            net.minecraft.world.phys.BlockHitResult hit) {
        // A room that is not yet his (elders' houses, treasury, halls): he knocks, the door stays shut (author 21.10).
        if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer sp
                && io.github.verycooltimo.murim.sect.SectKnock.knock(sp, pos, pos, this, -1)) {
            return net.minecraft.world.InteractionResult.sidedSuccess(false);
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }

    @Override
    public MapCodec<? extends DoorBlock> codec() {
        return CODEC;
    }
}
