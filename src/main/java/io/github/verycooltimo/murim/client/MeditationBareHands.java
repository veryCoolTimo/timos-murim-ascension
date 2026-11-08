package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * While the player sits in meditation his hands are empty and the hotbar is hidden (author 22.10); both return on standing.
 * Only the picture changes: the held stack is taken out of the slot for the draw call of the local player and put back at once.
 * API: reference/neoforge-src/net/neoforged/neoforge/client/event/RenderGuiLayerEvent.java, RenderHandEvent.java, RenderLivingEvent.java
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class MeditationBareHands {
    private static ItemStack mainHeld = ItemStack.EMPTY;
    private static ItemStack offHeld = ItemStack.EMPTY;
    private static boolean swapped;

    private MeditationBareHands() {
    }

    private static boolean sitting() {
        return ClientMeditationState.state().active();
    }

    @SubscribeEvent
    static void onGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (!sitting()) {
            return;
        }
        if (VanillaGuiLayers.HOTBAR.equals(event.getName()) || VanillaGuiLayers.SELECTED_ITEM_NAME.equals(event.getName())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onHand(RenderHandEvent event) {
        if (sitting()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (!sitting() || event.getEntity() != Minecraft.getInstance().player || swapped) {
            return;
        }
        var player = Minecraft.getInstance().player;
        mainHeld = player.getItemBySlot(EquipmentSlot.MAINHAND);
        offHeld = player.getItemBySlot(EquipmentSlot.OFFHAND);
        player.getInventory().items.set(player.getInventory().selected, ItemStack.EMPTY);
        player.getInventory().offhand.set(0, ItemStack.EMPTY);
        swapped = true;
    }

    @SubscribeEvent
    static void onLivingPost(RenderLivingEvent.Post<?, ?> event) {
        if (!swapped) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.getInventory().items.set(player.getInventory().selected, mainHeld);
            player.getInventory().offhand.set(0, offHeld);
        }
        mainHeld = ItemStack.EMPTY;
        offHeld = ItemStack.EMPTY;
        swapped = false;
    }
}
