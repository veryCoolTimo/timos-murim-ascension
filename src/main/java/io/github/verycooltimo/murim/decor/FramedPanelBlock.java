package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;

/**
 * Lacquer wall panel with a moulding frame (author's ref docs/design/reference/decor/framed_panel.png). Panels
 * placed side by side in one wall plane join: the frame strip is drawn only on edges that have no panel of the same
 * facing next to them, so a 3x6 or 2x5 patch shows ONE frame round the whole rectangle (like connected textures).
 *
 * <p>FACING is where the front looks (towards the player who placed it). LEFT/RIGHT are as the viewer in front sees
 * them: the viewer looks along {@code -facing}, so their right is {@code facing.getCounterClockWise()} (same rule as
 * {@link SectPlaqueBlock#toPartner}). The models are multipart pieces, see tools/art/decor_blocks_2.py#PANEL_PIECES.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/IronBarsBlock.java (#getStateForPlacement,
 * #updateShape: connection booleans recomputed from the neighbour), HorizontalDirectionalBlock.java,
 * state/properties/BlockStateProperties.java#UP/#DOWN.
 */
public class FramedPanelBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<FramedPanelBlock> CODEC = simpleCodec(FramedPanelBlock::new);
    public static final BooleanProperty UP = BlockStateProperties.UP;
    public static final BooleanProperty DOWN = BlockStateProperties.DOWN;
    public static final BooleanProperty LEFT = BooleanProperty.create("left");
    public static final BooleanProperty RIGHT = BooleanProperty.create("right");

    public FramedPanelBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)
                .setValue(UP, false).setValue(DOWN, false).setValue(LEFT, false).setValue(RIGHT, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    /** The viewer's right in front of a panel facing {@code facing}. */
    public static Direction right(Direction facing) {
        return facing.getCounterClockWise();
    }

    /** Same block only: murim:framed_panel and murim:framed_panel_b are two instances and never join each other. */
    private boolean joins(BlockState self, BlockState other) {
        return other.is(this) && other.getValue(FACING) == self.getValue(FACING);
    }

    private BlockState connect(BlockState state, BlockGetter level, BlockPos pos) {
        Direction r = right(state.getValue(FACING));
        return state.setValue(UP, joins(state, level.getBlockState(pos.above())))
                .setValue(DOWN, joins(state, level.getBlockState(pos.below())))
                .setValue(RIGHT, joins(state, level.getBlockState(pos.relative(r))))
                .setValue(LEFT, joins(state, level.getBlockState(pos.relative(r.getOpposite()))));
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        return connect(state, context.getLevel(), context.getClickedPos());
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        Direction r = right(state.getValue(FACING));
        boolean j = joins(state, neighbour);
        if (dir == Direction.UP) {
            return state.setValue(UP, j);
        }
        if (dir == Direction.DOWN) {
            return state.setValue(DOWN, j);
        }
        if (dir == r) {
            return state.setValue(RIGHT, j);
        }
        if (dir == r.getOpposite()) {
            return state.setValue(LEFT, j);
        }
        return state;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        if (mirror == Mirror.NONE) {
            return state;
        }
        // A mirror swaps the viewer's left and right.
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)))
                .setValue(LEFT, state.getValue(RIGHT)).setValue(RIGHT, state.getValue(LEFT));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, UP, DOWN, LEFT, RIGHT);
    }
}
