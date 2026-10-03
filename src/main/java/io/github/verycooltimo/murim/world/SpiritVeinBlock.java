package io.github.verycooltimo.murim.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * Камень духовной жилы — маркер места силы (docs/design/19b §3). Слабо светится; разбить можно,
 * но место с собой не унести: выпадает обычный булыжник (таблица добычи блока).
 *
 * <p>Блок-сущность нужна только клиенту: рендер столба ауры виден издалека (32–48 блоков),
 * чего не дают случайные тики анимации блока.
 * API: reference/minecraft-src/net/minecraft/world/level/block/BaseEntityBlock.java#codec
 */
public class SpiritVeinBlock extends BaseEntityBlock {

    public static final EnumProperty<PlaceKind> KIND = EnumProperty.create("kind", PlaceKind.class);

    public static final MapCodec<SpiritVeinBlock> CODEC = simpleCodec(SpiritVeinBlock::new);

    public SpiritVeinBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(KIND, PlaceKind.ALTAR));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(KIND);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState();
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpiritVeinBlockEntity(pos, state);
    }

    /** Свечение камня: заметен ночью, но не фонарь. */
    public static int light(BlockState state) {
        return 7;
    }
}
