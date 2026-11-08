package io.github.verycooltimo.murim.client.flow;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientConfig;
import io.github.verycooltimo.murim.client.HudText;
import io.github.verycooltimo.murim.cultivation.flow.FlowGame;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** Only the hands, pressure and dissolve clock remain on screen; the route is FlowBodyRenderer.
 * API: PillHud, MeditationBareHands and the former FlowOverlay GUI layer.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class FlowOverlay {
    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "pill_flow");

    private FlowOverlay() { }

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.CROSSHAIR, LAYER, FlowOverlay::render);
    }

    @SubscribeEvent
    static void onVanillaLayer(RenderGuiLayerEvent.Pre event) {
        if (!FlowClient.active()) return;
        // Vanilla identifiers already used by the project's GUI layers; survival layers share this namespace.
        String path = event.getName().getPath();
        if (event.getName().getNamespace().equals(MurimMod.MODID)
                && (path.equals("qi") || path.equals("technique_slots") || path.equals("meditation") || path.equals("pills"))) {
            event.setCanceled(true);
            return;
        }
        if (event.getName().getNamespace().equals("minecraft") && switch (path) {
            case "hotbar", "crosshair" -> true;
            default -> false;
        }) event.setCanceled(true);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        FlowGame game = FlowClient.game();
        Minecraft mc = Minecraft.getInstance();
        if (game == null || mc.player == null || mc.options.hideGui) return;
        float visibility = FlowClient.visibility();
        FlowFocusMask.render(g, visibility);
        int alpha = Math.round(255 * visibility);
        int w = g.guiWidth(), h = g.guiHeight(), cx = w / 2;
        if (game.state == FlowGame.State.TORN || game.state == FlowGame.State.BURST) {
            // One soft red tint; the flash accessibility switch also limits the torn feedback.
            int a = (int) ((ClientConfig.SCREEN_FLASHES.get() ? 40 : 14) * visibility * Math.max(0, 1 - game.end / 1.4D));
            g.fill(0, 0, w, h, (a << 24) | 0xA51D31);
        }
        int y = h - 24;
        g.fill(cx - 40, y, cx + 40, y + 2, (alpha / 4 << 24) | 0x594958);
        g.fill(cx - 40, y, cx - 40 + (int) Math.round(80 * game.pressure / 100), y + 2, (alpha << 24) | 0xE2485A);
        double remaining = Math.max(0, 1 - game.t / (FlowClient.automatic() ? 2.5D : game.time));
        for (int i = 0; i < 48; i++) {
            double angle = i * Math.PI / 24 - Math.PI / 2;
            int x = w - 17 + (int) Math.round(Math.cos(angle) * 7);
            int ry = 17 + (int) Math.round(Math.sin(angle) * 7);
            int a = i / 48D < remaining ? alpha : alpha / 6;
            g.fill(x, ry, x + 1, ry + 1, (a << 24) | game.pill.colour());
        }
        Component hint;
        if (game.state == FlowGame.State.RUN) {
            boolean fork = game.forks.stream().anyMatch(f -> !f.done && f.s > game.s && f.s - game.s < FlowGame.FORK_ZONE);
            hint = FlowClient.automatic() ? Component.translatable("murim.pill.absorbing.auto") : fork ? Component.translatable("murim.flow.keys.fork", mc.options.keyLeft.getTranslatedKeyMessage(),
                    mc.options.keyRight.getTranslatedKeyMessage())
                    : Component.translatable("murim.flow.keys.hold", mc.options.keyAttack.getTranslatedKeyMessage(),
                    mc.options.keyJump.getTranslatedKeyMessage());
        } else {
            hint = Component.translatable("murim.flow." + switch (game.state) {
                case DONE -> "done";
                case TORN -> "torn";
                case BURST -> "burst";
                default -> "timeout";
            });
        }
        if (alpha > 8) HudText.centered(g, mc.font, hint, cx, h - 16, (alpha << 24) | 0xD5CCDA, .8F);
    }

    /** Kept for callers of the old texture-backed overlay; there are no screen textures to release. */
    public static void reset() { }
}
