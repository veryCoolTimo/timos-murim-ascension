package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.HudText;
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
    private static Component text = Component.empty();
    private SectJourneyHud() { }

    public static void accept(SectGoalPayload payload) { text = payload.text(); }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().level == null) text = Component.empty();
    }

    @SubscribeEvent
    static void register(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR,
                ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_goal"), SectJourneyHud::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.player.isSpectator() || mc.screen != null
                || mc.getDebugOverlay().showDebugScreen() || text.equals(Component.empty())) return;
        var lines = mc.font.split(text, Math.min(260, g.guiWidth() - 20));
        int y = 8;
        for (var line : lines) {
            HudText.backdrop(g, 5, y - 2, 11 + mc.font.width(line), y + mc.font.lineHeight + 1, 220);
            g.drawString(mc.font, line, 8, y, 0xFFD8D5C5, true);
            y += mc.font.lineHeight + 2;
        }
    }
}
