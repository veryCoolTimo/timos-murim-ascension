package io.github.verycooltimo.murim.decor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Sect bed: a real {@link BedBlock} (sleeping, spawn point, OCCUPIED, the sect AI's BedBlock checks all apply) drawn with
 * an ordinary block model instead of the dye-coloured bed entity renderer, so it has no block entity. The head part has
 * a headboard that also collides.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/BedBlock.java (#getRenderShape, #newBlockEntity).
 */
public class StoBedBlock extends BedBlock {
    public StoBedBlock(Properties properties) {
        super(DyeColor.RED, properties);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return null;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape base = super.getShape(state, level, pos, context);
        if (state.getValue(PART) == BedPart.HEAD) {
            return Shapes.or(base, StoShapes.turn(0, 0, 0, 16, 13, 1.6, state.getValue(FACING)));
        }
        return base;
    }
}
