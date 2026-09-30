package io.github.verycooltimo.murim.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;

/**
 * Круги и дуги для интерфейса: четырёхугольники через тот же {@code RenderType.gui()},
 * что и ванильный {@code fill}.
 * API: reference/minecraft-src/net/minecraft/client/gui/GuiGraphics.java#fill
 *
 * <p>Обход вершин выравнивается под обход {@code fill}: у типа интерфейса включено
 * отсечение задних граней, и сегмент с обратным обходом просто не рисуется.
 */
public final class GuiShapes {

    /** Сектор кольца от угла {@code from} до {@code to} в радианах (0 — вправо, по часовой). */
    public static void arc(GuiGraphics graphics, float cx, float cy, float inner, float outer,
                           double from, double to, int colour) {
        if (outer <= 0.0F || outer <= inner || to <= from) {
            return;
        }
        float in = Math.max(0.0F, inner);
        var matrix = graphics.pose().last().pose();
        var consumer = graphics.bufferSource().getBuffer(RenderType.gui());
        int segments = Math.max(4, (int) Math.ceil((to - from) / (Math.PI * 2.0D) * 72.0D));
        for (int i = 0; i < segments; i++) {
            double a0 = from + (to - from) * i / segments;
            double a1 = from + (to - from) * (i + 1) / segments;
            float[] xs = {cx + (float) Math.cos(a0) * in, cx + (float) Math.cos(a1) * in,
                          cx + (float) Math.cos(a1) * outer, cx + (float) Math.cos(a0) * outer};
            float[] ys = {cy + (float) Math.sin(a0) * in, cy + (float) Math.sin(a1) * in,
                          cy + (float) Math.sin(a1) * outer, cy + (float) Math.sin(a0) * outer};
            float area = 0.0F;
            for (int k = 0; k < 4; k++) {
                int n = (k + 1) % 4;
                area += xs[k] * ys[n] - xs[n] * ys[k];
            }
            if (area > 0.0F) {
                for (int k = 3; k >= 0; k--) {
                    consumer.addVertex(matrix, xs[k], ys[k], 0.0F).setColor(colour);
                }
            } else {
                for (int k = 0; k < 4; k++) {
                    consumer.addVertex(matrix, xs[k], ys[k], 0.0F).setColor(colour);
                }
            }
        }
    }

    /** Полное кольцо или круг (при нулевом внутреннем радиусе). */
    public static void ring(GuiGraphics graphics, float cx, float cy, float inner, float outer, int colour) {
        arc(graphics, cx, cy, inner, outer, 0.0D, Math.PI * 2.0D, colour);
    }

    /** Мягкое свечение: несколько концентрических кругов с падающей прозрачностью. */
    public static void glow(GuiGraphics graphics, float cx, float cy, float radius, int rgb, int alpha) {
        for (int i = 3; i >= 1; i--) {
            float r = radius * i / 3.0F;
            int a = alpha * (4 - i) / 6;
            ring(graphics, cx, cy, 0.0F, r, (Math.min(255, a) << 24) | (rgb & 0xFFFFFF));
        }
    }

    private GuiShapes() {
    }
}
