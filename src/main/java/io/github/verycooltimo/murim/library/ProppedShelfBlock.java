package io.github.verycooltimo.murim.library;

import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The shelf that stands on a book (Absolute Regression: «that precious manual was used to prop up the 19th
 * bookshelf», docs/design/reference/library/heavenly-demon-archive/hda-04.png). The lowest block of one shelf column
 * on the archive's bottom tier: the shelf body sits two pixels up, a battered manual is wedged under its front.
 *
 * <p>Right-click pulls the book out: the genuine find of the archive is rolled from
 * {@code murim:chests/ruined_library/propped} (always one real manual), the block becomes an ordinary chiseled
 * shelf and settles with a thud. Breaking it drops the same (block loot table points at the same table).
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/state/BlockBehaviour.java#useWithoutItem,
 * reference/minecraft-src/net/minecraft/server/ReloadableServerRegistries.java#getLootTable,
 * reference/minecraft-src/net/minecraft/world/level/storage/loot/LootTable.java#getRandomItems(LootParams).
 */
public class ProppedShelfBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<ProppedShelfBlock> CODEC = simpleCodec(ProppedShelfBlock::new);

    public ProppedShelfBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level instanceof ServerLevel server)) {
            return InteractionResult.SUCCESS;
        }
        pull(server, pos, state);
        player.displayClientMessage(Component.translatable("junk.murim.msg.propped").withStyle(ChatFormatting.GRAY), true);
        return InteractionResult.CONSUME;
    }

    /** Pull the manual: drop the genuine find, leave an ordinary shelf facing the same way, thud and dust. */
    public static void pull(ServerLevel level, BlockPos pos, BlockState state) {
        LootTable table = level.getServer().reloadableRegistries().getLootTable(ModLibrary.PROPPED_LOOT);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .create(LootContextParamSets.CHEST);
        Vec3 front = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(state.getValue(FACING).getNormal()).scale(0.7D));
        for (ItemStack stack : table.getRandomItems(params)) {
            Block.popResource(level, BlockPos.containing(front), stack);
        }
        BlockState shelf = Blocks.CHISELED_BOOKSHELF.defaultBlockState()
                .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, state.getValue(FACING));
        level.setBlock(pos, shelf, Block.UPDATE_ALL);
        // 2001 = block-break dust and sound (LevelEvent.PARTICLES_DESTROY_BLOCK): the shelf column settles.
        level.levelEvent(2001, pos.above(), Block.getId(Blocks.CHISELED_BOOKSHELF.defaultBlockState()));
        level.playSound(null, pos, SoundEvents.WOOD_FALL, SoundSource.BLOCKS, 1.2F, 0.6F);
        level.playSound(null, pos, SoundEvents.CHISELED_BOOKSHELF_PICKUP, SoundSource.BLOCKS, 1.0F, 0.8F);
    }
}
