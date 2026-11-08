package io.github.verycooltimo.murim.client.library;

import io.github.verycooltimo.murim.library.LibraryNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/gui/{GuiGraphics.java,screens/Screen.java}
// (blit, scissor stack, colour, drawing, close/input overrides), gui/Font.java#split,
// Minecraft.java#getResourceManager/getTextureManager/getSoundManager/setScreen,
// resources/sounds/SimpleSoundInstance.java#forUI; server/packs/resources/Resource.java#open.
/** A hanging scroll over the live world. No vanilla background/blur or book pagination. */
public final class MethodScrollScreen extends Screen {
    private record Picture(ResourceLocation path, int width, int height, boolean present) { }
    private record Line(FormattedCharSequence text, int y, boolean heading) { }
    private final LibraryNetwork.OpenMethodScroll scroll;
    private LibraryNetwork.MethodState state;
    private boolean pending;
    private final List<Line> lines = new ArrayList<>();
    private MethodScrollLayout layout;
    private Picture top;
    private Picture paper;
    private Picture bottom;
    private Picture art;
    private long opened;
    private long closing;
    private long captureElapsed = -1;
    private boolean renderedUnrolled;
    private double closeFrom;
    private double offset;
    private int textHeight;

    public static void open(LibraryNetwork.OpenMethodScroll scroll) {
        Minecraft.getInstance().setScreen(new MethodScrollScreen(scroll));
    }

    /** A deterministic animation pose for gallery filming, independent of tick rate. */
    public static void openForCapture(LibraryNetwork.OpenMethodScroll scroll, long elapsedNanos) {
        var screen = new MethodScrollScreen(scroll);
        screen.captureElapsed = elapsedNanos;
        Minecraft.getInstance().setScreen(screen);
    }

    public boolean unrolled() {
        return renderedUnrolled;
    }

    private MethodScrollScreen(LibraryNetwork.OpenMethodScroll scroll) {
        super(Component.translatable(scroll.titleKey()));
        this.scroll = scroll;
        this.state = scroll.state();
    }

    @Override
    protected void init() {
        top = picture("top.png");
        paper = picture("paper.png");
        bottom = picture("bottom.png");
        art = picture("art/" + scroll.method().getPath() + ".png");
        if (!art.present) art = picture("art/default.png");
        layout = MethodScrollLayout.of(width, height, art.width, art.height);
        lines.clear();
        int y = paragraph(getTitle(), 0, true);
        for (String key : scroll.textKeys()) y = paragraph(Component.translatable(key), y, false);
        textHeight = Math.max(0, y - 4); // No trailing paragraph gap needs to be visible.
        layout = MethodScrollLayout.of(width, height, art.width, art.height, textHeight);
        offset = Math.min(offset, maxOffset());
        if (opened == 0) {
            opened = System.nanoTime();
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 0.85F, 0.3F));
        }
    }

    private Picture picture(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("murim", "textures/gui/scroll/" + path);
        var resource = minecraft.getResourceManager().getResource(id);
        if (resource.isPresent()) {
            try (var input = resource.get().open()) {
                var image = ImageIO.read(input);
                if (image != null) return new Picture(id, image.getWidth(), image.getHeight(), true);
            } catch (IOException ignored) {
                // Missing/broken resource packs retain a readable paper and the default illustration.
            }
        }
        return new Picture(id, 128, 128, false);
    }

    private int paragraph(Component text, int y, boolean heading) {
        float scale = heading ? 1.25F : 1;
        for (var line : font.split(text, (int) (layout.text().width() / scale))) {
            lines.add(new Line(line, y, heading));
            // Compact leading, with the original 1.25x title and 1x body glyph sizes.
            y += (int) Math.ceil(font.lineHeight * scale);
        }
        return y + 4;
    }

    private double reveal(long now) {
        if (closing != 0) return closeFrom * (1 - ease((now - closing) / 350_000_000.0));
        return ease((now - opened) / 600_000_000.0);
    }

    private static double ease(double t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - Math.pow(1 - t, 3);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = captureElapsed >= 0 && closing == 0 ? opened + captureElapsed : System.nanoTime();
        if (closing != 0 && now - closing >= 350_000_000L) {
            super.onClose();
            return;
        }
        renderBackground(g, mouseX, mouseY, partialTick);
        double reveal = reveal(now);
        int end = layout.paper().y() + (int) Math.round(layout.paper().height() * reveal);
        var p = layout.paper();
        g.enableScissor(p.x(), p.y(), p.right(), end);
        try {
            // Tile vertically at native height scaled to the paper width; never stretch one strip down the page.
            int tileHeight = Math.max(1, (int) Math.round(paper.height * p.width() / (double) paper.width));
            for (int y = p.y(); y < p.bottom(); y += tileHeight) {
                draw(g, paper, new MethodScrollLayout.Rect(p.x(), y, p.width(), tileHeight), 0xFFE7D7AF);
            }
            float alpha = (float) Math.max(0, Math.min(1, (now - opened - 600_000_000L) / 200_000_000.0));
            if (closing != 0) alpha *= (float) (reveal / Math.max(0.0001, closeFrom));
            if (alpha > 0.02F) {
                g.setColor(1, 1, 1, alpha);
                try {
                    if (art.present) draw(g, art, layout.illustration(), 0);
                } finally {
                    g.setColor(1, 1, 1, 1);
                }
                drawText(g, alpha);
                drawSeal(g, mouseX, mouseY, alpha);
            }
        } finally {
            g.disableScissor();
        }
        draw(g, top, layout.top(), 0xFF655037);
        var b = layout.bottom();
        draw(g, bottom, new MethodScrollLayout.Rect(b.x(), end, b.width(), b.height()), 0xFF655037);
        renderedUnrolled = closing == 0 && now - opened >= 600_000_000L;
    }

    public static void updateState(LibraryNetwork.MethodState state) {
        if (Minecraft.getInstance().screen instanceof MethodScrollScreen screen
                && screen.scroll.method().equals(state.method())) {
            screen.state = state;
            screen.pending = false;
        }
    }

    private void drawSeal(GuiGraphics g, int mouseX, int mouseY, float alpha) {
        var r = layout.seal();
        boolean can = state.status().equals("can") && !pending;
        int a = Math.round(alpha * 255) << 24;
        int ink = a | (can ? r.contains(mouseX, mouseY) ? 0x7E1A1F : 0xB3262C : 0x8A7C6A);
        g.fill(r.x() + 1, r.y(), r.right() - 1, r.bottom(), ink);
        g.fill(r.x(), r.y() + 1, r.right(), r.bottom() - 1, ink);
        g.fill(r.x() + 2, r.y() + 2, r.right() - 2, r.y() + 3, a | 0xD4AA94);
        g.fill(r.x() + 2, r.bottom() - 3, r.right() - 2, r.bottom() - 2, a | 0xD4AA94);
        var label = Component.translatable(state.status().equals("learned") ? "murim.manual.learned" : "murim.manual.learn");
        g.drawString(font, label, r.x() + (r.width() - font.width(label)) / 2, r.y() + 5, a | 0xF3E6D0, false);
        if (state.status().equals("blocked")) {
            // Draw a lock explicitly: the default font need not contain a lock glyph.
            g.fill(r.x() + 3, r.y() + 8, r.x() + 10, r.y() + 14, a | 0xF3E6D0);
            g.fill(r.x() + 4, r.y() + 4, r.x() + 9, r.y() + 9, a | 0xF3E6D0);
            g.fill(r.x() + 5, r.y() + 5, r.x() + 8, r.y() + 8, ink);
            footerLine(g, Component.translatable(state.reasonKey()), r.bottom() + 2, a);
        }
        if (!state.replacesKey().isEmpty()) {
            footerLine(g, Component.translatable("murim.method.replaces", Component.translatable(state.replacesKey())), r.y() - 13, a);
        }
    }

    private void footerLine(GuiGraphics g, Component text, int y, int alpha) {
        float scale = Math.min(0.65F, (layout.paper().width() - 12F) / Math.max(1, font.width(text)));
        g.pose().pushPose();
        g.pose().translate(layout.paper().x() + layout.paper().width() / 2F, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, -font.width(text) / 2, 0, alpha | 0x5B3429, false);
        g.pose().popPose();
    }

    private void drawText(GuiGraphics g, float alpha) {
        var area = layout.text();
        g.enableScissor(area.x(), area.y(), area.right(), area.bottom());
        try {
            int ink = ((int) (alpha * 255) << 24) | 0x30271C;
            for (Line line : lines) {
                g.pose().pushPose();
                try {
                    g.pose().translate(area.x(), area.y() + line.y - (int) offset, 0);
                    float scale = line.heading ? 1.25F : 1;
                    g.pose().scale(scale, scale, 1);
                    g.drawString(font, line.text, 0, 0, ink, false);
                } finally {
                    g.pose().popPose();
                }
            }
        } finally {
            g.disableScissor();
        }
        if (offset < maxOffset() && area.height() >= font.lineHeight) {
            // A separate paper-backed corner hint stays legible over a partially clipped line.
            int x = area.right() - 8;
            int y = area.bottom() - font.lineHeight;
            g.fill(x - 1, y, area.right(), area.bottom(), ((int) (alpha * 255) << 24) | 0xE7D7AF);
            g.drawString(font, "▼", x, y, ((int) (alpha * 255) << 24) | 0x30271C, false);
        }
    }

    private void draw(GuiGraphics g, Picture picture, MethodScrollLayout.Rect rect, int fallback) {
        if (rect.height() <= 0) return;
        if (!picture.present) {
            g.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), fallback);
            return;
        }
        // PNG dimensions are read from the pack, so illustrations retain their actual aspect ratio.
        minecraft.getTextureManager().getTexture(picture.path).setFilter(false, false);
        // API: supplied minecraft-src/net/minecraft/client/gui/GuiGraphics.java#innerBlit;
        // the uncoloured blit requires the caller to enable blending for transparent PNGs/fading.
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        try {
            g.blit(picture.path, rect.x(), rect.y(), rect.width(), rect.height(), 0, 0,
                    picture.width, picture.height, picture.width, picture.height);
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // A soft 20% edge vignette only: the world remains visible and receives no blur.
        for (int i = 0; i < 24; i++) {
            int x0 = width * i / 120;
            int x1 = width * (i + 1) / 120;
            int y0 = height * i / 120;
            int y1 = height * (i + 1) / 120;
            int colour = Math.round(51 * (1 - i / 24.0F)) << 24;
            // Disjoint rings keep corners at the same maximum 20% opacity as the edges.
            g.fill(x0, y0, width - x0, y1, colour);
            g.fill(x0, height - y1, width - x0, height - y0, colour);
            g.fill(x0, y1, x1, height - y1, colour);
            g.fill(width - x1, y1, width - x0, height - y1, colour);
        }
    }

    @Override
    public void onClose() {
        if (closing == 0) {
            long now = captureElapsed >= 0 ? opened + captureElapsed : System.nanoTime();
            closeFrom = reveal(now);
            closing = System.nanoTime();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && closing == 0 && renderedUnrolled && System.nanoTime() - opened >= 800_000_000L && !pending
                && state.status().equals("can") && layout.seal().contains(mouseX, mouseY)) {
            pending = true;
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new LibraryNetwork.LearnMethod(scroll.method()));
            return true;
        }
        if (!layout.bounds().contains(mouseX, mouseY)) {
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private double maxOffset() { return Math.max(0, textHeight - layout.text().height()); }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (closing == 0 && layout.paper().contains(mouseX, mouseY)) {
            offset = Math.max(0, Math.min(maxOffset(), offset - scrollY * (font.lineHeight + 3) * 3));
            return true;
        }
        return false;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
