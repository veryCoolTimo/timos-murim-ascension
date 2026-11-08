package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.SectTabletData;
import io.github.verycooltimo.murim.network.SectTabletPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client snapshot cache, cleared at disconnect. No gameplay is inferred from inventory on the client.
 * API: reference/neoforge-src/net/neoforged/neoforge/client/event/{ClientTickEvent,ClientPlayerNetworkEvent,RegisterGuiLayersEvent}.java;
 * reference/minecraft-src/net/minecraft/client/gui/GuiGraphics.java#renderItem, Font.java#split;
 * core/Registry.java#get, world/item/Item.java#getDefaultInstance. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ContractTracker {
    private static SectTabletData snapshot;
    private static int ticks;
    private static int updatedAt;
    private ContractTracker() { }
    public static void accept(SectTabletData data) { snapshot = data; updatedAt = ticks; }
    public static void clearForCapture() { if (io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.galleryActive()) snapshot = null; }
    @SubscribeEvent static void logout(ClientPlayerNetworkEvent.LoggingOut event) { snapshot = null; ticks = 0; }
    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        // Also discovers a contract accepted through dialogue, without ever opening a tablet.
        if (mc.level != null && mc.player != null && mc.screen == null) {
            ticks++;
            if (ticks - updatedAt > 80 && !io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.galleryActive()) snapshot = null; // A former member stops receiving replies; never leave a stale contract on screen.
        }
        if (mc.level != null && mc.player != null && mc.screen == null && ticks % 40 == 0
                && !io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.galleryActive()) {
            PacketDistributor.sendToServer(new SectTabletPayload.Act("track"));
        }
    }
    @SubscribeEvent static void register(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "contract_tracker"), ContractTracker::render);
    }
    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (snapshot == null || snapshot.active() == null || mc.player == null || mc.screen != null || mc.options.hideGui
                || mc.player.isSpectator() || mc.getDebugOverlay().showDebugScreen()) return;
        TabletLayout.Rect r = TabletLayout.tracker(g.guiWidth(), g.guiHeight());
        int edge = TabletLayout.frameEdge(r, mc.getWindow().getGuiScale());
        int w = r.width() - 2 * (edge + 3);
        Component title = snapshot.offers().stream().filter(o -> o.slot() == snapshot.active().slot()).map(SectTabletData.Offer::title)
                .findFirst().orElse(snapshot.active().status());
        Component giver = Component.translatable("murim.tablet.board.hand_in_at", snapshot.contracts().giver());
        int needed = edge + 6 + Math.max(16, mc.font.split(title, w - 20).size() * 10) + mc.font.split(giver, w).size() * 10;
        for (SectTabletData.Counter c : snapshot.active().counters()) needed += Math.max(16, mc.font.split(counterText(c), w - 20).size() * 10);
        if (snapshot.active().counters().isEmpty()) needed += mc.font.split(snapshot.active().status(), w).size() * 10;
        // Accessibility fallback on small GUI canvases: expand only this card vertically rather than reduce the font.
        int cardHeight = Math.max(r.height(), (int) Math.ceil(needed / .82));
        var card = new TabletLayout.Rect(r.x(), r.y(), r.width(), cardHeight);
        int crown = TabletLayout.frameCrown(card, mc.getWindow().getGuiScale());
        cardHeight = Math.max(cardHeight, needed + crown);
        int bottom = r.y() + cardHeight;
        int px = r.x() + edge + 3, yy = r.y() + crown + 3;
        // Reuse the tablet's corner/edge art at the card's smaller dimensions.
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/gui/GuiGraphics.java#blitSprite.
        g.blitSprite(ResourceLocation.fromNamespaceAndPath("murim", "tablet/frame"), r.x(), r.y(), r.width(), bottom - r.y());
        g.fill(r.x() + edge, r.y() + crown, r.right() - edge, bottom - edge, 0xFFE3EDE2);
        String icon = snapshot.offers().stream().filter(o -> o.slot() == snapshot.active().slot())
                .map(SectTabletData.Offer::icon).findFirst().orElse("icon_scroll");
        g.blitSprite(ResourceLocation.fromNamespaceAndPath("murim", "tablet/" + icon), px, yy, 16, 16);
        yy = Math.max(yy + 16, text(g, mc.font, title, px + 20, yy, w - 20));
        if (snapshot.active().counters().isEmpty()) yy = text(g, mc.font, snapshot.active().status(), px, yy, w);
        else for (SectTabletData.Counter c : snapshot.active().counters()) yy = counter(g, mc.font, c, px, yy, w);
        text(g, mc.font, giver, px, yy, w);
    }
    static Component counterText(SectTabletData.Counter c) {
        return Component.translatable("murim.tablet.counter", BuiltInRegistries.ITEM.get(ResourceLocation.parse(c.item())).getDescription(), c.have(), c.need());
    }
    static int counter(GuiGraphics g, Font font, SectTabletData.Counter c, int x, int y, int width) {
        g.renderItem(BuiltInRegistries.ITEM.get(ResourceLocation.parse(c.item())).getDefaultInstance(), x, y);
        return Math.max(y + 16, text(g, font, counterText(c), x + 20, y + 3, width - 20));
    }
    private static int text(GuiGraphics g, Font font, Component c, int x, int y, int width) {
        for (var line : font.split(c, Math.max(1, width))) { g.drawString(font, line, x, y, 0xFF202820, false); y += 10; }
        return y;
    }
}
