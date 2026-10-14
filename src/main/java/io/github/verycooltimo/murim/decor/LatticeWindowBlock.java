package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Lattice window in a red lacquer frame (author 07.10, refs docs/design/reference/build2/ref_05.png, ref_06.png,
 * ref_08.png). Joins exactly like {@link FramedPanelBlock} (same UP/DOWN/LEFT/RIGHT edges, same block and facing only):
 * the multipart blockstate draws the wide 3D outer frame on free edges only and nothing on joined ones (author 10.10:
 * inside, the window is only the flat pixel lattice, like the round window). The same four booleans pick one of 16
 * lattice panes, so a block column is one ref_05 panel with the Mount Hua plum medallion; no extra block state.
 * The lattice aperture is unchanged: rails cover 2 px inside and 2 px over the adjacent plaster. Frame depth is
 * z 2..14 (2 px proud of a centred thin wall), with a deeper z 0..16 sill. Corners are mitred in the OBJ mesh.
 * OBJ models and panes: tools/art/hall_windows.py, tools/art/lattice_panes.py.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/IronBarsBlock.java (thin connecting pane shape).
 */
public class LatticeWindowBlock extends FramedPanelBlock {

    public static final MapCodec<LatticeWindowBlock> CODEC = simpleCodec(LatticeWindowBlock::new);

    public LatticeWindowBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape shape = box(state, 0, 0, 5, 16, 16, 11);
        double x0 = state.getValue(RIGHT) ? 0 : -2;
        double x1 = state.getValue(LEFT) ? 16 : 18;
        double y0 = state.getValue(DOWN) ? 0 : -2;
        double y1 = state.getValue(UP) ? 16 : 18;
        if (!state.getValue(UP)) {
            shape = Shapes.or(shape, box(state, x0, 14, 2, x1, 18, 14));
        }
        if (!state.getValue(DOWN)) {
            shape = Shapes.or(shape, box(state, x0, -2, 0, x1, 2, 16));
        }
        if (!state.getValue(LEFT)) {
            shape = Shapes.or(shape, box(state, 14, y0, 2, 18, y1, 14));
        }
        if (!state.getValue(RIGHT)) {
            shape = Shapes.or(shape, box(state, -2, y0, 2, 2, y1, 14));
        }
        return shape;
    }

    private static VoxelShape box(BlockState state, double x0, double y0, double z0,
                                  double x1, double y1, double z1) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> Block.box(16 - x1, y0, 16 - z1, 16 - x0, y1, 16 - z0);
            case EAST -> Block.box(16 - z1, y0, x0, 16 - z0, y1, x1);
            case WEST -> Block.box(z0, y0, 16 - x1, z1, y1, 16 - x0);
            default -> Block.box(x0, y0, z0, x1, y1, z1);
        };
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }
}
