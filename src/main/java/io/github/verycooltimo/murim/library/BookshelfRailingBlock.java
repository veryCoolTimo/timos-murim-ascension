package io.github.verycooltimo.murim.library;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.FenceBlock;

/** Open wooden library meander railing with the vanilla fence's connections, waterlogging and 1.5-block collision. */
public class BookshelfRailingBlock extends FenceBlock {
    public static final MapCodec<FenceBlock> CODEC = simpleCodec(BookshelfRailingBlock::new);

    public BookshelfRailingBlock(Properties properties) { super(properties); }
    @Override public MapCodec<FenceBlock> codec() { return CODEC; }
}
