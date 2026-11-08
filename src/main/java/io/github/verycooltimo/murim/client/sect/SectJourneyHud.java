package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.SectGoalPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** Uses the existing HUD caption style; the same invitation is available in dialogue and the sect log. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class SectJourneyHud {
    /** The reminder is shown for about a minute and a half after a trigger (goal change, joining, a new period, nearing the person, closing the tablet), then hidden. */
    private static final int SHOW_TICKS = 2000;
    private static final int FADE_TICKS = 60;
    private static Component text = Component.empty();
    private static int age;
    private SectJourneyHud() { }

    public static void accept(SectGoalPayload payload) {
        if (!payload.text().equals(text)) age = 0;
        text = payload.text();
    }

    /** Show the reminder again for its minute and a half. */
    public static void pulse() {
        age = 0;
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().level == null) text = Component.empty();
        else {
            Minecraft mc = Minecraft.getInstance();
            // UX #11: charge only time during which the reminder can actually be seen.
            if (mc.player != null && !mc.isPaused() && mc.screen == null && !mc.options.hideGui
                    && !mc.player.isSpectator() && !mc.getDebugOverlay().showDebugScreen()
                    && mc.player.tickCount - mc.player.getLastHurtByMobTimestamp() >= 200
                    && mc.player.tickCount - mc.player.getLastHurtMobTimestamp() >= 100
                    && age < Integer.MAX_VALUE) age++;
        }
    }

    @SubscribeEvent
    static void register(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR,
                ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_goal"), SectJourneyHud::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        // Author decision, 6 Nov: guidance stays in the tablet and jade mote, without corner text.
    }
}
