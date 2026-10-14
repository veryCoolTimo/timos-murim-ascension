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
    public MapCodec<? extends DoorBlock> codec() {
        return CODEC;
    }
}
