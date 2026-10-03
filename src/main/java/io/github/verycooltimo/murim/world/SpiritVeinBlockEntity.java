package io.github.verycooltimo.murim.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Блок-сущность камня жилы: данных нет, нужна для рендера столба ауры на клиенте. */
public class SpiritVeinBlockEntity extends BlockEntity {

    public SpiritVeinBlockEntity(BlockPos pos, BlockState state) {
        super(ModWorld.SPIRIT_VEIN_ENTITY.get(), pos, state);
    }

    public PlaceKind kind() {
        return getBlockState().hasProperty(SpiritVeinBlock.KIND) ? getBlockState().getValue(SpiritVeinBlock.KIND) : PlaceKind.ALTAR;
    }
}
