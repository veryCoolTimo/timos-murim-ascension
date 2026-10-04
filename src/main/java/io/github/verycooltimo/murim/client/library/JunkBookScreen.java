package io.github.verycooltimo.murim.client.library;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.library.JunkBook;
import io.github.verycooltimo.murim.library.JunkKind;
import io.github.verycooltimo.murim.library.JunkText;
import io.github.verycooltimo.murim.library.LibraryNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * A junk book from the ruined library (docs/design/25-ruined-library.md §2): text only, no pictures (book-spread
 * rule: pictures only for advanced/secret techniques). Every junk volume uses the same plain spread — clones on the
 * outside and inside (author 04.10); water damage and the heretical method get a stained sheet. Paragraphs from
 * {@link JunkText} flow over both pages; the heretical method ends with a red «Попробовать» seal.
 *
 * <p>Same pixel grid as {@code ManualScreen} (texture 384×256, ~60 % of the screen width).
 */
public final class JunkBookScreen extends Screen {

    private static final int TEX_W = 384;
    private static final int TEX_H = 256;
    private static final int INK = 0xFF1B1612;
    private static final int FADED = 0xFF4A3F35;
    private static final int NOTE = 0xFF7D6C58;
    private static final int SEAL = 0xFFB3262C;
    private static final int SEAL_DARK = 0xFF7E1A1F;
    private static final int SEAL_TEXT = 0xFFF3E6D0;
    /** Full font scale: junk is read, not glanced at (0.8 left most volumes on one page with the other empty). */
    private static final float SCALE = 1.0F;
    /** Text boxes of the left and right page (texture coordinates). */
    private static final int[] PAGE_X = {26, 214};
    private static final int PAGE_Y = 22;
    private static final int PAGE_W = 144;
    private static final int PAGE_H = 186;
    private static final int SEAL_X = 284;
    private static final int SEAL_Y = 206;
    private static final int SEAL_W = 56;
    private static final int SEAL_H = 22;

    /** One laid-out line: text and colour, or a gap. */
    private record Line(FormattedCharSequence text, int colour) {
    }

    private final JunkBook book;
    private final boolean mainHand;
    private final ResourceLocation sheet;
    private final List<List<Line>> pages = new ArrayList<>();
    private int spread;
    private int bx;
    private int by;
    private float k;
    private PageButton prev;
    private PageButton next;

    public static void open(JunkBook book, boolean mainHand) {
        Minecraft.getInstance().setScreen(new JunkBookScreen(book, mainHand));
    }

    private JunkBookScreen(JunkBook book, boolean mainHand) {
        super(JunkText.title(book));
        this.book = book;
        this.mainHand = mainHand;
        boolean stained = book.kind() == JunkKind.WATER || book.kind() == JunkKind.HERETICAL;
        this.sheet = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID,
                stained ? "textures/gui/book/junk_text_stained.png" : "textures/gui/book/junk_text.png");
    }

    @Override
    protected void init() {
        k = Math.min(width * 0.6F / TEX_W, height * 0.72F / TEX_H);
        bx = (int) ((width - TEX_W * k) / 2);
        by = (int) ((height - TEX_H * k) / 2);
        layout();
        int y = (int) (by + TEX_H * k) - 18;
        // API: net.minecraft.client.gui.screens.inventory.PageButton (vanilla book arrows), as ManualScreen.
        prev = addRenderableWidget(new PageButton(bx + 8, y, false, b -> turn(-1), true));
        next = addRenderableWidget(new PageButton((int) (bx + TEX_W * k) - 31, y, true, b -> turn(1), true));
        if (minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 0.8F, 0.7F));
        }
    }

    /** Flow the paragraphs into pages of {@link #PAGE_H} at {@link #SCALE}. */
    private void layout() {
        pages.clear();
        int lw = (int) (PAGE_W / SCALE);
        int maxH = (int) (PAGE_H / SCALE);
        // The heretical method keeps room at the bottom of its last page for the seal.
        List<Line> page = new ArrayList<>();
        int h = 0;
        List<JunkText.Para> paras = JunkText.paras(book);
        for (int i = 0; i < paras.size(); i++) {
            JunkText.Para p = paras.get(i);
            int colour = switch (p.style()) {
                case HEADING -> INK;
                case NOTE -> NOTE;
                case SEAL -> SEAL_DARK;
                default -> FADED;
            };
            FormattedText text = p.style() == JunkText.Style.SMUDGED
                    ? FormattedText.of(JunkText.smudge(p.text().getString(), book.seed() * 7919L + i))
                    : p.text();
            List<FormattedCharSequence> lines = font.split(text, lw);
            int need = lines.size() * (font.lineHeight + 1);
            if (h > 0 && h + need > maxH) {
                pages.add(page);
                page = new ArrayList<>();
                h = 0;
            }
            for (FormattedCharSequence l : lines) {
                if (h + font.lineHeight > maxH) {
                    pages.add(page);
                    page = new ArrayList<>();
                    h = 0;
                }
                page.add(new Line(l, colour));
                h += font.lineHeight + 1;
            }
            page.add(new Line(null, 0));
            h += p.style() == JunkText.Style.HEADING ? 7 : 5;
        }
        pages.add(page);
        if (practicable() && h > maxH - (SEAL_H + 6) / SCALE && pages.size() % 2 == 0) {
            pages.add(new ArrayList<>());
        }
        if (pages.size() % 2 == 1) {
            pages.add(new ArrayList<>());
        }
    }

    private boolean practicable() {
        return book.kind() == JunkKind.HERETICAL;
    }

    private int spreads() {
        return Math.max(1, pages.size() / 2);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        prev.visible = spread > 0;
        next.visible = spread < spreads() - 1;
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.renderBackground(g, mouseX, mouseY, partial);
        g.pose().pushPose();
        g.pose().translate(bx, by, 0.0F);
        g.pose().scale(k, k, 1.0F);
        g.blit(sheet, 0, 0, 0.0F, 0.0F, TEX_W, TEX_H, TEX_W, TEX_H);
        for (int side = 0; side < 2; side++) {
            int index = spread * 2 + side;
            if (index < pages.size()) {
                page(g, pages.get(index), PAGE_X[side]);
            }
        }
        if (practicable() && spread == spreads() - 1) {
            seal(g, mouseX, mouseY);
        }
        if (spreads() > 1) {
            Component n = Component.translatable("junk.murim.screen.page", spread + 1, spreads());
            g.pose().pushPose();
            g.pose().translate(TEX_W / 2.0F - font.width(n) * 0.6F / 2.0F, TEX_H - 14, 0.0F);
            g.pose().scale(0.6F, 0.6F, 1.0F);
            g.drawString(font, n, 0, 0, NOTE, false);
            g.pose().popPose();
        }
        g.pose().popPose();
    }

    private void page(GuiGraphics g, List<Line> lines, int x) {
        g.pose().pushPose();
        g.pose().translate(x, PAGE_Y, 0.0F);
        g.pose().scale(SCALE, SCALE, 1.0F);
        int y = 0;
        for (Line l : lines) {
            if (l.text() == null) {
                y += 5;
                continue;
            }
            g.drawString(font, l.text(), 0, y, l.colour(), false);
            y += font.lineHeight + 1;
        }
        g.pose().popPose();
    }

    /** «Попробовать»: the same red stamp as the manuals' «Изучить», darker and pressed under the mouse. */
    private void seal(GuiGraphics g, int mouseX, int mouseY) {
        boolean hot = overSeal(mouseX, mouseY);
        int x = SEAL_X + (hot ? 1 : 0);
        int y = SEAL_Y + (hot ? 1 : 0);
        int base = hot ? SEAL_DARK : SEAL;
        g.fill(x + 1, y, x + SEAL_W - 1, y + SEAL_H, base);
        g.fill(x, y + 1, x + SEAL_W, y + SEAL_H - 1, base);
        g.fill(x + 2, y + 2, x + SEAL_W - 2, y + 3, SEAL_TEXT & 0x60FFFFFF);
        g.fill(x + 2, y + SEAL_H - 3, x + SEAL_W - 2, y + SEAL_H - 2, SEAL_TEXT & 0x60FFFFFF);
        Component label = Component.translatable("junk.murim.screen.try");
        float s = 0.75F;
        g.pose().pushPose();
        g.pose().translate(x + SEAL_W / 2.0F - font.width(label) * s / 2.0F, y + SEAL_H / 2.0F - 3.0F, 0.0F);
        g.pose().scale(s, s, 1.0F);
        g.drawString(font, label, 0, 0, SEAL_TEXT, false);
        g.pose().popPose();
    }

    private boolean overSeal(double mouseX, double mouseY) {
        double tx = (mouseX - bx) / k;
        double ty = (mouseY - by) / k;
        return practicable() && spread == spreads() - 1
                && tx >= SEAL_X && tx <= SEAL_X + SEAL_W && ty >= SEAL_Y && ty <= SEAL_Y + SEAL_H;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && overSeal(mouseX, mouseY)) {
            PacketDistributor.sendToServer(new LibraryNetwork.Practise(mainHand));
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0.0D) {
            turn(scrollY < 0.0D ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void turn(int dir) {
        int n = Mth.clamp(spread + dir, 0, spreads() - 1);
        if (n != spread) {
            spread = n;
            if (minecraft != null) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0F));
            }
        }
    }

    /** For the capture stand: turn to the last spread. */
    public void lastSpread() {
        spread = spreads() - 1;
    }
}
