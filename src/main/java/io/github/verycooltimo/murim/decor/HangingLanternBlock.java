package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
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
 * Hall lantern (author 07.10, ref docs/design/reference/build2/ref_05.png): hangs on a cord under a beam or ceiling
 * ({@link Attach#CEILING}), on a bracket arm from a wall ({@link Attach#WALL}, FACING points away from the wall, as the
 * vanilla wall torch), or stands on the floor ({@link Attach#FLOOR}). Models: OBJ from tools/art/hall_lights.py.
 *
 * <p>Placement takes the clicked face first (under a block = ceiling, a wall side = wall, a top = floor), then the
 * other directions in the order the player looks (BlockPlaceContext#getNearestLookingDirections). The lantern drops
 * when its support goes (updateShape returns air, Block#updateOrDestroy drops it).
 *
 * <p>Support: a sturdy centre like the vanilla lantern (reference/minecraft-src/net/minecraft/world/level/block/
 * LanternBlock.java#canSurvive, Block#canSupportCenter), or any block with a collision shape on that side (the
 * timber kit beams and brackets are not full faces).
 */
public class HangingLanternBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<HangingLanternBlock> CODEC = simpleCodec(HangingLanternBlock::new);
    public static final EnumProperty<Attach> ATTACH = EnumProperty.create("attach", Attach.class);

    private static final VoxelShape CEILING_SHAPE = Shapes.or(Block.box(3, 0, 3, 13, 12.5, 13), Block.box(6.5, 12.5, 6.5, 9.5, 16, 9.5));
    private static final VoxelShape FLOOR_SHAPE = Block.box(2.5, 0, 2.5, 13.5, 12.5, 13.5);
    private static final Map<Direction, VoxelShape> WALL_SHAPES = new EnumMap<>(Direction.class);

    static {
        // Base: wall on the north side (z 0), lantern out towards +z, i.e. FACING south.
        for (Direction d : Direction.Plane.HORIZONTAL) {
            WALL_SHAPES.put(d, Shapes.or(turn(3.5, 0, 3.5, 12.5, 12, 12.5, d), turn(6.5, 8.5, 0, 9.5, 16, 10, d)));
        }
    }

    public HangingLanternBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH).setValue(ATTACH, Attach.CEILING));
    }

    /** Box given for FACING south (wall at z 0), turned for {@code facing}. */
    private static VoxelShape turn(double x0, double y0, double z0, double x1, double y1, double z1, Direction facing) {
        return switch (facing) {
            case NORTH -> Block.box(16 - x1, y0, 16 - z1, 16 - x0, y1, 16 - z0);
            case EAST -> Block.box(z0, y0, 16 - x1, z1, y1, 16 - x0);
            case WEST -> Block.box(16 - z1, y0, x0, 16 - z0, y1, x1);
            default -> Block.box(x0, y0, z0, x1, y1, z1);
        };
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    /** Direction from the lantern to the block that holds it. */
    public static Direction supportDir(BlockState state) {
        return switch (state.getValue(ATTACH)) {
            case CEILING -> Direction.UP;
            case FLOOR -> Direction.DOWN;
            case WALL -> state.getValue(FACING).getOpposite();
        };
    }

    private static boolean holds(LevelReader level, BlockPos pos, Direction towards) {
        BlockPos p = pos.relative(towards);
        BlockState s = level.getBlockState(p);
        if (Block.canSupportCenter(level, p, towards.getOpposite())) {
            return true;
        }
        return !(s.getBlock() instanceof HangingLanternBlock) && !s.getCollisionShape(level, p).isEmpty();
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return holds(level, pos, supportDir(state));
    }

    private BlockState forFace(Direction towardsSupport, Direction horizontalLook) {
        if (towardsSupport == Direction.UP) {
            return defaultBlockState().setValue(ATTACH, Attach.CEILING).setValue(FACING, horizontalLook.getOpposite());
        }
        if (towardsSupport == Direction.DOWN) {
            return defaultBlockState().setValue(ATTACH, Attach.FLOOR).setValue(FACING, horizontalLook.getOpposite());
        }
        return defaultBlockState().setValue(ATTACH, Attach.WALL).setValue(FACING, towardsSupport.getOpposite());
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        LevelReader level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction look = context.getHorizontalDirection();
        BlockState first = forFace(context.getClickedFace().getOpposite(), look);
        if (first.canSurvive(level, pos)) {
            return first;
        }
        if (context.getPlayer() == null) {
            // Direction#orderedByNearest needs an entity (dispensers, tests): the clicked face only.
            return null;
        }
        for (Direction d : context.getNearestLookingDirections()) {
            BlockState s = forFace(d, look);
            if (s.canSurvive(level, pos)) {
                return s;
            }
        }
        return null;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        if (dir == supportDir(state) && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(ATTACH)) {
            case CEILING -> CEILING_SHAPE;
            case FLOOR -> FLOOR_SHAPE;
            case WALL -> WALL_SHAPES.get(state.getValue(FACING));
        };
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
        builder.add(FACING, ATTACH);
    }

    public enum Attach implements StringRepresentable {
        CEILING("ceiling"),
        WALL("wall"),
        FLOOR("floor");

        private final String name;

        Attach(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
