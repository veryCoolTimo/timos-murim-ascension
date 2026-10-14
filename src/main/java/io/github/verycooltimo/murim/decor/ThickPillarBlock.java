package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import io.github.verycooltimo.murim.world.build.RedPillarBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Twelve-pixel chamfered hall post. Inherits the timber kit's axis, joints, placement and bridge entity.
 * Edge slots have centres 6/10 rather than 3/13 so the wider post stays inside its block. Existing five-pixel
 * beam ends and bridge anchors fit inside it. A free standing bottom has a stone foot, a free top an abacus.
 * Art and the complete 1,350-state multipart mapping: tools/art/hall_joinery.py.
 */
public final class ThickPillarBlock extends RedPillarBlock {

    public static final MapCodec<ThickPillarBlock> CODEC = simpleCodec(ThickPillarBlock::new);

    public ThickPillarBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    private static double centre(int slot) {
        return 8 + 2 * slot;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        var offset = state.getValue(OFFSET);
        double a = centre(offset.dx());
        double b = centre(offset.dz());
        Direction.Axis axis = state.getValue(AXIS);
        VoxelShape post = switch (axis) {
            case Y -> Block.box(a - 5, 0, b - 5, a + 5, 16, b + 5);
            case X -> Block.box(0, a - 5, b - 5, 16, a + 5, b + 5);
            case Z -> Block.box(a - 5, b - 5, 0, a + 5, b + 5, 16);
        };
        // Includes the inherited bridge collision, which still uses the timber kit's shared anchors.
        return Shapes.or(post, super.getShape(state, level, pos, context));
    }
}
