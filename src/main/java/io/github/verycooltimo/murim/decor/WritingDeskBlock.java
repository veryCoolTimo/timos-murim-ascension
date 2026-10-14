package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Low writing desk two blocks wide (ref_09/ref_10 desks; low, for sitting on a cushion): FACING turns the front to the
 * player who placed it, the clicked block is the LEFT part, the RIGHT part goes to the player's right
 * ({@code facing.getCounterClockWise()}, the model's +x in the south frame of tools/art/hall_furniture.py).
 *
 * <p>Two parts the way the vanilla bed does it: placement refused when the right spot is taken, the partner set in
 * {@link #setPlacedBy}, a part turns to air when its partner goes, only the LEFT part has loot, creative breaking of the
 * right part removes the left without drops.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/BedBlock.java (#getStateForPlacement, #setPlacedBy,
 * #updateShape, #playerWillDestroy).
 */
public class WritingDeskBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<WritingDeskBlock> CODEC = simpleCodec(WritingDeskBlock::new);
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    private static final Map<Direction, VoxelShape> LEFT = new EnumMap<>(Direction.class);
    private static final Map<Direction, VoxelShape> RIGHT = new EnumMap<>(Direction.class);

    static {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            LEFT.put(d, Shapes.or(HallFurniture.turn(0, 8.5, 2, 16, 11, 14, d), HallFurniture.turn(2.2, 0, 2, 5.3, 8.5, 14, d)));
            RIGHT.put(d, Shapes.or(HallFurniture.turn(0, 8.5, 2, 16, 11, 14, d), HallFurniture.turn(10.7, 0, 2, 13.8, 8.5, 14, d)));
        }
    }

    public WritingDeskBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH).setValue(PART, Part.LEFT));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    /** Direction from this part to its partner. */
    public static Direction toPartner(BlockState state) {
        Direction right = state.getValue(FACING).getCounterClockWise();
        return state.getValue(PART) == Part.LEFT ? right : right.getOpposite();
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos right = context.getClickedPos().relative(facing.getCounterClockWise());
        Level level = context.getLevel();
        if (!level.getBlockState(right).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(right)) {
            return null;
        }
        return defaultBlockState().setValue(FACING, facing).setValue(PART, Part.LEFT);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide) {
            level.setBlock(pos.relative(toPartner(state)), state.setValue(PART, Part.RIGHT), Block.UPDATE_ALL);
            level.blockUpdated(pos, Blocks.AIR);
            state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        if (dir == toPartner(state)) {
            boolean ok = neighbour.is(this) && neighbour.getValue(PART) != state.getValue(PART)
                    && neighbour.getValue(FACING) == state.getValue(FACING);
            return ok ? state : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && state.getValue(PART) == Part.RIGHT) {
            BlockPos other = pos.relative(toPartner(state));
            BlockState b = level.getBlockState(other);
            if (b.is(this) && b.getValue(PART) == Part.LEFT) {
                level.setBlock(other, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, other, Block.getId(b));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return (state.getValue(PART) == Part.LEFT ? LEFT : RIGHT).get(state.getValue(FACING));
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
        // A mirrored desk would put its right part on the left: keep the parts, turn like the vanilla bed.
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    public enum Part implements StringRepresentable {
        LEFT("left"),
        RIGHT("right");

        private final String name;

        Part(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
