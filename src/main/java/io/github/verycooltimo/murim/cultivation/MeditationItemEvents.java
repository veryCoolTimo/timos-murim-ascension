package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Server authority: no items can be used while seated. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class MeditationItemEvents {
    private MeditationItemEvents() {
    }

    private static boolean blocked(Player player, ItemStack stack) {
        if (player instanceof ServerPlayer server && server.getData(ModAttachments.MEDITATION).active()) {
            MeditationService.blockedAction(server);
            return true;
        }
        return false;
    }

    // API: NeoForge 1.21.1 PlayerInteractEvent concrete subclasses, as in PenanceEnforcement.
    @SubscribeEvent
    static void rightClick(PlayerInteractEvent.RightClickItem event) {
        if (blocked(event.getEntity(), event.getItemStack())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void block(PlayerInteractEvent.RightClickBlock event) {
        if (blocked(event.getEntity(), event.getItemStack())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void entity(PlayerInteractEvent.EntityInteract event) {
        if (blocked(event.getEntity(), event.getItemStack())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void entitySpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (blocked(event.getEntity(), event.getItemStack())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    // API: NeoForge 1.21.1 LivingEntityUseItemEvent.Start cancels startUsingItem, before any consumption.
    @SubscribeEvent
    static void startUse(LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof Player player && blocked(player, event.getItem())) {
            event.setCanceled(true);
        }
    }
}
