package io.github.verycooltimo.murim.client.flow;

import io.github.verycooltimo.murim.client.ClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;

/**
 * The frame-wide part of the PILL SCENES' effects (the high pill game draws nothing on the screen any more): haze in the pill's colour over the whole frame that thickens with the
 * progress (refs: the whole frame goes into the pill's colour), a red edge near a backlash, the flash with rays of the finale, the dark/red
 * flash of a mistake. Drawn before the HUD text so the hands, pressure and clock stay readable. Plain alpha-blended GUI quads, no textures.
 */
final class FlowFxScreen {
    private FlowFxScreen() { }

    /** The pill scene's frame: haze in the pill's colour, wind bands, the peak flash and rays (all from {@link PillScenePlan}). */
    static void renderScene(GuiGraphics g, int ticks, float age, float strength, int rgb) {
        if (strength <= 0) return;
        paint(g, strength, PillScenePlan.haze(ticks, age), rgb, 0, 0, 0, PillScenePlan.flash(ticks, age) * .75F, PillScenePlan.rays(ticks, age),
                lerp(rgb, 0xFFFFFF, .45), PillScenePlan.wind(ticks, age), true);
    }

    /** The ordinary pill's haze: a light veil in the pill's colour with a little wind. */
    static void renderCommon(GuiGraphics g, float haze, int rgb, float visibility) {
        if (haze <= .004F) return;
        paint(g, visibility, haze * .95F, rgb, 0, 0, 0, 0, 0, rgb, haze * .7F, false);
    }

    private static int lerp(int a, int b, double k) { return mix(a, b, k); }

    private static void paint(GuiGraphics g, float visibility, float hazeV, int hazeRgbV, float edgeV, float darkV, float redV, float flashV, float raysV,
                              int rayRgbV, float windK, boolean softRays) {
        float w = g.guiWidth(), h = g.guiHeight();
        float cx = FlowFocusMask.ready() ? FlowFocusMask.centreX() * w : w / 2, cy = FlowFocusMask.ready() ? FlowFocusMask.centreY() * h : h / 2;
        boolean flashes = ClientConfig.SCREEN_FLASHES.get();
        g.flush();
        var matrix = g.pose().last().pose();
        var c = g.bufferSource().getBuffer(RenderType.gui());
        // haze: every corner of the grid has its own alpha, a little thinner round the chest so the figure stays clear
        float haze = hazeV * visibility;
        float edge = edgeV * visibility;
        if (haze > .004F || edge > .004F) {
            int rgb = hazeRgbV & 0xFFFFFF;
            int cols = 20, rows = 12;
            for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) {
                for (int corner = 0; corner < 4; corner++) {
                    float px = (x + (corner >= 2 ? 1 : 0)) * w / cols, py = (y + (corner == 1 || corner == 2 ? 1 : 0)) * h / rows;
                    double d = Math.min(1, Math.hypot((px - cx) / (w * .6), (py - cy) / (h * .75)));
                    double wind = .86 + .14 * Math.sin(px * .011 + System.nanoTime() / 1e9 * 1.4 + py * .007);
                    double a = (haze * (.5 + .5 * d) * wind + edge * .6 * d * d);
                    int ai = (int) Math.round(255 * Math.min(.7, a));
                    int col = edge > .004F ? mix(rgb, 0xE2485A, Math.min(1, edge * d * 1.6)) : rgb;
                    c.addVertex(matrix, px, py, 0).setColor(ai << 24 | col);
                }
            }
        }
        if (windK > .01F) windBands(c, matrix, w, h, hazeRgbV & 0xFFFFFF, windK * visibility);
        quad(c, matrix, w, h, darkV * visibility * .5F, 0x000000);
        quad(c, matrix, w, h, redV * visibility * (flashes ? .55F : .2F), 0xE2485A);
        quad(c, matrix, w, h, flashV * visibility * (flashes ? .62F : .3F), 0xFFFFFF);
        float rays = raysV * visibility;
        float glow = Math.max(rays * .8F, flashV * visibility);
        if (glow > .01F) { // the light centre: a soft sun that fills the frame from the chest
            int rgbG = rayRgbV & 0xFFFFFF;
            int ring = 20;
            double rad = Math.hypot(w, h) * (.35 + .25 * Math.min(1F, glow));
            for (int i = 0; i < ring; i++) {
                double a0 = i * Math.PI * 2 / ring, a1 = (i + 1) * Math.PI * 2 / ring;
                int ac = (int) (255 * Math.min(.75F, glow * (flashes ? .7F : .3F))) << 24 | 0xFFFFFF;
                c.addVertex(matrix, cx, cy, 0).setColor(ac);
                c.addVertex(matrix, cx, cy, 0).setColor(ac);
                c.addVertex(matrix, (float) (cx + Math.cos(a1) * rad), (float) (cy + Math.sin(a1) * rad), 0).setColor(rgbG);
                c.addVertex(matrix, (float) (cx + Math.cos(a0) * rad), (float) (cy + Math.sin(a0) * rad), 0).setColor(rgbG);
            }
        }
        if (rays > .01F) {
            int rgb = rayRgbV & 0xFFFFFF;
            double far = Math.hypot(w, h);
            double spin = System.nanoTime() / 1e9 * .05;
            for (int i = 0; i < 30; i++) {
                double a = i * Math.PI * 2 / 30 + (i * 7 % 5) * .09 + spin;
                double wd = .022 + (i % 4) * .016;
                double len = far * (.55 + (i * 3 % 7) * .08);
                float al = rays * (flashes ? .5F : .2F) * (.5F + (i % 3) * .25F);
                double dx = Math.cos(a), dy = Math.sin(a), nx = -dy, ny = dx;
                int a0 = (int) (255 * Math.min(.8F, al)) << 24 | rgb;
                int a1 = rgb;
                float x0 = cx, y0 = cy;
                float x1 = (float) (cx + dx * len), y1 = (float) (cy + dy * len);
                float b = (float) (len * wd);
                if (softRays) {
                    // a streak with soft sides: bright along its middle line, clear at both edges and at the far end
                    float bw = b * .7F;
                    int mid = (int) (255 * Math.min(.8F, al)) << 24 | rgb;
                    float mx = (x0 + x1) * .5F, my = (y0 + y1) * .5F;
                    for (int side = -1; side <= 1; side += 2) {
                        c.addVertex(matrix, x0, y0, 0).setColor(mid);
                        c.addVertex(matrix, x0 + side * (float) nx * 2, y0 + side * (float) ny * 2, 0).setColor(a1);
                        c.addVertex(matrix, x1 + side * (float) nx * bw, y1 + side * (float) ny * bw, 0).setColor(a1);
                        c.addVertex(matrix, x1, y1, 0).setColor(a1);
                    }
                    continue;
                }
                c.addVertex(matrix, x0 - (float) nx * 2, y0 - (float) ny * 2, 0).setColor(a0);
                c.addVertex(matrix, x0 + (float) nx * 2, y0 + (float) ny * 2, 0).setColor(a0);
                c.addVertex(matrix, x1 + (float) nx * b, y1 + (float) ny * b, 0).setColor(a1);
                c.addVertex(matrix, x1 - (float) nx * b, y1 - (float) ny * b, 0).setColor(a1);
            }
        }
        g.flush();
    }

    /** Wind: wide soft bands of the pill's colour that stream across the frame, each at its own height and speed (cloth and hair in the refs are carried the same way). */
    private static void windBands(com.mojang.blaze3d.vertex.VertexConsumer c, org.joml.Matrix4f m, float w, float h, int rgb, float k) {
        double t = System.nanoTime() / 1e9;
        for (int i = 0; i < 7; i++) {
            double speed = .10 + .035 * (i % 4), phase = (t * speed + i * .291) % 1.0;
            float len = w * (.5F + .08F * (i % 3));
            float x0 = (float) (-len + phase * (w + len * 2));
            float cy = h * (.12F + .13F * i + .02F * (float) Math.sin(t * .7 + i));
            float half = h * (.045F + .012F * (i % 3));
            float al = k * (.16F + .05F * (i % 3));
            int mid = (int) (255 * Math.min(.4F, al)) << 24 | rgb, edge = rgb;
            // three columns (fade in, body, fade out) by two rows (top edge to centre, centre to bottom edge)
            float[] xs = {x0, x0 + len * .3F, x0 + len * .7F, x0 + len};
            int[] a = {0, 1, 1, 0};
            for (int col = 0; col < 3; col++) for (int row = 0; row < 2; row++) {
                float ya = row == 0 ? cy - half : cy, yb = row == 0 ? cy : cy + half;
                int ca = row == 0 ? 0 : 1, cb = row == 0 ? 1 : 0;
                c.addVertex(m, xs[col], ya, 0).setColor(col(mid, a[col] * ca));
                c.addVertex(m, xs[col], yb, 0).setColor(col(mid, a[col] * cb));
                c.addVertex(m, xs[col + 1], yb, 0).setColor(col(mid, a[col + 1] * cb));
                c.addVertex(m, xs[col + 1], ya, 0).setColor(col(mid, a[col + 1] * ca));
            }
        }
    }

    private static int col(int argb, int on) { return on == 0 ? argb & 0xFFFFFF : argb; }

    private static void quad(com.mojang.blaze3d.vertex.VertexConsumer c, org.joml.Matrix4f m, float w, float h, float alpha, int rgb) {
        if (alpha <= .004F) return;
        int a = (int) (255 * Math.min(1F, alpha)) << 24 | rgb;
        c.addVertex(m, 0, 0, 0).setColor(a);
        c.addVertex(m, 0, h, 0).setColor(a);
        c.addVertex(m, w, h, 0).setColor(a);
        c.addVertex(m, w, 0, 0).setColor(a);
    }

    private static int mix(int a, int b, double k) {
        int r = (int) (((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k), gg = (int) (((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k),
                bl = (int) ((a & 255) * (1 - k) + (b & 255) * k);
        return r << 16 | gg << 8 | bl;
    }
}
