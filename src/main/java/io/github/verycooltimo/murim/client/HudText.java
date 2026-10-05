package io.github.verycooltimo.murim.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * One style for HUD captions over the world: text with a drop shadow on a dark translucent backdrop, kept inside the
 * screen. Taken from {@code RankReading} (codex 01.10: without the backdrop a line sinks into aura flashes and a red
 * sky); the training HUD lines were pale on a light floor without it (codex 05.10).
 *
 * <p>The backdrop alpha follows the text alpha, so fades stay smooth; nothing here flashes (rules/04, accessibility).
 * Screens drawn on paper (books, scrolls, dialogue) keep their ink style and do not use this.
 *
 * <p>API: reference/minecraft-src/net/minecraft/client/gui/GuiGraphics.java#fill, #drawString(Font, Component, int, int, int, boolean).
 */
public final class HudText {

    /** Backdrop colour: near-black with a blue cast, as in {@code RankReading}. */
    public static final int BACKDROP = 0x05070B;
    /** Backdrop opacity relative to the text alpha. */
    public static final float BACKDROP_K = 0.62F;
    /** Distance kept from the screen edge. */
    public static final int MARGIN = 2;

    private HudText() {
    }

    /** Centred caption at full scale. */
    public static void centered(GuiGraphics g, Font font, Component text, int cx, int y, int argb) {
        centered(g, font, text, cx, y, argb, 1.0F);
    }

    /**
     * Centred caption at {@code scale}; shifted sideways to stay inside the screen.
     *
     * @param argb text colour; its alpha drives the backdrop too
     * @return the left edge actually used (GUI pixels)
     */
    public static int centered(GuiGraphics g, Font font, Component text, int cx, int y, int argb, float scale) {
        float w = font.width(text) * scale;
        int left = Mth.clamp(Math.round(cx - w / 2.0F), MARGIN + 3, Math.max(MARGIN + 3, g.guiWidth() - MARGIN - 3 - Math.round(w)));
        draw(g, font, text, left, y, argb, scale);
        return left;
    }

    /** Caption that ends at {@code right} (for labels left of a bar). */
    public static void rightAligned(GuiGraphics g, Font font, Component text, int right, int y, int argb, float scale) {
        int w = Math.round(font.width(text) * scale);
        draw(g, font, text, Math.max(MARGIN + 3, right - w), y, argb, scale);
    }

    /** Caption from its left edge. */
    public static void draw(GuiGraphics g, Font font, Component text, int x, int y, int argb, float scale) {
        int a = argb >>> 24;
        if (a < 8) {
            return;
        }
        int w = Math.round(font.width(text) * scale);
        int h = Math.round(font.lineHeight * scale);
        backdrop(g, x - 3, y - 2, x + w + 3, y + h + 1, a);
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(scale, scale, 1.0F);
        g.drawString(font, text, 0, 0, argb, true);
        g.pose().popPose();
    }

    /** Dark backdrop under a group of lines and bars; {@code alpha} is the content alpha 0..255. */
    public static void backdrop(GuiGraphics g, int x0, int y0, int x1, int y1, int alpha) {
        int a = Mth.clamp(Math.round(alpha * BACKDROP_K), 0, 255);
        if (a > 0) {
            g.fill(x0, y0, x1, y1, a << 24 | BACKDROP);
        }
    }
}
