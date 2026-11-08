package io.github.verycooltimo.murim.client.training;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.GuiShapes;
import io.github.verycooltimo.murim.client.HudText;
import io.github.verycooltimo.murim.training.Exercise;
import io.github.verycooltimo.murim.training.TrainingPayloads;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.Locale;

/**
 * Drill HUD, in the style of the qi line ({@code QiHud}: thin glowing lines — the character is the main thing on
 * screen). Shown only during a sect drill (the stone carried uphill, a route run), ABOVE the crosshair on a dark backdrop
 * ({@link HudText}): under it the captions covered the legs in third person and were pale on a light floor (codex on the
 * training stand, 05.10):
 *
 * <ul>
 *   <li>carry: a name and the blocks gained;</li>
 *   <li>runs: checkpoint n/N, the clock and a progress line; a fall flashes rust;</li>
 *   <li>under both, a faint body-stage line.</li>
 * </ul>
 * Nothing is shown for the body's ordinary growth (it is silent; the stage-up is one chat line). Small and centred: no
 * flashes over a large part of the screen (rules/04, accessibility).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class TrainingHud {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "training");

    private static final int WIDTH = 81;
    private static final int OCHRE = 0xE8B66A;
    private static final int GOLD = 0xFFE7A3;
    private static final int PALE = 0xD8D5C1;
    /** Title ink: brighter than {@link #PALE}, it is read against the world. */
    private static final int CREAM = 0xF4ECD8;
    /** Panel height below the title line: title, progress line, body line and its label. */
    private static final int PANEL = 40;
    private static final int RED = 0xC8553D;

    private TrainingHud() {
    }

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, LAYER, TrainingHud::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null) {
            return;
        }
        float now = mc.level.getGameTime() + delta.getGameTimeDeltaPartialTick(false);
        TrainingPayloads.State s = ClientTraining.local();
        int cx = g.guiWidth() / 2;
        // Above the head in third person: the panel ends 14 px over the crosshair.
        int y = g.guiHeight() / 2 - 14 - PANEL;
        if (s == null) {
            TrainingPayloads.State f = ClientTraining.finished();
            if (f != null && now - ClientTraining.finishedAt() < 100) {
                finished(g, mc.font, f, cx, y, now - ClientTraining.finishedAt());
            }
            return;
        }
        Exercise e = s.exerciseOrNull();
        if (e == null) {
            return;
        }
        if (e.route()) {
            route(g, mc.font, s, e, cx, y, now);
        } else {
            carry(g, mc.font, s, e, cx, y, now);
        }
        body(g, cx, y + 26);
        g.flush();
    }

    private static void carry(GuiGraphics g, Font font, TrainingPayloads.State s, Exercise e, int cx, int y, float now) {
        Component title = Component.translatable(e.nameKey()).append("  ")
                .append(Component.translatable("murim.training.hud.blocks", s.reps()));
        panel(g, font, title, cx, y);
        g.drawCenteredString(font, title, cx, y, 0xFF000000 | CREAM);
        int line = y + 14;
        int half = WIDTH / 2;
        g.fill(cx - half, line, cx + half + 1, line + 1, 0x60FFFFFF);
        float age = now - ClientTraining.flashAt();
        if (ClientTraining.flash() == TrainingPayloads.Beat.GAIN && age < 8.0F) {
            GuiShapes.glow(g, cx, line, 4.0F + age * 0.4F, GOLD, (int) (200 * (1.0F - age / 8.0F)));
        }
        GuiShapes.ring(g, cx, line, 0.0F, 1.4F, 0xFFF4ECD8);
    }

    private static void route(GuiGraphics g, Font font, TrainingPayloads.State s, Exercise e, int cx, int y, float now) {
        int elapsed = (int) Math.max(0, now - s.origin());
        // Checkpoints go in the title: under the bar they collided with the body line.
        Component title = Component.translatable(e.nameKey()).append("  ").append(Component.literal(clock(elapsed)))
                .append("  " + s.reps() + "/" + Math.max(1, s.total()));
        panel(g, font, title, cx, y);
        g.drawCenteredString(font, title, cx, y, 0xFF000000 | CREAM);
        int line = y + 14;
        int half = WIDTH / 2;
        int total = Math.max(1, s.total());
        float done = Mth.clamp(s.reps() / (float) total, 0.0F, 1.0F);
        g.fill(cx - half, line, cx + half + 1, line + 1, 0x60FFFFFF);
        g.fill(cx - half, line - 1, cx - half + Math.round(WIDTH * done), line + 1, 0xD0000000 | OCHRE);
        for (int i = 0; i <= total; i++) {
            int x = cx - half + Math.round(WIDTH * i / (float) total);
            g.fill(x, line - 2, x + 1, line + 3, i <= s.reps() ? 0xE0000000 | OCHRE : 0x50FFFFFF);
        }
        float age = now - ClientTraining.flashAt();
        if (ClientTraining.flash() == TrainingPayloads.Beat.FELL && age < 30.0F) {
            HudText.centered(g, font, Component.translatable("murim.training.hud.fell"), cx, y - 14,
                    ((int) (255 * (1.0F - age / 30.0F)) << 24) | RED);
        } else if (ClientTraining.flash() == TrainingPayloads.Beat.REACH && age < 10.0F) {
            GuiShapes.glow(g, cx - half + Math.round(WIDTH * done), line, 4.0F + age * 0.4F, GOLD, (int) (200 * (1.0F - age / 10.0F)));
        }
    }

    private static void finished(GuiGraphics g, Font font, TrainingPayloads.State f, int cx, int y, float age) {
        Exercise e = f.exerciseOrNull();
        if (e == null) {
            return;
        }
        int alpha = (int) (255 * Mth.clamp((100.0F - age) / 30.0F, 0.0F, 1.0F));
        if (alpha < 8) {
            return;
        }
        Component line = Component.translatable(e.nameKey()).append("  ").append(Component.literal(clock(f.reps())));
        HudText.centered(g, font, line, cx, y, (alpha << 24) | GOLD);
        HudText.centered(g, font, Component.literal(String.format(Locale.ROOT, "+%.1f", f.gain())), cx, y + 12, (alpha << 24) | OCHRE);
    }

    /** Body level: faint line of progress to the next level. */
    private static void body(GuiGraphics g, int cx, int y) {
        TrainingPayloads.Body b = ClientTraining.body();
        int half = WIDTH / 2;
        g.fill(cx - half, y, cx + half + 1, y + 1, 0x40FFFFFF);
        g.fill(cx - half, y, cx - half + Math.round(WIDTH * Mth.clamp(b.progress(), 0.0F, 1.0F)), y + 1, 0xC0000000 | PALE);
        // Level label under the line, inside the panel (it hung left of the bar, faint, over the world).
        Minecraft mc = Minecraft.getInstance();
        Component label = Component.translatable("murim.training.hud.level", b.level());
        float k = 0.75F;
        g.pose().pushPose();
        g.pose().translate(cx - mc.font.width(label) * k / 2.0F, y + 4, 0.0F);
        g.pose().scale(k, k, 1.0F);
        g.drawString(mc.font, label, 0, 0, 0xE0000000 | PALE, true);
        g.pose().popPose();
    }

    /** Dark panel under the whole widget, wide enough for the title (EN names run longer). */
    private static void panel(GuiGraphics g, Font font, Component title, int cx, int y) {
        int half = Math.max(WIDTH / 2 + 4, font.width(title) / 2) + 6;
        HudText.backdrop(g, cx - half, y - 4, cx + half + 1, y + PANEL, 255);
    }

    static String clock(int ticks) {
        int sec = ticks / 20;
        return String.format(Locale.ROOT, "%d:%02d.%d", sec / 60, sec % 60, (ticks % 20) / 2);
    }
}
