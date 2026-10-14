package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Open shelf two blocks tall (ref_09 shelves; Hall of Scriptures): rectangular scroll bundles, books, mixed or sparse
 * filling on a shared frame. Two halves exactly like {@link StoneLanternBlock} (placement, partner, loot on the lower
 * half, creative break); only the shape differs. Model: tools/art/hall_furniture.py.
 */
public class ScrollShelfBlock extends StoneLanternBlock {

    public static final MapCodec<ScrollShelfBlock> CODEC = simpleCodec(ScrollShelfBlock::new);
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            SHAPES.put(d, HallFurniture.turn(0, 0, 1, 16, 16, 15, d));
        }
    }

    public ScrollShelfBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }
}
