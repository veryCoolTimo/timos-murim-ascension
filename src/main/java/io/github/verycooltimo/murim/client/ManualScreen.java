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
    private static final ResourceLocation COVER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/gui/book/cover_huashan.png");
    private static final int INK = 0xFF1B1612;
    private static final int FADED = 0xFF4A3F35;
    private static final int SEAL = 0xFFB3262C;
    private static final int SEAL_DARK = 0xFF7E1A1F;
    private static final int SEAL_TEXT = 0xFFF3E6D0;

    /** Тики: обложка появляется, держится, раскрывается за OPEN тиков. */
    private static final float COVER_IN = 5.0F;
    private static final float HOLD = 9.0F;
    private static final float OPEN = 8.0F;
    private static final float TURN = 5.0F;
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
    private float turnAt = -100.0F;
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
    }

    @Override
    public void tick() {
        ticks++;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private boolean opened(float t) {
        return t >= COVER_IN + HOLD + OPEN;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        float t = ticks + partial;
        g.pose().pushPose();
        g.pose().translate(bx, by, 0.0F);
        g.pose().scale(k, k, 1.0F);
        float spine = PAGE_W;
        if (t < COVER_IN + HOLD) {
            // Закрытая книга на месте правой страницы, лёгкое «опускание» на стол.
            float in = Mth.clamp(t / COVER_IN, 0.0F, 1.0F);
            float s = 0.92F + 0.08F * (1.0F - (1.0F - in) * (1.0F - in));
            g.pose().pushPose();
            g.pose().translate(spine + PAGE_W / 2.0F, TEX_H / 2.0F, 0.0F);
            g.pose().scale(s, s, 1.0F);
            g.pose().translate(-(spine + PAGE_W / 2.0F), -TEX_H / 2.0F, 0.0F);
            g.setColor(1.0F, 1.0F, 1.0F, in);
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            g.blit(COVER, (int) spine, 0, 0.0F, 0.0F, PAGE_W, TEX_H, PAGE_W, TEX_H);
            g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            g.pose().popPose();
        } else if (!opened(t)) {
            // Раскрытие: правая страница видна сразу, обложка «ложится» к корешку, из-за него
            // разворачивается левая страница (сжатие по X вокруг корешка — 2D-поворот).
            float u = Mth.clamp((t - COVER_IN - HOLD) / OPEN, 0.0F, 1.0F);
            u = u * u * (3.0F - 2.0F * u);
            spread(g, 1, false, true, mouseX, mouseY);
            if (u < 0.5F) {
                float sx = 1.0F - u * 2.0F;
                g.pose().pushPose();
                g.pose().translate(spine, 0.0F, 0.0F);
                g.pose().scale(sx, 1.0F, 1.0F);
                g.pose().translate(-spine, 0.0F, 0.0F);
                g.blit(COVER, (int) spine, 0, 0.0F, 0.0F, PAGE_W, TEX_H, PAGE_W, TEX_H);
                g.pose().popPose();
            } else {
                float sx = (u - 0.5F) * 2.0F;
                g.pose().pushPose();
                g.pose().translate(spine, 0.0F, 0.0F);
                g.pose().scale(sx, 1.0F, 1.0F);
                g.pose().translate(-spine, 0.0F, 0.0F);
                spread(g, 0, true, false, mouseX, mouseY);
                g.pose().popPose();
            }
        } else {
            // Перелистывание: новая страница проявляется за TURN тиков.
            spread(g, 2, true, true, mouseX, mouseY);
            float since = t - turnAt;
            if (since >= 0.0F && since < TURN) {
                g.fill(0, 0, TEX_W, TEX_H, ((int) ((1.0F - since / TURN) * 0xB0) << 24) | 0xE9DDC4);
            }
        }
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
            if (pages > 1 && which == 2) {
                corners(g, mouseX, mouseY);
            }
        }
    }

    /** Загнутые уголки: правый нижний — дальше, левый нижний — назад. */
    private void corners(GuiGraphics g, int mouseX, int mouseY) {
        float tx = (mouseX - bx) / k;
        float ty = (mouseY - by) / k;
        if (page < pages - 1) {
            boolean hot = tx > TEX_W - 22 && ty > TEX_H - 22;
            corner(g, TEX_W - 6, TEX_H - 6, -1, hot);
        }
        if (page > 0) {
            boolean hot = tx < 22 && ty > TEX_H - 22;
            corner(g, 6, TEX_H - 6, 1, hot);
        }
    }

    private void corner(GuiGraphics g, int x, int y, int dir, boolean hot) {
        int n = hot ? 14 : 11;
        for (int i = 0; i < n; i++) {
            int x0 = dir < 0 ? x - (n - i) : x;
            int x1 = dir < 0 ? x : x + (n - i);
            g.fill(x0, y - i - 1, x1, y - i, i == 0 || i == n - 1 ? FADED : 0xFFDDCDAE);
        }
        g.fill(dir < 0 ? x - n : x, y - n, dir < 0 ? x : x + n, y - n + 1, FADED);
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
        double tx = (mouseX - bx) / k;
        double ty = (mouseY - by) / k;
        if (button == 0 && opened(ticks) && ty > TEX_H - 24) {
            if (tx > TEX_W - 24 && page < pages - 1) {
                turn(1);
                return true;
            }
            if (tx < 24 && page > 0) {
                turn(-1);
                return true;
            }
        }
        if (button == 0 && !opened(ticks)) {
            // Нетерпеливый клик — сразу раскрыть.
            ticks = (int) (COVER_IN + HOLD + OPEN);
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
        int next = Mth.clamp(page + dir, 0, pages - 1);
        if (next != page) {
            page = next;
            turnAt = ticks;
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
