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

    /**
     * Сектор кольца с зазором ПОСТОЯННОЙ ширины до соседей. Угловой отступ даёт щель,
     * сужающуюся к центру, — на кольце это читалось как кривизна (замечание автора 01.10).
     * На каждом радиусе отступ — {@code asin(gap/2 / r)}.
     *
     * @param gap ширина щели между секторами, пиксели интерфейса
     */
    public static float[][] sector(float cx, float cy, float inner, float outer, double from, double to,
                                   float gap, int steps) {
        double di = Math.asin(Math.min(1.0D, gap / 2.0D / Math.max(inner, 0.01F)));
        double dout = Math.asin(Math.min(1.0D, gap / 2.0D / outer));
        float[][] pts = new float[(steps + 1) * 2][];
        for (int i = 0; i <= steps; i++) {
            double k = i / (double) steps;
            double ai = from + di + (to - from - 2 * di) * k;
            double ao = from + dout + (to - from - 2 * dout) * k;
            pts[i * 2] = new float[] {cx + (float) Math.cos(ai) * inner, cy + (float) Math.sin(ai) * inner};
            pts[i * 2 + 1] = new float[] {cx + (float) Math.cos(ao) * outer, cy + (float) Math.sin(ao) * outer};
        }
        return pts;
    }

    /**
     * Сектор, залитый текстурой: координаты текстуры — от положения на экране, так что
     * одна текстура лежит через всё кольцо. API: Tesselator#begin, BufferUploader#drawWithShader,
     * GameRenderer#getPositionTexColorShader (javap по build/moddev/artifacts/neoforge-21.1.*.jar).
     *
     * @param span сколько пикселей интерфейса занимает текстура целиком
     */
    public static void texturedSector(GuiGraphics graphics, net.minecraft.resources.ResourceLocation texture,
                                      float cx, float cy, float inner, float outer, double from, double to,
                                      float gap, float span, int argb) {
        float[][] pts = sector(cx, cy, inner, outer, from, to, gap, 24);
        var matrix = graphics.pose().last().pose();
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader);
        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, texture);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        var builder = com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int i = 0; i + 3 < pts.length; i += 2) {
            float[][] quad = {pts[i], pts[i + 2], pts[i + 3], pts[i + 1]};
            float area = 0.0F;
            for (int k = 0; k < 4; k++) {
                int n = (k + 1) % 4;
                area += quad[k][0] * quad[n][1] - quad[n][0] * quad[k][1];
            }
            for (int k = 0; k < 4; k++) {
                float[] p = quad[area > 0.0F ? 3 - k : k];
                builder.addVertex(matrix, p[0], p[1], 0.0F)
                        .setUv((p[0] - cx) / span + 0.5F, (p[1] - cy) / span + 0.5F)
                        .setColor(argb);
            }
        }
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(builder.buildOrThrow());
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    /** Сплошная заливка сектора с теми же зазорами — для подсветки выбранного. */
    public static void sectorFill(GuiGraphics graphics, float cx, float cy, float inner, float outer,
                                  double from, double to, float gap, int colour) {
        float[][] pts = sector(cx, cy, inner, outer, from, to, gap, 24);
        var matrix = graphics.pose().last().pose();
        var consumer = graphics.bufferSource().getBuffer(RenderType.gui());
        for (int i = 0; i + 3 < pts.length; i += 2) {
            float[][] quad = {pts[i], pts[i + 2], pts[i + 3], pts[i + 1]};
            float area = 0.0F;
            for (int k = 0; k < 4; k++) {
                int n = (k + 1) % 4;
                area += quad[k][0] * quad[n][1] - quad[n][0] * quad[k][1];
            }
            for (int k = 0; k < 4; k++) {
                float[] p = quad[area > 0.0F ? 3 - k : k];
                consumer.addVertex(matrix, p[0], p[1], 0.0F).setColor(colour);
            }
        }
    }

    /** Тонкая обводка сектора: внешняя и внутренняя дуги и оба радиальных края. */
    public static void sectorOutline(GuiGraphics graphics, float cx, float cy, float inner, float outer,
                                     double from, double to, float gap, float width, int colour) {
        float[][] pts = sector(cx, cy, inner, outer, from, to, gap, 24);
        int last = pts.length - 2;
        for (int i = 0; i + 2 < pts.length; i += 2) {
            line(graphics, pts[i], pts[i + 2], width, colour);
            line(graphics, pts[i + 1], pts[i + 3], width, colour);
        }
        line(graphics, pts[0], pts[1], width, colour);
        line(graphics, pts[last], pts[last + 1], width, colour);
    }

    /** Отрезок заданной толщины четырёхугольником. */
    public static void line(GuiGraphics graphics, float[] a, float[] b, float width, int colour) {
        float dx = b[0] - a[0];
        float dy = b[1] - a[1];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0E-4F) {
            return;
        }
        float nx = -dy / len * width / 2.0F;
        float ny = dx / len * width / 2.0F;
        var matrix = graphics.pose().last().pose();
        var consumer = graphics.bufferSource().getBuffer(RenderType.gui());
        float[][] quad = {{a[0] + nx, a[1] + ny}, {b[0] + nx, b[1] + ny}, {b[0] - nx, b[1] - ny}, {a[0] - nx, a[1] - ny}};
        float area = 0.0F;
        for (int k = 0; k < 4; k++) {
            int n = (k + 1) % 4;
            area += quad[k][0] * quad[n][1] - quad[n][0] * quad[k][1];
        }
        for (int k = 0; k < 4; k++) {
            float[] p = quad[area > 0.0F ? 3 - k : k];
            consumer.addVertex(matrix, p[0], p[1], 0.0F).setColor(colour);
        }
    }

    private GuiShapes() {
    }
}
