package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Small tabletop wooden, flat-lid counterpart of the tabletop box. Shares its authoritative interactions. */
public final class PillCrateBlock extends PillBoxBlock {
    public static final MapCodec<PillCrateBlock> CODEC = simpleCodec(PillCrateBlock::new);

    public PillCrateBlock(Properties properties) { super(properties); }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }

    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/phys/shapes/Shapes.java#or;
    // StoShapes.turn delegates to verified Block.java#box. The open outline follows
    // the flat lid at rest (105 degrees), conservatively sliced into six cuboids.
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        var facing = state.getValue(FACING);
        VoxelShape body = StoShapes.turn(5.2, 0, 5.4, 10.8, 3.2, 10.47, facing);
        VoxelShape lid;
        if (state.getValue(OPEN)) {
            lid = body;
            double sin = Math.sin(Math.toRadians(105)), cos = Math.cos(Math.toRadians(105));
            for (int i = 0; i < 6; i++) {
                double z0 = 5.35 + i * (5.05 / 6), z1 = z0 + 5.05 / 6;
                double yMin = 3.2 + 1.87 * cos - (z1 - 10.4) * sin;
                double yMax = 3.2 - (z0 - 10.4) * sin;
                double zMin = 10.4 + (z1 - 10.4) * cos;
                double zMax = 10.4 + 1.87 * sin + (z0 - 10.4) * cos;
                lid = Shapes.or(lid, StoShapes.turn(5.2, yMin, zMin, 10.8, yMax, zMax, facing));
            }
        } else {
            lid = StoShapes.turn(5.2, 3.2, 5.35, 10.8, 5.07, 10.4, facing);
        }
        return TabletopSupport.lower(Shapes.or(body, lid), state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }
}
