package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/**
 * A hall plaque: the four-block board of {@link SectPlaqueWideBlock} (placement, parts, inset, loot, shape) with its own
 * calligraphy (tools/art/anc_plaques.py): 祖師堂 for the Hall of Ancestors, 藏經閣 for the Hall of Scriptures. The
 * behaviour lives entirely in the parent, which tests {@code neighbour.is(this)}, so two plaque blocks never merge.
 */
public class AncPlaqueBlock extends SectPlaqueWideBlock {

    public static final MapCodec<AncPlaqueBlock> ANC_CODEC = simpleCodec(AncPlaqueBlock::new);

    public AncPlaqueBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return ANC_CODEC;
    }
}
