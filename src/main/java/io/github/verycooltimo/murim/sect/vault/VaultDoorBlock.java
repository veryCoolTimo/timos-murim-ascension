package io.github.verycooltimo.murim.sect.vault;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * One cell of the arched cold iron door of the main vault. The face is 6 cells wide and 7 high, a round arch of radius 3 on four straight rows (40 cells, {@link VaultPlan#doorCells}):
 * {@code col} 0..5 counted from the left of whoever looks at the front, {@code row} 0..6 from the bottom, {@code facing} where
 * the front looks (into the tunnel). The leaf has three states: {@code closed}; {@code glow} (the carved diagram burns pink
 * while the door wakes); {@code open} (the two leaves have swung inward on their hinges: every cell is empty).
 *
 * <p>From outside, any one performed form of the Twenty-Four Movements opens the door
 * ({@link io.github.verycooltimo.murim.sect.seal.VaultTrial}). From inside, approaching or using it opens it freely.
 * Redstone and tools do nothing; it cannot be broken (sealed, like the cold iron wall around it).
 * API: reference/minecraft-src/net/minecraft/world/level/block/DoorBlock.java (shape turning), BlockBehaviour#tick / scheduleTick.
 */
public class VaultDoorBlock extends HorizontalDirectionalBlock implements net.minecraft.world.level.block.EntityBlock {

    /**
     * The staged opening: the carved diagram lights line by line ({@code GLOW1..GLOW4}: seam and outer rings, inner rings, spiral,
     * thorns), a qi pulse ({@code PULSE}, everything at full, a ring of light runs over the door), the leaves part with weight
     * ({@code OPEN}: dust, a shake, a deep sound, the plates still glowing), then the glow fades ({@code DIM}).
     */
    public enum State implements StringRepresentable {
        CLOSED, GLOW1, GLOW2, GLOW3, GLOW4, PULSE, OPEN, DIM;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final MapCodec<VaultDoorBlock> CODEC = simpleCodec(VaultDoorBlock::new);
    public static final IntegerProperty FORMS = IntegerProperty.create("forms", 0, 4);
    public static final IntegerProperty COL = IntegerProperty.create("col", 0, 5);
    public static final IntegerProperty ROW = IntegerProperty.create("row", 0, 6);
    public static final EnumProperty<State> STATE = EnumProperty.create("state", State.class);

    /** Ticks of each of the four glow stages, of the pulse, and of the glowing open leaves before they fade. */
    public static final int STAGE_TICKS = 12, PULSE_TICKS = 16, FADE_TICKS = 50;
    /** The whole sequence from the first line to the parting leaves (ticks). */
    public static final int OPENING_TICKS = 4 * STAGE_TICKS + PULSE_TICKS;
    /** The door stays open this long, then shuts if nobody stands in it (ticks). */
    public static final int OPEN_TICKS = 240;
    /** Block event: the leaves part (clients shake the camera). */
    public static final int EVENT_PART = 1;

    public VaultDoorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH).setValue(COL, 0).setValue(ROW, 0)
                .setValue(STATE, State.CLOSED).setValue(FORMS, 0));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, COL, ROW, STATE, FORMS);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state;
    }

    private static VoxelShape shape(BlockState state) {
        // The leaves swing inward (client block entity renderer) and are not collidable once they start to part.
        State st = state.getValue(STATE);
        return st == State.OPEN || st == State.DIM ? Shapes.empty() : Shapes.block();
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape(state);
    }

    /** The leaves are drawn by {@link io.github.verycooltimo.murim.client.VaultDoorRenderer} from the controller cell; the cells draw nothing. */
    @Override
    protected net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
        return net.minecraft.world.level.block.RenderShape.INVISIBLE;
    }

    @Override
    public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return controller(state) ? new VaultDoorBlockEntity(pos, state) : null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends net.minecraft.world.level.block.entity.BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
            Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        if (level.isClientSide && type == VaultRegistry.VAULT_DOOR_ENTITY.get()) {
            return (l, p, st, be) -> VaultDoorBlockEntity.clientTick(l, p, st, (VaultDoorBlockEntity) be);
        }
        return null;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer p) exit(level, pos, p);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative()) {
            // Derive the footprint from row/column, so even disconnected or hidden cells are removed.
            Direction right = state.getValue(FACING).getCounterClockWise();
            BlockPos origin = pos.relative(right, -state.getValue(COL)).below(state.getValue(ROW));
            for (int row = 0; row <= 6; row++) for (int col = 0; col <= 5; col++) {
                BlockPos at = origin.relative(right, col).above(row);
                if (!at.equals(pos) && level.getBlockState(at).getBlock() instanceof VaultDoorBlock)
                    level.removeBlock(at, false);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    // ------------------------------------------------------------------ opening and shutting

    /** The cells of the door that {@code pos} belongs to: the same facing, in the plane of the door, connected. */
    public static List<BlockPos> cells(Level level, BlockPos pos) {
        BlockState first = level.getBlockState(pos);
        List<BlockPos> out = new ArrayList<>();
        if (!(first.getBlock() instanceof VaultDoorBlock)) {
            return out;
        }
        Direction facing = first.getValue(FACING);
        out.add(pos);
        for (int i = 0; i < out.size() && out.size() < 40; i++) {
            BlockPos at = out.get(i);
            for (Direction d : Direction.values()) {
                if (d.getAxis() == facing.getAxis()) {
                    continue;
                }
                BlockPos n = at.relative(d);
                BlockState s = level.getBlockState(n);
                if (s.getBlock() instanceof VaultDoorBlock && s.getValue(FACING) == facing && !out.contains(n)) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    /** Wakes the door: the diagram lights fully, a pulse runs over it, the leaves part. Returns the number of cells that moved. */
    public static int wake(Level level, BlockPos near) {
        int n = 0;
        List<BlockPos> cells = cells(level, near);
        for (BlockPos p : cells) {
            BlockState s = level.getBlockState(p);
            if (s.getValue(STATE) == State.CLOSED) {
                level.setBlock(p, s.setValue(STATE, State.GLOW4).setValue(FORMS, 4), Block.UPDATE_ALL);
                level.scheduleTick(p, s.getBlock(), STAGE_TICKS);
                n++;
            }
        }
        if (n > 0 && level instanceof ServerLevel sl) {
            BlockPos mid = cells.get(cells.size() / 2);
            sl.playSound(null, mid, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.6F, 0.5F);
            sl.playSound(null, mid, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.8F, 0.5F);
        }
        return n;
    }

    /** The trial already lit the seal and waited eight ticks after a successful finish. */
    public static void openCompleted(Level level, BlockPos pos) {
        for (BlockPos cell : cells(level, pos)) {
            BlockState state = level.getBlockState(cell);
            if (state.getValue(STATE) != State.CLOSED) continue;
            level.setBlock(cell, state.setValue(STATE, State.PULSE).setValue(FORMS, 4), Block.UPDATE_ALL);
            // Reuse the existing opening sounds, block event and closing schedule, without another glow delay.
            if (level instanceof ServerLevel server) ((VaultDoorBlock) state.getBlock()).tick(
                    level.getBlockState(cell), server, cell, server.random);
        }
    }

    /** Free egress, using the same facing convention as VaultTrial.beforeDoor. No flags are involved. */
    static void exit(Level level, BlockPos pos, ServerPlayer player) {
        BlockState state = level.getBlockState(pos);
        if (state.getValue(STATE) == State.DIM || state.getValue(STATE) == State.OPEN) return;
        Direction front = state.getValue(FACING);
        double side = (player.getX() - pos.getX() - 0.5D) * front.getStepX()
                + (player.getZ() - pos.getZ() - 0.5D) * front.getStepZ();
        if (side >= 0 || player.isSpectator()) return;
        for (BlockPos cell : cells(level, pos)) {
            BlockState s = level.getBlockState(cell);
            if (s.getValue(STATE) != State.DIM && s.getValue(STATE) != State.OPEN) {
                level.setBlock(cell, s.setValue(STATE, State.DIM), Block.UPDATE_ALL);
                level.scheduleTick(cell, s.getBlock(), 60);
            }
        }
    }

    static boolean controller(BlockState s) {
        return s.getValue(COL) == 0 && s.getValue(ROW) == 0;
    }

    /** The pulse: a few white sparks in the middle of the door (the lines themselves carry the light now). */
    private static void pulse(ServerLevel level, List<BlockPos> cells) {
        double cx = 0, cy = 0, cz = 0;
        for (BlockPos p : cells) {
            cx += p.getX() + 0.5D;
            cy += p.getY() + 0.5D;
            cz += p.getZ() + 0.5D;
        }
        cx /= cells.size();
        cy /= cells.size();
        cz /= cells.size();
        Direction front = level.getBlockState(cells.get(0)).getValue(FACING);
    }

    /** The leaves part: smoke comes from the client block entity, nothing is thrown here. */
    private static void dust(ServerLevel level, List<BlockPos> cells, BlockState iron) {
    }

    /** True if the door is open or opening. */
    public static boolean isOpen(Level level, BlockPos near) {
        for (BlockPos p : cells(level, near)) {
            if (level.getBlockState(p).getValue(STATE) != State.CLOSED) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean triggerEvent(BlockState state, Level level, BlockPos pos, int id, int param) {
        if (id == EVENT_PART && level.isClientSide) {
            io.github.verycooltimo.murim.client.VaultDoorClient.shake(pos);
            return true;
        }
        return id == EVENT_PART;
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        State st = state.getValue(STATE);
        switch (st) {
            case GLOW1, GLOW2, GLOW3, GLOW4 -> {
                State next = st == State.GLOW1 ? State.GLOW2 : st == State.GLOW2 ? State.GLOW3 : st == State.GLOW3 ? State.GLOW4 : State.PULSE;
                level.setBlock(pos, state.setValue(STATE, next), Block.UPDATE_ALL);
                level.scheduleTick(pos, this, next == State.PULSE ? PULSE_TICKS : STAGE_TICKS);
                if (controller(state)) {
                    float pitch = 0.5F + 0.15F * (st.ordinal());
                    level.playSound(null, pos, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 1.2F, pitch + 0.3F);
                    if (next == State.PULSE) {
                        pulse(level, cells(level, pos));
                        level.playSound(null, pos, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.6F, 0.6F);
                        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 2.0F, 0.5F);
                    }
                }
            }
            case PULSE -> {
                level.setBlock(pos, state.setValue(STATE, State.OPEN), Block.UPDATE_ALL);
                level.scheduleTick(pos, this, FADE_TICKS);
                if (controller(state)) {
                    level.playSound(null, pos, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 1.0F, 0.35F);
                    level.playSound(null, pos, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 1.6F, 0.35F);
                    level.playSound(null, pos, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 1.2F, 0.4F);
                    level.playSound(null, pos, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.4F, 0.4F);
                    level.blockEvent(pos, this, EVENT_PART, 0);
                    dust(level, cells(level, pos), VaultRegistry.VAULT_IRON.get().defaultBlockState());
                }
            }
            case OPEN -> {
                level.setBlock(pos, state.setValue(STATE, State.DIM), Block.UPDATE_ALL);
                level.scheduleTick(pos, this, OPEN_TICKS);
            }
            case DIM -> {
                // Keep the whole leaf open while occupied: independent cell checks can close its upper half onto a player.
                List<BlockPos> door = cells(level, pos);
                for (ServerPlayer p : level.players()) {
                    if (p.isSpectator()) continue;
                    for (BlockPos cell : door) {
                        if (p.distanceToSqr(cell.getX() + 0.5D, cell.getY() + 0.5D, cell.getZ() + 0.5D) <= 12.0D) {
                            level.scheduleTick(pos, this, 40);
                            return;
                        }
                    }
                }
                for (BlockPos cell : door) {
                    BlockState s = level.getBlockState(cell);
                    level.setBlock(cell, s.setValue(STATE, State.CLOSED), Block.UPDATE_ALL);
                }
                level.playSound(null, pos, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.0F, 0.5F);
            }
            default -> { }
        }
    }
}
