package io.github.verycooltimo.murim.sect.vault;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Ancestors' Hall floor over the way down: a sealed copy of the hall's floor tiles (nobody breaks through it from the
 * hall). The cells with {@code hatch} slide away when the founders' rite is done ({@code open}: no collision, no model) and
 * come back by themselves after {@link #OPEN_TICKS} unless somebody stands in the opening.
 */
public class VaultFloorBlock extends Block {

    public static final MapCodec<VaultFloorBlock> CODEC = simpleCodec(VaultFloorBlock::new);
    public static final BooleanProperty HATCH = BooleanProperty.create("hatch");
    public static final BooleanProperty OPEN = BooleanProperty.create("open");
    public static final int OPEN_TICKS = 400;

    public VaultFloorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HATCH, false).setValue(OPEN, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HATCH, OPEN);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(OPEN) ? Shapes.empty() : Shapes.block();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(OPEN) ? Shapes.empty() : Shapes.block();
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getValue(OPEN);
    }

    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, net.minecraft.world.level.Level level,
            BlockPos pos, net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hit) {
        if (player instanceof ServerPlayer p) exit((ServerLevel) level, pos, p);
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Lift the whole hatch from below so the return route is visible, including beside the top stair. */
    static void exit(ServerLevel level, BlockPos pos, ServerPlayer player) {
        BlockState hatch = level.getBlockState(pos);
        if (player.isSpectator() || player.getY() >= pos.getY() || !hatch.getValue(HATCH) || hatch.getValue(OPEN)) return;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            BlockPos at = pos.offset(dx, 0, dz);
            BlockState s = level.getBlockState(at);
            if (s.getBlock() instanceof VaultFloorBlock && s.getValue(HATCH) && !s.getValue(OPEN)) {
                level.setBlock(at, s.setValue(OPEN, true), Block.UPDATE_ALL);
                level.scheduleTick(at, s.getBlock(), 60);
            }
        }
    }

    /** Opens one hatch cell (the shut tick is scheduled by the caller). */
    public static void open(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.getBlock() instanceof VaultFloorBlock && s.getValue(HATCH) && !s.getValue(OPEN)) {
            level.setBlock(pos, s.setValue(OPEN, true), Block.UPDATE_ALL);
            level.scheduleTick(pos, s.getBlock(), OPEN_TICKS);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(OPEN)) {
            return;
        }
        // Treat the 4x4 hatch as one opening, as VaultDoorBlock does for a door leaf.
        // Per-cell occupancy could close the far side while somebody was still ascending,
        // and made protection depend on which rotated edge their body overlapped.
        int minX = pos.getX(), maxX = pos.getX(), minZ = pos.getZ(), maxZ = pos.getZ();
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            BlockPos at = pos.offset(dx, 0, dz);
            BlockState cell = level.getBlockState(at);
            if (cell.getBlock() instanceof VaultFloorBlock && cell.getValue(HATCH)) {
                minX = Math.min(minX, at.getX()); maxX = Math.max(maxX, at.getX());
                minZ = Math.min(minZ, at.getZ()); maxZ = Math.max(maxZ, at.getZ());
            }
        }
        AABB around = new AABB(minX, pos.getY(), minZ, maxX + 1, pos.getY() + 1, maxZ + 1)
                .inflate(0.8D, 2.5D, 0.8D);
        for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, around)) {
            if (!p.isSpectator()) {
                level.scheduleTick(pos, this, 60);
                return;
            }
        }
        level.setBlock(pos, state.setValue(OPEN, false), Block.UPDATE_ALL);
        if (pos.getX() % 4 == 0) {
            level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.6F, 0.6F);
        }
    }
}
