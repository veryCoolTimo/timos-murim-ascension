package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * A slim lattice transom (coordinator/author 15.10: «сделать как люки, похожей текстуры как окна, и поставить над
 * дверью»): a full 16 x 16 face panel, 3 px thick, red frame and paper lattice like {@code lattice_window_paper}. Meant
 * to sit above a door instead of a full window. FACING is where the front looks; CENTERED puts the panel in the
 * middle of the block (flush with the thin centred wall segments of the hall), otherwise it hangs on the back of the
 * block like a trapdoor on a wall. Models: tools/art/lattice_transom.py.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/TrapDoorBlock.java (shape per facing),
 * HorizontalDirectionalBlock.java.
 */
public class LatticeTransomBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<LatticeTransomBlock> CODEC = simpleCodec(LatticeTransomBlock::new);
    public static final BooleanProperty CENTERED = BooleanProperty.create("centered");

    private static final VoxelShape[] BACK = {
            Block.box(0, 0, 13, 16, 16, 16), // north
            Block.box(0, 0, 0, 16, 16, 3),   // south
            Block.box(13, 0, 0, 16, 16, 16), // west
            Block.box(0, 0, 0, 3, 16, 16),   // east
    };
    private static final VoxelShape[] MIDDLE = {
            Block.box(0, 0, 6.5, 16, 16, 9.5), // north
            Block.box(0, 0, 6.5, 16, 16, 9.5), // south
            Block.box(6.5, 0, 0, 9.5, 16, 16), // west
            Block.box(6.5, 0, 0, 9.5, 16, 16), // east
    };

    public LatticeTransomBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(CENTERED, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clicked = context.getClickedFace();
        Direction facing = clicked.getAxis().isHorizontal() ? clicked : context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing).setValue(CENTERED, context.getPlayer() != null && context.getPlayer().isShiftKeyDown());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, net.minecraft.core.BlockPos pos, CollisionContext context) {
        int i = state.getValue(FACING).get3DDataValue() - 2;
        return state.getValue(CENTERED) ? MIDDLE[i] : BACK[i];
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CENTERED);
    }
}
