package io.github.verycooltimo.murim.library;

import com.mojang.serialization.MapCodec;
import io.github.verycooltimo.murim.decor.LibraryBookcaseBlock;
import io.github.verycooltimo.murim.decor.ScrollShelfBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The shelf that stands on a book (Absolute Regression: «that precious manual was used to prop up the 19th
 * bookshelf», docs/design/reference/library/heavenly-demon-archive/hda-04.png). The lowest block of one shelf column
 * on the archive's bottom tier: only the full-size lower frame leans three degrees, meeting the ordinary upright upper half.
 * One front foot rests on the thin old book; taking the manual leaves a plain book under it.
 *
 * <p>Right-click pulls the book out: the genuine find of the archive is rolled from
 * {@code murim:chests/ruined_library/propped} (always one real manual). The decorative tome remains under the frame.
 * Breaking it drops the same manual only while {@code has_book} is true.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/state/BlockBehaviour.java#useWithoutItem,
 * reference/minecraft-src/net/minecraft/server/ReloadableServerRegistries.java#getLootTable,
 * reference/minecraft-src/net/minecraft/world/level/storage/loot/LootTable.java#getRandomItems(LootParams).
 */
public class ProppedShelfBlock extends ScrollShelfBlock {

    public static final MapCodec<ProppedShelfBlock> CODEC = simpleCodec(ProppedShelfBlock::new);

    public static final BooleanProperty HAS_BOOK =
            LibraryBookcaseBlock.HAS_BOOK;
    public static final IntegerProperty VARIANT =
            LibraryBookcaseBlock.VARIANT;

    public ProppedShelfBlock(Properties properties) {
        super(properties);
        // Old saves without half decode as lower; older saves without occupancy keep their unclaimed secret.
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)
                .setValue(HALF, DoubleBlockHalf.LOWER).setValue(HAS_BOOK, true).setValue(VARIANT, 0));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(HAS_BOOK, VARIANT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : state.setValue(HAS_BOOK, false)
                .setValue(VARIANT, LibraryBookcaseBlock.variantAt(context.getClickedPos()));
    }

    /** Old one-cell saves decode with half=lower. Occupied space above must never be overwritten. */
    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour,
                                     LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER && dir == Direction.UP) {
            // Missing upper is a supported legacy single, including when the cell above is occupied.
            return state;
        }
        return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    /** Explicit partner removal lets a legacy lower survive updates, without leaving modern lower orphans. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock())) {
            BlockPos partner = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState other = level.getBlockState(partner);
            if (other.is(this) && other.getValue(HALF) != state.getValue(HALF)
                    && other.getValue(FACING) == state.getValue(FACING)) {
                // Clear the partner before spawning its lower-only loot. The removed half cannot recurse.
                level.setBlock(partner, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                if (!level.isClientSide && other.getValue(HALF) == DoubleBlockHalf.LOWER) {
                    Block.dropResources(other, level, partner);
                }
            }
        }
        super.onRemove(state, level, pos, next, moving);
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
    protected VoxelShape getShape(BlockState state,
            BlockGetter level, BlockPos pos, CollisionContext context) {
        return super.getShape(state, level, pos, context);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER || !level.getBlockState(pos).is(this) || !level.getBlockState(pos).getValue(HAS_BOOK)) {
            return InteractionResult.PASS;
        }
        if (!(level instanceof ServerLevel server)) {
            return InteractionResult.SUCCESS;
        }
        pull(server, pos, level.getBlockState(pos));
        player.displayClientMessage(Component.translatable("junk.murim.msg.propped").withStyle(ChatFormatting.GRAY), true);
        return InteractionResult.CONSUME;
    }

    /** Pull the hidden manual once; the separate decorative support remains in both models. */
    public static void pull(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState current = level.getBlockState(pos);
        if (!current.is(ModLibrary.PROPPED_SHELF.get()) || current.getValue(HALF) != DoubleBlockHalf.LOWER || !current.getValue(HAS_BOOK)) return;
        // Occupancy is the persistent one-shot latch, including after save/load.
        level.setBlock(pos, current.setValue(HAS_BOOK, false), Block.UPDATE_ALL);
        LootTable table = level.getServer().reloadableRegistries().getLootTable(ModLibrary.PROPPED_LOOT);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .create(LootContextParamSets.CHEST);
        Vec3 front = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(current.getValue(FACING).getNormal()).scale(0.7D));
        for (ItemStack stack : table.getRandomItems(params)) {
            Block.popResource(level, BlockPos.containing(front), stack);
        }
        level.playSound(null, pos, SoundEvents.WOOD_FALL, SoundSource.BLOCKS, 1.2F, 0.6F);
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1.0F, 0.8F);
    }
}
