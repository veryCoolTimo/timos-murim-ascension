package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import org.jetbrains.annotations.Nullable;

/**
 * Round window 2x2 (author's ref docs/design/reference/decor/round_window.png): a plaster wall square with a round
 * opening, the red lacquer ring of the building kit and a thin lattice with the Hua plum in the middle.
 * FACING looks at the player who placed it; the clicked spot becomes the BOTTOM_LEFT quarter (as the viewer sees
 * it), the others go to the viewer's right and up.
 *
 * <p>Four quarters the way the bed and the sect plaque do two: placement is refused when any other spot is not
 * free; each quarter turns to air (with {@code updateOrDestroy}) when one of its two partners is gone, so breaking
 * any quarter takes the whole window; only BOTTOM_LEFT has loot (one window back, never four); in creative breaking
 * another quarter removes BOTTOM_LEFT first without drops.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/BedBlock.java (#getStateForPlacement,
 * #setPlacedBy, #updateShape, #playerWillDestroy); SectPlaqueBlock in this package.
 */
public class RoundWindowBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<RoundWindowBlock> CODEC = simpleCodec(RoundWindowBlock::new);
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    public RoundWindowBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.BOTTOM_LEFT));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    /** The viewer's right in front of a window facing {@code facing}. */
    public static Direction right(Direction facing) {
        return facing.getCounterClockWise();
    }

    /** Position of {@code part} for a window whose BOTTOM_LEFT quarter is at {@code origin}. */
    public static BlockPos partPos(BlockPos origin, Direction facing, Part part) {
        return origin.relative(right(facing), part.right ? 1 : 0).above(part.top ? 1 : 0);
    }

    /** BOTTOM_LEFT position of the window that {@code state} at {@code pos} belongs to. */
    public static BlockPos origin(BlockPos pos, BlockState state) {
        Part p = state.getValue(PART);
        return pos.relative(right(state.getValue(FACING)), p.right ? -1 : 0).below(p.top ? 1 : 0);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos origin = context.getClickedPos();
        Level level = context.getLevel();
        for (Part part : Part.values()) {
            if (part == Part.BOTTOM_LEFT) {
                continue;
            }
            BlockPos p = partPos(origin, facing, part);
            if (!level.getBlockState(p).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(p)
                    || p.getY() >= level.getMaxBuildHeight()) {
                return null;
            }
        }
        return defaultBlockState().setValue(FACING, facing).setValue(PART, Part.BOTTOM_LEFT);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide) {
            Direction facing = state.getValue(FACING);
            for (Part part : Part.values()) {
                if (part != Part.BOTTOM_LEFT) {
                    level.setBlock(partPos(pos, facing, part), state.setValue(PART, part), Block.UPDATE_ALL);
                }
            }
            level.blockUpdated(pos, Blocks.AIR);
            state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        Part part = state.getValue(PART);
        Direction r = right(state.getValue(FACING));
        Direction side = part.right ? r.getOpposite() : r;
        Direction vertical = part.top ? Direction.DOWN : Direction.UP;
        if (dir == side || dir == vertical) {
            Part expected = dir == side ? part.flipH() : part.flipV();
            boolean ok = neighbour.is(this) && neighbour.getValue(FACING) == state.getValue(FACING)
                    && neighbour.getValue(PART) == expected;
            return ok ? state : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // Creative: take the quarter with the loot away first, without drops (BedBlock#preventDropFromHeadPart idea).
        if (!level.isClientSide && player.isCreative() && state.getValue(PART) != Part.BOTTOM_LEFT) {
            BlockPos o = origin(pos, state);
            BlockState bl = level.getBlockState(o);
            if (bl.is(this) && bl.getValue(PART) == Part.BOTTOM_LEFT) {
                level.setBlock(o, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, o, Block.getId(bl));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
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
        if (mirror == Mirror.NONE) {
            return state;
        }
        // A mirror swaps the viewer's left and right: quarters trade sides so the window still holds together.
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING))).setValue(PART, state.getValue(PART).flipH());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    public enum Part implements StringRepresentable {
        BOTTOM_LEFT("bottom_left", false, false),
        BOTTOM_RIGHT("bottom_right", true, false),
        TOP_LEFT("top_left", false, true),
        TOP_RIGHT("top_right", true, true);

        private final String name;
        public final boolean right;
        public final boolean top;

        Part(String name, boolean right, boolean top) {
            this.name = name;
            this.right = right;
            this.top = top;
        }

        public static Part of(boolean right, boolean top) {
            return top ? (right ? TOP_RIGHT : TOP_LEFT) : (right ? BOTTOM_RIGHT : BOTTOM_LEFT);
        }

        public Part flipH() {
            return of(!right, top);
        }

        public Part flipV() {
            return of(right, !top);
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
