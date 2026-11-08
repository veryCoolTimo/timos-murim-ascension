package io.github.verycooltimo.murim.sect.vault;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Finite treasure, stored in block states so saves, clients and simultaneous clicks agree. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class VaultHarvest {
    private VaultHarvest() { }

    static InteractionResult use(Level level, BlockPos pos, Player player) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        return take(level, pos, player) ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    private static void give(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }

    /** Reads the current state, never a stale click packet's state. No creative duplication. */
    static boolean take(Level level, BlockPos pos, Player player) {
        if (level.isClientSide || player.isSpectator()) return false;
        var state = level.getBlockState(pos);
        if (state.getBlock() instanceof VaultTreasureBlock) {
            int mask = VaultTreasureLayout.LOOT[state.getValue(VaultTreasureBlock.KIND)];
            if (mask == 0) return false; // cloth alone is furnishing
            int layers = state.getValue(VaultTreasureBlock.LAYERS);
            level.setBlock(pos, layers == 1 ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                    : state.setValue(VaultTreasureBlock.LAYERS, layers - 1), 3);
            if ((mask & 1) != 0) give(player, new ItemStack(Items.GOLD_NUGGET, 3));
            if ((mask & 2) != 0) give(player, new ItemStack(Items.GOLD_INGOT));
            if (layers == 1) {
                if ((mask & 4) != 0) give(player, new ItemStack(Items.EMERALD));
                if ((mask & 8) != 0) give(player, new ItemStack(Items.DIAMOND));
                if ((mask & 16) != 0) give(player, new ItemStack(Items.AMETHYST_SHARD));
            }
            return true;
        }
        if (state.getBlock() instanceof VaultDecorBlock) {
            int kind = state.getValue(VaultDecorBlock.KIND);
            if (state.getValue(VaultDecorBlock.TAKEN) || kind == VaultDecorBlock.PLAQUE
                    || kind == VaultDecorBlock.BANNER_RED || kind == VaultDecorBlock.BANNER_MAROON) return false;
            // The founders' sword is seated in its socket: both hands must be free to lift it.
            if (kind == VaultDecorBlock.HERO && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty())) return false;
            if (kind >= VaultDecorBlock.LEAN_0 && kind < VaultDecorBlock.BANNER_RED) level.removeBlock(pos, false);
            else level.setBlock(pos, state.setValue(VaultDecorBlock.TAKEN, true), 3);
            int count = kind <= VaultDecorBlock.RACK_B ? 3 : 1;
            for (int i = 0; i < count; i++) {
                ItemStack sword = new ItemStack(kind == VaultDecorBlock.HERO ? ModItems.PLUM_GUARD_SWORD.get() : ModItems.HUASHAN_SWORD.get());
                sword.set(DataComponents.CUSTOM_NAME, Component.translatable(kind == VaultDecorBlock.HERO
                        ? "item.murim.vault_founders_sword" : "item.murim.vault_elders_sword"));
                give(player, sword);
            }
            level.playSound(null, pos, SoundEvents.CHAIN_HIT, SoundSource.BLOCKS, 0.7F, 0.6F);
            return true;
        }
        if (state.getBlock() instanceof VaultPropBlock && state.getValue(VaultPropBlock.KIND) == 5) {
            level.removeBlock(pos, false);
            give(player, new ItemStack(ModItems.HUASHAN_SWORD.get()));
            return true;
        }
        return false;
    }

    /** A survival mining stroke takes one layer, using the same transaction as a click.
     * NeoForge 1.21.1 BlockEvent.BreakEvent runs on the server before destruction.
     */
    @SubscribeEvent
    static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer().isCreative()) return;
        var block = event.getState().getBlock();
        if (block instanceof VaultTreasureBlock || block instanceof VaultDecorBlock || block instanceof VaultPropBlock) {
            if (event.getLevel() instanceof Level level) {
                boolean took = take(level, event.getPos(), event.getPlayer());
                if (took || block instanceof VaultTreasureBlock
                        || block instanceof VaultDecorBlock && event.getState().getValue(VaultDecorBlock.KIND) == VaultDecorBlock.HERO)
                    event.setCanceled(true);
            }
        }
    }
}
