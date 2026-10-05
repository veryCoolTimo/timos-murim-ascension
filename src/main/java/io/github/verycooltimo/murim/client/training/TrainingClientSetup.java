package io.github.verycooltimo.murim.client.training;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import io.github.verycooltimo.murim.training.TrainingRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Hooks the training props layer onto both player skins (wide and slim arms), like {@code MurimModClient#onAddLayers}.
 * Same subscriber style as the HUD layers ({@code QiHud}).
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/client/event/EntityRenderersEvent.java#AddLayers
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class TrainingClientSetup {

    private TrainingClientSetup() {
    }

    /**
     * How to train, on the two props (no new keys: the hint is the only place that tells the input).
     * API: reference/neoforge-src/net/neoforged/neoforge/event/entity/player/ItemTooltipEvent.java
     */
    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().is(TrainingRegistry.TRAINING_STONE_ITEM.get())) {
            event.getToolTip().add(Component.translatable("murim.training.tooltip.stone").withStyle(ChatFormatting.GRAY));
        } else if (event.getItemStack().is(TrainingRegistry.WEIGHT_SLAB.get())) {
            event.getToolTip().add(Component.translatable("murim.training.tooltip.slab").withStyle(ChatFormatting.GRAY));
            event.getToolTip().add(Component.translatable("murim.training.tooltip.hint").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    @SubscribeEvent
    static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (PlayerSkin.Model skin : event.getSkins()) {
            LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> renderer = event.getSkin(skin);
            if (renderer != null) {
                renderer.addLayer(new TrainingPropLayer(renderer));
            }
        }
    }
}
