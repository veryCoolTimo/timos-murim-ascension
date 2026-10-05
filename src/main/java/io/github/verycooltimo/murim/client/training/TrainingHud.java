package io.github.verycooltimo.murim.client.training;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.GuiShapes;
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
 * Training HUD, in the style of the qi line ({@code QiHud}: thin glowing lines, no panels — the character is the main
 * thing on screen). Shown only while training, under the crosshair:
 *
 * <ul>
 *   <li>a name and count line;</li>
 *   <li>rhythm sets: two ochre ticks slide in from both sides and meet in the centre on the beat — press crouch then;
 *       the centre flashes gold (on the beat), pale (near) or rust (off);</li>
 *   <li>a stamina line under it (the set ends when it runs out), and a faint body-level line with fatigue;</li>
 *   <li>runs: checkpoint n/N and the clock; a fall flashes rust.</li>
 * </ul>
 * Small and centred: no flashes over a large part of the screen (rules/04, accessibility).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class TrainingHud {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "training");

    private static final int WIDTH = 81;
    private static final int OCHRE = 0xE8B66A;
    private static final int RUST = 0x8A4B2A;
    private static final int GOLD = 0xFFE7A3;
    private static final int PALE = 0xD8D5C1;
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
        int y = g.guiHeight() / 2 + 22;
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
            set(g, mc.font, s, e, cx, y, now);
        }
        body(g, cx, y + 26);
        g.flush();
    }

    private static void set(GuiGraphics g, Font font, TrainingPayloads.State s, Exercise e, int cx, int y, float now) {
        Component title = Component.translatable(e.nameKey()).append("  ").append(count(s, e));
        g.drawCenteredString(font, title, cx, y, 0xFF000000 | PALE);
        int line = y + 14;
        int half = WIDTH / 2;
        g.fill(cx - half, line, cx + half + 1, line + 1, 0x30FFFFFF);
        if (e.rhythmic()) {
            float beat = e.beat();
            float phase = Mth.positiveModulo(now - s.origin(), beat) / beat;
            float k = 1.0F - phase;
            int off = Math.round(half * k);
            int a = 0xC0000000;
            g.fill(cx - off - 1, line - 2, cx - off + 1, line + 3, a | OCHRE);
            g.fill(cx + off - 1, line - 2, cx + off + 1, line + 3, a | OCHRE);
        } else if (e == Exercise.HORSE_STANCE) {
            // Stance: the line trembles a little more as breath runs out.
            float shake = (1.0F - s.stamina()) * 2.0F * Mth.sin(now * 1.7F);
            g.fill(cx - half, line + Math.round(shake), cx + half + 1, line + Math.round(shake) + 1, 0xA0000000 | OCHRE);
        }
        float age = now - ClientTraining.flashAt();
        int flash = switch (ClientTraining.flash()) {
            case GOOD, HOLD -> GOLD;
            case FAIR -> PALE;
            case OFF, RUSHED -> RED;
            default -> 0;
        };
        if (flash != 0 && age < 8.0F) {
            int alpha = (int) (200 * (1.0F - age / 8.0F));
            GuiShapes.glow(g, cx, line, 4.0F + age * 0.4F, flash, alpha);
        }
        GuiShapes.ring(g, cx, line, 0.0F, 1.4F, 0xFFF4ECD8);
        // Stamina: a thin ochre line, rust when low.
        float st = Mth.clamp(s.stamina(), 0.0F, 1.0F);
        int filled = Math.round(WIDTH * st);
        int col = st < 0.25F ? RED : OCHRE;
        g.fill(cx - half, line + 5, cx - half + filled, line + 6, 0xD0000000 | col);
    }

    private static Component count(TrainingPayloads.State s, Exercise e) {
        return switch (e) {
            case HORSE_STANCE -> Component.literal(s.reps() + " s");
            case CARRY_STONE -> Component.translatable("murim.training.hud.blocks", s.reps());
            default -> Component.literal(Integer.toString(s.reps()));
        };
    }

    private static void route(GuiGraphics g, Font font, TrainingPayloads.State s, Exercise e, int cx, int y, float now) {
        int elapsed = (int) Math.max(0, now - s.origin());
        Component title = Component.translatable(e.nameKey()).append("  ").append(Component.literal(clock(elapsed)));
        g.drawCenteredString(font, title, cx, y, 0xFF000000 | PALE);
        int line = y + 14;
        int half = WIDTH / 2;
        int total = Math.max(1, s.good());
        float done = Mth.clamp(s.reps() / (float) total, 0.0F, 1.0F);
        g.fill(cx - half, line, cx + half + 1, line + 1, 0x30FFFFFF);
        g.fill(cx - half, line - 1, cx - half + Math.round(WIDTH * done), line + 1, 0xD0000000 | OCHRE);
        for (int i = 0; i <= total; i++) {
            int x = cx - half + Math.round(WIDTH * i / (float) total);
            g.fill(x, line - 2, x + 1, line + 3, i <= s.reps() ? 0xE0000000 | OCHRE : 0x50FFFFFF);
        }
        g.drawCenteredString(font, Component.literal(s.reps() + "/" + total), cx, line + 5, 0xC0000000 | PALE);
        float age = now - ClientTraining.flashAt();
        if (ClientTraining.flash() == TrainingPayloads.Beat.FELL && age < 30.0F) {
            g.drawCenteredString(font, Component.translatable("murim.training.hud.fell"), cx, y - 12,
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
        g.drawCenteredString(font, line, cx, y, (alpha << 24) | GOLD);
        g.drawCenteredString(font, Component.literal(String.format(Locale.ROOT, "+%.1f", f.gain())), cx, y + 11, (alpha << 24) | OCHRE);
    }

    /** Body level: faint line of progress to the next level; fatigue as a rust stretch from the right. */
    private static void body(GuiGraphics g, int cx, int y) {
        TrainingPayloads.Body b = ClientTraining.body();
        int half = WIDTH / 2;
        g.fill(cx - half, y, cx - half + Math.round(WIDTH * Mth.clamp(b.progress(), 0.0F, 1.0F)), y + 1, 0x70000000 | PALE);
        int tired = Math.round(WIDTH * Mth.clamp(b.fatigue(), 0.0F, 1.0F));
        g.fill(cx + half + 1 - tired, y + 2, cx + half + 1, y + 3, 0x90000000 | RUST);
        Minecraft mc = Minecraft.getInstance();
        g.drawString(mc.font, Component.translatable("murim.training.hud.level", b.level()), cx - half - 2
                - mc.font.width(Component.translatable("murim.training.hud.level", b.level())), y - 3, 0x90000000 | PALE, false);
    }

    static String clock(int ticks) {
        int sec = ticks / 20;
        return String.format(Locale.ROOT, "%d:%02d.%d", sec / 60, sec % 60, (ticks % 20) / 2);
    }
}
