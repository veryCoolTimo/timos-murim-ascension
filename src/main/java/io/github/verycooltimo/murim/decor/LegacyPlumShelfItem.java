package io.github.verycooltimo.murim.decor;

import java.util.Map;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Old inventory stacks place the unified two-high rack, preserving the old fill. */
public class LegacyPlumShelfItem extends BlockItem {
    private final int variant;
    private final Block legacyBlock;

    public LegacyPlumShelfItem(Properties properties, Block legacyBlock, int variant) {
        super(HallFurniture.SCROLL_SHELF.get(), properties);
        this.legacyBlock = legacyBlock;
        this.variant = variant;
    }

    @Override
    protected @Nullable BlockState getPlacementState(BlockPlaceContext context) {
        BlockState state = super.getPlacementState(context);
        return state == null ? null : state.setValue(PlumShelfBlock.VARIANT, variant);
    }

    @Override
    public String getDescriptionId() {
        return legacyBlock.getDescriptionId();
    }

    @Override
    public void registerBlocks(Map<Block, Item> map, Item item) {
        // Give old blocks their compatibility item without replacing the canonical block -> item mapping.
        map.put(legacyBlock, item);
    }
}
