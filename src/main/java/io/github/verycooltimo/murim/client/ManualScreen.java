package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.ManualPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Книга-манускрипт (автор 03.10): прочитать, потом «Изучить». Пиксель-арт
 * (docs/design/reference/ui/books-v2-pixel). Сначала закрытая обложка, она раскрывается вокруг
 * корешка; обычные техники — один разворот-шаблон секты с текстом; формы Семи Цветков и 24
 * Движений — разворот-иллюстрация, затем разворот с полным текстом. «Изучить» — красная
 * печать на странице, не кнопка интерфейса.
 */
public final class ManualScreen extends Screen {

    private static final int TEX_W = 384;
    private static final int TEX_H = 256;
    private static final int PAGE_W = TEX_W / 2;
    private static final ResourceLocation BASIC = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/gui/book/basic_huashan.png");
    private static final ResourceLocation PLUM = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/gui/book/seven_plum.png");
    private static final int INK = 0xFF1B1612;
    private static final int FADED = 0xFF4A3F35;
    private static final int SEAL = 0xFFB3262C;
    private static final int SEAL_DARK = 0xFF7E1A1F;
    private static final int SEAL_TEXT = 0xFFF3E6D0;

    /** Печать «Изучить» на правой странице (координаты текстуры 384×256). */
    private static final int SEAL_X = 284;
    private static final int SEAL_Y = 206;
    private static final int SEAL_W = 56;
    private static final int SEAL_H = 22;

    private final ManualPayloads.Open open;
    private final boolean illustrated;
    private final int pages;
    private int page;
    private int ticks;
    private net.minecraft.client.gui.screens.inventory.PageButton prev;
    private net.minecraft.client.gui.screens.inventory.PageButton next;
    private int bx;
    private int by;
    private float k;

    public static void open(ManualPayloads.Open open) {
        net.minecraft.client.Minecraft.getInstance().setScreen(new ManualScreen(open));
    }

    public ManualScreen(ManualPayloads.Open open) {
        super(io.github.verycooltimo.murim.mastery.MasteryService.name(open.technique()));
        this.open = open;
        String path = open.technique().getPath();
        this.illustrated = path.startsWith("seven_plum") && !path.equals("seven_plum_basic") || path.startsWith("twenty_four_plum");
        this.pages = illustrated ? 2 : 1;
    }

    @Override
    protected void init() {
        k = Math.min((width - 16) / (float) TEX_W, (height - 16) / (float) TEX_H);
        if (k >= 1.0F) {
            k = (float) Math.floor(k);
        }
        bx = (int) ((width - TEX_W * k) / 2);
        by = (int) ((height - TEX_H * k) / 2);
        if (pages > 1) {
            int y = (int) (by + TEX_H * k) - 18;
            // API: net.minecraft.client.gui.screens.inventory.PageButton (ванильные стрелки книги).
            prev = addRenderableWidget(new net.minecraft.client.gui.screens.inventory.PageButton(bx + 8, y, false, b -> turn(-1), true));
            next = addRenderableWidget(new net.minecraft.client.gui.screens.inventory.PageButton((int) (bx + TEX_W * k) - 31, y, true, b -> turn(1), true));
            prev.visible = false;
            next.visible = false;
        }
    }

    @Override
    public void tick() {
        ticks++;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Автор 03.10: без анимации открытия — книга сразу открыта на развороте. */
    private boolean opened(float t) {
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        float t0 = ticks + partial;
        if (prev != null) {
            prev.visible = opened(t0) && page > 0;
        }
        if (next != null) {
            next.visible = opened(t0) && page < pages - 1;
        }
        // Фон экрана рисует книгу (renderBackground), виджеты — поверх неё.
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.renderBackground(g, mouseX, mouseY, partial);
        float t = ticks + partial;
        g.pose().pushPose();
        g.pose().translate(bx, by, 0.0F);
        g.pose().scale(k, k, 1.0F);
        spread(g, 2, true, true, mouseX, mouseY);
        g.pose().popPose();
    }

    /**
     * Текущий разворот: {@code which} 0 — только левая страница, 1 — только правая, 2 — обе.
     */
    private void spread(GuiGraphics g, int which, boolean left, boolean right, int mouseX, int mouseY) {
        boolean textPage = !illustrated || page == 1;
        ResourceLocation tex = textPage ? BASIC : PLUM;
        if (left) {
            g.blit(tex, 0, 0, 0.0F, 0.0F, PAGE_W, TEX_H, TEX_W, TEX_H);
        }
        if (right) {
            g.blit(tex, PAGE_W, 0, PAGE_W, 0.0F, PAGE_W, TEX_H, TEX_W, TEX_H);
            if (textPage) {
                text(g, 214, 24, 144, 176, 0.8F);
                seal(g, mouseX, mouseY);
            } else {
                g.pose().pushPose();
                g.pose().translate(214, 236, 0.0F);
                g.pose().scale(0.6F, 0.6F, 1.0F);
                g.drawString(font, title, 0, 0, INK, false);
                g.pose().popPose();
            }
        }
    }

    /** Красная печать «Изучить»: при наведении темнее и «вдавлена» на пиксель. */
    private void seal(GuiGraphics g, int mouseX, int mouseY) {
        boolean hot = overSeal(mouseX, mouseY);
        int x = SEAL_X + (hot ? 1 : 0);
        int y = SEAL_Y + (hot ? 1 : 0);
        int base = hot ? SEAL_DARK : SEAL;
        // Неровный край оттиска: срезанные углы и пара «пропусков» краски.
        g.fill(x + 1, y, x + SEAL_W - 1, y + SEAL_H, base);
        g.fill(x, y + 1, x + SEAL_W, y + SEAL_H - 1, base);
        g.fill(x + 2, y + 2, x + SEAL_W - 2, y + 3, SEAL_TEXT & 0x60FFFFFF);
        g.fill(x + 2, y + SEAL_H - 3, x + SEAL_W - 2, y + SEAL_H - 2, SEAL_TEXT & 0x60FFFFFF);
        g.fill(x + 7, y, x + 9, y + 1, 0x00000000);
        Component label = Component.translatable("murim.manual.learn");
        g.pose().pushPose();
        float s = 0.75F;
        g.pose().translate(x + SEAL_W / 2.0F - font.width(label) * s / 2.0F, y + SEAL_H / 2.0F - 3.0F, 0.0F);
        g.pose().scale(s, s, 1.0F);
        g.drawString(font, label, 0, 0, SEAL_TEXT, false);
        g.pose().popPose();
    }

    private boolean overSeal(double mouseX, double mouseY) {
        double tx = (mouseX - bx) / k;
        double ty = (mouseY - by) / k;
        return opened(ticks) && (!illustrated || page == 1)
                && tx >= SEAL_X && tx <= SEAL_X + SEAL_W && ty >= SEAL_Y && ty <= SEAL_Y + SEAL_H;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && overSeal(mouseX, mouseY)) {
            PacketDistributor.sendToServer(new ManualPayloads.Learn(open.mainHand()));
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (opened(ticks) && scrollY != 0.0D) {
            turn(scrollY < 0.0D ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void turn(int dir) {
        int n = Mth.clamp(page + dir, 0, pages - 1);
        if (n != page) {
            page = n;
            if (minecraft != null) {
                minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, 1.0F));
            }
        }
    }

    /** Название и текст техники в прямоугольнике страницы (координаты текстуры 384×256). */
    private void text(GuiGraphics g, int x, int y, int w, int h, float scale) {
        String path = open.technique().getPath();
        // У иллюстрированных форм на странице текста — полный трактат (.full), подпись — на иллюстрации.
        String key = I18n.exists("book.murim." + path + ".full") ? "book.murim." + path + ".full" : "book.murim." + path;
        String body = I18n.exists(key) ? I18n.get(key)
                : I18n.exists("technique.murim." + path + ".weakness") ? I18n.get("technique.murim." + path + ".weakness") : "";
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(scale, scale, 1.0F);
        int lw = (int) (w / scale);
        int cy = 0;
        for (FormattedCharSequence line : font.split(title, lw)) {
            g.drawString(font, line, 0, cy, INK, false);
            cy += font.lineHeight + 1;
        }
        if (open.depth() > 0) {
            g.drawString(font, Component.translatable("murim.manual.torn", open.depth()), 0, cy, FADED, false);
            cy += font.lineHeight + 1;
        }
        cy += 4;
        List<FormattedCharSequence> lines = font.split(FormattedText.of(body), lw);
        for (FormattedCharSequence line : lines) {
            if (cy + font.lineHeight > h / scale) {
                break;
            }
            g.drawString(font, line, 0, cy, FADED, false);
            cy += font.lineHeight + 1;
        }
        g.pose().popPose();
    }
}
