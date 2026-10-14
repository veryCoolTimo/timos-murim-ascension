package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Sect gate: a double door 4 wide and 4 high (author's refs docs/design/reference/decor/door_ref_1.png, door_ref_2.png,
 * thin_frame_3.png): two red lacquer leaves of 2x4, the Hua plum on each, ring handles (the studded band is its own
 * block, {@link StuddedBandBlock}, placed above).
 *
 * <p>Sixteen parts, COL 0..3 from the viewer's left to right, ROW 0..3 from the bottom; FACING looks at the player who
 * placed it, the clicked spot becomes COL 1 ROW 0 (the door stands centred on the player). Multi-block the way
 * {@link RoundWindowBlock} does it: placement is refused when any spot is taken; a part turns to air when an
 * in-plane neighbour of the same door is gone, so breaking any part takes the whole gate; only COL 0 ROW 0 (the
 * origin) has loot; in creative the origin is removed first without drops.
 *
 * <p>Opening: the leaves swing inwards (away from the front) on their outer hinges over {@link #SWING_TICKS}. The
 * origin's block entity counts the swing and the clients animate it (block event, like the chest lid); the gate's
 * parts are drawn by {@code client/decor/GateDoorRenderer} only (render shape ENTITYBLOCK_ANIMATED), so no chunk
 * model stands still while the leaves move. Collision: OPEN is set at the END of the opening swing (solid while it
 * moves) and cleared at the START of the closing one.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/DoorBlock.java (#useWithoutItem, #setOpen,
 * #neighborChanged, #playSound), BedBlock.java (#setPlacedBy, #updateShape, #playerWillDestroy), ChestBlock.java
 * (#getRenderShape ENTITYBLOCK_ANIMATED), BaseEntityBlock.java#triggerEvent, ServerLevel.java#doBlockEvent.
 */
public class GateDoorBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final MapCodec<GateDoorBlock> CODEC = simpleCodec(GateDoorBlock::new);
    public static final IntegerProperty COL = IntegerProperty.create("col", 0, 3);
    public static final IntegerProperty ROW = IntegerProperty.create("row", 0, 3);
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final int SIZE = 4;
    /** 0.7 s swing (task 06.10: ~90° over 0.6-0.8 s). */
    public static final int SWING_TICKS = 14;
    /** Block event id: the swing starts, param 1 = opening, 0 = closing. */
    public static final int EVENT_SWING = 1;

    /** Leaf: 3 px thick in the middle of the block, z 6.5..9.5 in the north-facing model frame. */
    private static final VoxelShape[] SHAPES = {
            Block.box(0, 0, 6.5, 16, 16, 9.5), // north
            Block.box(0, 0, 6.5, 16, 16, 9.5), // south
            Block.box(6.5, 0, 0, 9.5, 16, 16), // west
            Block.box(6.5, 0, 0, 9.5, 16, 16), // east
    };

    public GateDoorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(COL, 0).setValue(ROW, 0)
                .setValue(OPEN, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    /** The viewer's right in front of a gate facing {@code facing} (the viewer looks along −facing). */
    public static Direction right(Direction facing) {
        return facing.getCounterClockWise();
    }

    public static BlockPos partPos(BlockPos origin, Direction facing, int col, int row) {
        return origin.relative(right(facing), col).above(row);
    }

    /** COL 0 ROW 0 of the gate that {@code state} at {@code pos} belongs to. */
    public static BlockPos origin(BlockPos pos, BlockState state) {
        return pos.relative(right(state.getValue(FACING)), -state.getValue(COL)).below(state.getValue(ROW));
    }

    public static boolean isOrigin(BlockState state) {
        return state.getValue(COL) == 0 && state.getValue(ROW) == 0;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos origin = context.getClickedPos().relative(right(facing), -1);
        Level level = context.getLevel();
        for (int col = 0; col < SIZE; col++) {
            for (int row = 0; row < SIZE; row++) {
                BlockPos p = partPos(origin, facing, col, row);
                if (!level.getBlockState(p).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(p)
                        || p.getY() >= level.getMaxBuildHeight()) {
                    return null;
                }
            }
        }
        return defaultBlockState().setValue(FACING, facing).setValue(COL, 1).setValue(ROW, 0);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide) {
            BlockPos origin = origin(pos, state);
            Direction facing = state.getValue(FACING);
            for (int col = 0; col < SIZE; col++) {
                for (int row = 0; row < SIZE; row++) {
                    BlockPos p = partPos(origin, facing, col, row);
                    if (!p.equals(pos)) {
                        level.setBlock(p, state.setValue(COL, col).setValue(ROW, row), Block.UPDATE_ALL);
                    }
                }
            }
            level.blockUpdated(pos, Blocks.AIR);
            state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        Direction facing = state.getValue(FACING);
        Direction r = right(facing);
        int col = state.getValue(COL);
        int row = state.getValue(ROW);
        int wantCol = col;
        int wantRow = row;
        if (dir == r) {
            wantCol++;
        } else if (dir == r.getOpposite()) {
            wantCol--;
        } else if (dir == Direction.UP) {
            wantRow++;
        } else if (dir == Direction.DOWN) {
            wantRow--;
        } else {
            return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
        }
        if (wantCol < 0 || wantCol >= SIZE || wantRow < 0 || wantRow >= SIZE) {
            return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
        }
        boolean ok = neighbour.is(this) && neighbour.getValue(FACING) == facing
                && neighbour.getValue(COL) == wantCol && neighbour.getValue(ROW) == wantRow;
        return ok ? state : Blocks.AIR.defaultBlockState();
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // Creative: take the part with the loot away first, without drops (BedBlock#preventDropFromHeadPart idea).
        if (!level.isClientSide && player.isCreative() && !isOrigin(state)) {
            BlockPos o = origin(pos, state);
            BlockState os = level.getBlockState(o);
            if (os.is(this) && isOrigin(os)) {
                level.setBlock(o, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, o, Block.getId(os));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    // ------------------------------------------------------------------ opening

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            toggle(level, origin(pos, state), player);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Starts the swing of the gate at {@code origin}; false while a swing is still under way. */
    public boolean toggle(Level level, BlockPos origin, @Nullable Entity who) {
        BlockState o = level.getBlockState(origin);
        if (!o.is(this) || !(level.getBlockEntity(origin) instanceof GateDoorBlockEntity be) || be.busy()) {
            return false;
        }
        boolean opening = !o.getValue(OPEN);
        if (opening) {
            be.startOpening();
        } else {
            setOpen(level, origin, false);
            be.startClosing();
        }
        level.blockEvent(origin, this, EVENT_SWING, opening ? 1 : 0);
        playSound(level, origin, o.getValue(FACING), opening);
        level.gameEvent(who, opening ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, origin);
        return true;
    }

    /** OPEN on all sixteen parts (flags 10 as DoorBlock#setOpen: clients, no neighbour updates). */
    public void setOpen(Level level, BlockPos origin, boolean open) {
        BlockState o = level.getBlockState(origin);
        if (!o.is(this)) {
            return;
        }
        Direction facing = o.getValue(FACING);
        for (int col = 0; col < SIZE; col++) {
            for (int row = 0; row < SIZE; row++) {
                BlockPos p = partPos(origin, facing, col, row);
                BlockState s = level.getBlockState(p);
                if (s.is(this) && s.getValue(OPEN) != open) {
                    level.setBlock(p, s.setValue(OPEN, open), Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
                }
            }
        }
    }

    private static void playSound(Level level, BlockPos origin, Direction facing, boolean opening) {
        // From the middle of the gate; a heavy wooden door: the wooden creak low, an iron groan under it.
        BlockPos mid = partPos(origin, facing, 2, 1);
        float pitch = 0.62F + level.random.nextFloat() * 0.08F;
        level.playSound(null, mid, opening ? SoundEvents.WOODEN_DOOR_OPEN : SoundEvents.WOODEN_DOOR_CLOSE,
                SoundSource.BLOCKS, 1.0F, pitch);
        level.playSound(null, mid, opening ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE,
                SoundSource.BLOCKS, 0.45F, pitch * 0.85F);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean moving) {
        if (level.isClientSide || block == this) {
            return;
        }
        BlockPos origin = origin(pos, state);
        if (!(level.getBlockEntity(origin) instanceof GateDoorBlockEntity be)) {
            return;
        }
        Direction facing = state.getValue(FACING);
        boolean powered = false;
        for (int col = 0; col < SIZE && !powered; col++) {
            for (int row = 0; row < SIZE && !powered; row++) {
                powered = level.hasNeighborSignal(partPos(origin, facing, col, row));
            }
        }
        if (powered != be.powered()) {
            be.setPowered(powered);
            boolean openOrOpening = level.getBlockState(origin).getValue(OPEN) || be.opening();
            if (powered != openOrOpening) {
                toggle(level, origin, null);
            }
        }
    }

    @Override
    protected boolean triggerEvent(BlockState state, Level level, BlockPos pos, int id, int param) {
        super.triggerEvent(state, level, pos, id, param);
        BlockEntity be = level.getBlockEntity(pos);
        return be != null && be.triggerEvent(id, param);
    }

    // ------------------------------------------------------------------ shape, render

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(FACING).get3DDataValue() - 2];
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(OPEN) ? Shapes.empty() : getShape(state, level, pos, context);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return (type == PathComputationType.LAND || type == PathComputationType.AIR) && state.getValue(OPEN);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isOrigin(state) ? new GateDoorBlockEntity(pos, state) : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != GateDoors.GATE_DOOR_ENTITY.get()) {
            return null;
        }
        BlockEntityTicker<GateDoorBlockEntity> ticker = level.isClientSide
                ? GateDoorBlockEntity::clientTick : GateDoorBlockEntity::serverTick;
        return (BlockEntityTicker<T>) ticker;
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
        // A mirror swaps the viewer's left and right: columns trade sides so the gate still holds together.
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING))).setValue(COL, SIZE - 1 - state.getValue(COL));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, COL, ROW, OPEN);
    }
}
