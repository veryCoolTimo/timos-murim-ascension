package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Bronze sect bell hanging under a beam (Mount Hua bell tower, docs/design/23-mount-hua-sect.md `bell`). Rings when
 * right-clicked, hit, struck by a projectile or powered (rising edge): a deep bell sound (murim:sect_bell, ElevenLabs,
 * art/sounds-src/sect_bell_*.mp3) and a swing of the body away from the striker.
 *
 * <p>The wooden beam and bronze pin are the chunk model; the bell body is drawn by client/decor/HallFurnitureClient's block entity
 * renderer so it can swing. The swing travels as a block event (id 1, param = 2D direction), the vanilla bell's way.
 * Hangs only from a block above (a sturdy centre or any collision on that face, like {@link HangingLanternBlock}).
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/BellBlock.java (#onProjectileHit, #neighborChanged,
 * #attemptToRing), BaseEntityBlock.java (#getRenderShape, #triggerEvent, #createTickerHelper), BlockBehaviour.java#attack.
 */
public class SectBellBlock extends BaseEntityBlock {

    public static final MapCodec<SectBellBlock> CODEC = simpleCodec(SectBellBlock::new);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    public static final int EVENT_RING = 1;
    private static final VoxelShape SHAPE = Block.box(0.5, 0, 0.5, 15.5, 16, 15.5);

    public SectBellBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(POWERED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SectBellBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? createTickerHelper(type, HallFurniture.SECT_BELL_ENTITY.get(), SectBellBlockEntity::clientTick) : null;
    }

    /**
     * Server: rings the bell at {@code pos}, the body swinging towards {@code towards}. Returns false while it is still
     * ringing from a strike a moment ago (no machine-gun bell).
     */
    public static boolean ring(Level level, BlockPos pos, Direction towards, @Nullable Player who, boolean sound) {
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof SectBellBlockEntity be)) {
            return false;
        }
        long now = level.getGameTime();
        if (!be.mayRing(now)) {
            return false;
        }
        be.rang(now);
        if (sound) {
            level.playSound(null, pos, HallFurniture.SECT_BELL_SOUND.get(), SoundSource.BLOCKS, 3.0F, 0.95F + level.random.nextFloat() * 0.1F);
        }
        level.blockEvent(pos, level.getBlockState(pos).getBlock(), EVENT_RING, towards.get2DDataValue());
        level.gameEvent(who, GameEvent.BLOCK_CHANGE, pos);
        return true;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            ring(level, pos, player.getDirection(), player, true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
        ring(level, pos, player.getDirection(), player, true);
    }

    @Override
    protected void onProjectileHit(Level level, BlockState state, BlockHitResult hit, Projectile projectile) {
        Direction face = hit.getDirection().getAxis().isHorizontal() ? hit.getDirection().getOpposite() : projectile.getDirection();
        ring(level, hit.getBlockPos(), face, projectile.getOwner() instanceof Player p ? p : null, true);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbour, BlockPos from, boolean moving) {
        boolean power = level.hasNeighborSignal(pos);
        if (power != state.getValue(POWERED)) {
            if (power) {
                ring(level, pos, Direction.NORTH, null, true);
            }
            level.setBlock(pos, state.setValue(POWERED, power), Block.UPDATE_ALL);
        }
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos up = pos.above();
        return Block.canSupportCenter(level, up, Direction.DOWN) || !level.getBlockState(up).getCollisionShape(level, up).isEmpty();
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState s = defaultBlockState();
        return s.canSurvive(context.getLevel(), context.getClickedPos()) ? s : null;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        if (dir == Direction.UP && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }
}
