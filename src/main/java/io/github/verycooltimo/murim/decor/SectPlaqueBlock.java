package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
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
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Sect name plaque (ref docs/design/reference/decor/photo_2026-10-06_10_53_31.jpeg): a board two blocks wide that
 * hangs on a wall or under a beam. FACING is where the inscription looks; the back sits against the block behind.
 * The clicked spot becomes the LEFT half (as the viewer reads it), the RIGHT half goes to the viewer's right.
 *
 * <p>Two halves the way the bed does it: placement is refused when the second spot is not free, each half drops to
 * air when its partner goes, only the LEFT half has loot (so explosions and partner removal never duplicate), and in
 * creative breaking the RIGHT half removes the LEFT one without drops.
 *
 * <p>Flush on a pillar or beam (author 07.10: «табличка секты должна прилегать прямо к колонне»): {@link #INSET} is
 * how far (in half pixels) the board moves back into the block behind it, read from that block's real shape —
 * a full block gives 0, a centred 5 px pillar 11 (5.5 px), a beam at the far side more. Both halves keep the
 * inset of the block the LEFT half was placed against, so the board stays straight.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/BedBlock.java
 * (#getStateForPlacement, #setPlacedBy, #updateShape, #playerWillDestroy), WallSignBlock.java#getStateForPlacement.
 */
public class SectPlaqueBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<SectPlaqueBlock> CODEC = simpleCodec(SectPlaqueBlock::new);
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final net.minecraft.world.level.block.state.properties.IntegerProperty INSET =
            net.minecraft.world.level.block.state.properties.IntegerProperty.create("inset", 0, 31);

    // Board y 2..14, 2 px deep (board 1 + frame 1) against the back of the block.
    private static final VoxelShape NORTH = Block.box(0, 2, 14, 16, 14, 16);
    private static final VoxelShape SOUTH = Block.box(0, 2, 0, 16, 14, 2);
    private static final VoxelShape WEST = Block.box(14, 2, 0, 16, 14, 16);
    private static final VoxelShape EAST = Block.box(0, 2, 0, 2, 14, 16);

    public SectPlaqueBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.LEFT).setValue(INSET, 0));
    }

    /**
     * Gap in half pixels between this block's back face and the surface of the support behind it, measured on the
     * support's shape; 0 for a full block or nothing. API: BlockState#getShape, VoxelShape#min/max(Axis).
     */
    public static int insetFor(Direction facing, BlockGetter level, BlockPos supportPos) {
        VoxelShape shape = level.getBlockState(supportPos).getShape(level, supportPos);
        if (shape.isEmpty()) {
            return 0;
        }
        Direction.Axis axis = facing.getAxis();
        // the support lies behind the board, toward -facing; its face toward us is its max (facing positive) or min
        double gap = facing.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 - shape.max(axis) : shape.min(axis);
        return (int) Math.max(0, Math.min(31, Math.round(gap * 32)));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    /** Direction from this half to its partner: the viewer faces {@code -facing}, so their right is facing's CCW. */
    public static Direction toPartner(BlockState state) {
        Direction right = state.getValue(FACING).getCounterClockWise();
        return state.getValue(PART) == Part.LEFT ? right : right.getOpposite();
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clicked = context.getClickedFace();
        // On a wall: face away from it. Under a beam or on a floor: face the player.
        Direction facing = clicked.getAxis().isHorizontal() ? clicked : context.getHorizontalDirection().getOpposite();
        Level level = context.getLevel();
        BlockPos support = context.getClickedPos().relative(facing.getOpposite());
        BlockState state = defaultBlockState().setValue(FACING, facing).setValue(PART, Part.LEFT)
                .setValue(INSET, insetFor(facing, level, support));
        BlockPos other = context.getClickedPos().relative(toPartner(state));
        return level.getBlockState(other).canBeReplaced(context) && level.getWorldBorder().isWithinBounds(other) ? state : null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
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
            return neighbour.is(this) && neighbour.getValue(PART) != state.getValue(PART)
                    && neighbour.getValue(FACING) == state.getValue(FACING) ? state : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // Creative: the RIGHT half takes the LEFT one (the one with loot) without drops, like the bed's foot/head.
        if (!level.isClientSide && player.isCreative() && state.getValue(PART) == Part.RIGHT) {
            BlockPos other = pos.relative(toPartner(state));
            BlockState left = level.getBlockState(other);
            if (left.is(this) && left.getValue(PART) == Part.LEFT) {
                level.setBlock(other, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, other, Block.getId(left));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        VoxelShape base = switch (facing) {
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
            default -> NORTH;
        };
        double back = state.getValue(INSET) / 32.0; // moves toward the support, i.e. along -facing
        return base.move(-facing.getStepX() * back, 0, -facing.getStepZ() * back);
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
        // Any mirror swaps the viewer's left and right: the halves trade parts so the text still reads 大華山派.
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING))).setValue(PART, state.getValue(PART).other());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, INSET);
    }

    public enum Part implements StringRepresentable {
        LEFT("left"), RIGHT("right");

        private final String name;

        Part(String name) {
            this.name = name;
        }

        public Part other() {
            return this == LEFT ? RIGHT : LEFT;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
