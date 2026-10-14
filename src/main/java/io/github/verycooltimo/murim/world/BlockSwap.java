package io.github.verycooltimo.murim.world;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Builder convenience (author 14.10): in creative, Shift + right-click on a block while holding another block swaps it in
 * place — no breaking. Shared state properties (facing, axis, slab type, stairs half…) carry over. Skips two-block-tall
 * blocks (doors, tall shelves) and blocks with contents (chests, shelves with books) so nothing is half-replaced or lost.
 * No new keybind: Shift + right-click, creative only.
 */
// API: reference/neoforge-src/net/neoforged/neoforge/event/entity/player/PlayerInteractEvent.java#RightClickBlock
@EventBusSubscriber(modid = MurimMod.MODID)
public final class BlockSwap {
    private BlockSwap() {
    }

    @SubscribeEvent
    static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!player.isCreative() || !player.isShiftKeyDown()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof BlockItem item)) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState old = level.getBlockState(pos);
        Block block = item.getBlock();
        if (old.isAir() || old.is(block) || old.hasBlockEntity() || tall(old) || tall(block.defaultBlockState())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide()));
        if (level.isClientSide()) {
            return;
        }
        BlockHitResult hit = event.getHitVec();
        BlockState placed = block.getStateForPlacement(new BlockPlaceContext(player, event.getHand(), stack, hit));
        BlockState state = carry(old, placed == null ? block.defaultBlockState() : placed);
        level.setBlock(pos, state, Block.UPDATE_ALL);
        SoundType sound = state.getSoundType(level, pos, player);
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F,
                sound.getPitch() * 0.8F);
    }

    private static boolean tall(BlockState state) {
        return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
    }

    /** Copy every property the two blocks share (same name and an allowed value), e.g. facing or stairs half. */
    private static BlockState carry(BlockState from, BlockState to) {
        for (Property<?> property : to.getProperties()) {
            for (Property<?> source : from.getProperties()) {
                if (source.getName().equals(property.getName())) {
                    to = copy(from, to, source, property);
                }
            }
        }
        return to;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState from, BlockState to, Property<?> source,
                                                              Property<T> target) {
        String value = valueName(from, source);
        return target.getValue(value).map(v -> to.setValue(target, v)).orElse(to);
    }

    private static <S extends Comparable<S>> String valueName(BlockState state, Property<S> property) {
        return property.getName(state.getValue(property));
    }
}
