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
    /**
     * Книга стиля (автор 03.10): страница 0 — титул-иллюстрация, дальше по форме на страницу,
     * изучаются по очереди. У обычной техники — одна форма, она же сама техника.
     */
    private final List<ResourceLocation> forms;
    /** Развороты: {@code null} в списке форм — титул стиля. */
    private final List<ResourceLocation> spreads = new java.util.ArrayList<>();
    private ResourceLocation styleId;
    private int page;
    private int ticks;
    private net.minecraft.client.gui.screens.inventory.PageButton prev;
    private net.minecraft.client.gui.screens.inventory.PageButton next;
    private int bx;
    private int by;
    private float k;

    private Component styleTitle;

    private void setTitleFromStyle(io.github.verycooltimo.murim.technique.Styles.Style s) {
        styleTitle = Component.translatable(s.nameKey());
    }


    private static ResourceLocation pageTex(ResourceLocation f) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/gui/book/pages/" + f.getPath() + ".png");
    }

    private static ResourceLocation titleOf(ResourceLocation style) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, style.getPath() + "_title");
    }

    private static boolean hasPage(ResourceLocation f) {
        return net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(pageTex(f)).isPresent();
    }

    /** Форма текущего разворота; на титуле — первая форма (для подписи не используется). */
    private ResourceLocation form() {
        ResourceLocation f = spreads.get(page);
        return f != null ? f : forms.get(0);
    }

    private boolean titlePage() {
        return spreads.get(page) == null;
    }

    /** Состояние формы: 2 — изучена, 1 — можно изучить, 0 — закрыта (нужна предыдущая). */
    private int state(ResourceLocation f) {
        if (io.github.verycooltimo.murim.client.TechniqueSlotsHud.mastery(f) != null) {
            return 2;
        }
        java.util.Optional<ResourceLocation> prevForm = io.github.verycooltimo.murim.technique.Styles.previous(f);
        if (prevForm.isEmpty()) {
            return 1;
        }
        io.github.verycooltimo.murim.network.SyncMasteryPayload.Entry e = io.github.verycooltimo.murim.client.TechniqueSlotsHud.mastery(prevForm.get());
        return e != null && e.layer() >= io.github.verycooltimo.murim.technique.Styles.NEXT_FORM_LAYER ? 1 : 0;
    }

    public static void open(ManualPayloads.Open open) {
        net.minecraft.client.Minecraft.getInstance().setScreen(new ManualScreen(open));
    }

    public ManualScreen(ManualPayloads.Open open) {
        super(io.github.verycooltimo.murim.mastery.MasteryService.name(open.technique()));
        this.open = open;
        java.util.Optional<io.github.verycooltimo.murim.technique.Styles.Style> style =
                io.github.verycooltimo.murim.technique.Styles.of(open.technique());
        // Автор 03.10: в каждом развороте слева — своя картинка формы, справа — объяснение.
        // Книга с картинками — если у первой формы есть страница-иллюстрация.
        this.forms = style.isPresent() ? style.get().forms() : List.of(open.technique());
        this.illustrated = hasPage(forms.get(0));
        if (illustrated && style.isPresent()) {
            styleId = style.get().id();
            setTitleFromStyle(style.get());
            if (styleId.getPath().equals("seven_plum") || hasPage(titleOf(styleId))) {
                spreads.add(null);
            }
        }
        if (illustrated) {
            spreads.addAll(forms);
        } else {
            spreads.add(open.technique());
        }
        this.pages = spreads.size();
    }

    @Override
    protected void init() {
        // Автор 03.10: «слишком огромная» — разворот ~60 % ширины экрана.
        k = Math.min(width * 0.6F / TEX_W, height * 0.72F / TEX_H);
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
        if (titlePage() && styleId != null && styleId.getPath().equals("seven_plum")) {
            // Титул Семи Цветков — одобренный разворот целиком.
            g.blit(PLUM, 0, 0, 0.0F, 0.0F, TEX_W, TEX_H, TEX_W, TEX_H);
            g.pose().pushPose();
            g.pose().translate(214, 236, 0.0F);
            g.pose().scale(0.6F, 0.6F, 1.0F);
            g.drawString(font, styleTitle, 0, 0, INK, false);
            g.pose().popPose();
            return;
        }
        // Слева — картинка формы (или титул стиля), иначе шаблон секты.
        ResourceLocation leftTex = titlePage() ? pageTex(titleOf(styleId)) : illustrated ? pageTex(form()) : null;
        if (leftTex != null) {
            g.blit(leftTex, 0, 0, 0.0F, 0.0F, PAGE_W, TEX_H, PAGE_W, TEX_H);
        } else {
            g.blit(BASIC, 0, 0, 0.0F, 0.0F, PAGE_W, TEX_H, TEX_W, TEX_H);
        }
        g.blit(BASIC, PAGE_W, 0, PAGE_W, 0.0F, PAGE_W, TEX_H, TEX_W, TEX_H);
        if (titlePage()) {
            styleIntro(g);
        } else {
            text(g, 214, 24, 144, 176, 0.8F);
            seal(g, mouseX, mouseY);
        }
    }

    /** Правая страница титула: название стиля и вступление. */
    private void styleIntro(GuiGraphics g) {
        String key = "book.murim.style." + styleId.getPath();
        g.pose().pushPose();
        g.pose().translate(214, 24, 0.0F);
        g.pose().scale(0.8F, 0.8F, 1.0F);
        int lw = (int) (144 / 0.8F);
        int cy = 0;
        for (FormattedCharSequence line : font.split(styleTitle, lw)) {
            g.drawString(font, line, 0, cy, INK, false);
            cy += font.lineHeight + 1;
        }
        cy += 4;
        if (I18n.exists(key)) {
            for (FormattedCharSequence line : font.split(FormattedText.of(I18n.get(key)), lw)) {
                g.drawString(font, line, 0, cy, FADED, false);
                cy += font.lineHeight + 1;
            }
        }
        g.pose().popPose();
    }

    /** Красная печать «Изучить»: при наведении темнее и «вдавлена» на пиксель. */
    private void seal(GuiGraphics g, int mouseX, int mouseY) {
        int st = state(form());
        if (st != 1) {
            // Изучено — бледный оттиск; закрыто — подпись, что нужно сначала.
            Component note = st == 2 ? Component.translatable("murim.manual.learned")
                    : Component.translatable("murim.manual.locked",
                            io.github.verycooltimo.murim.mastery.MasteryService.name(io.github.verycooltimo.murim.technique.Styles.previous(form()).orElse(form())),
                            io.github.verycooltimo.murim.technique.Styles.NEXT_FORM_LAYER);
            g.pose().pushPose();
            g.pose().translate(214, SEAL_Y + 4, 0.0F);
            g.pose().scale(0.6F, 0.6F, 1.0F);
            int ly = 0;
            for (FormattedCharSequence line : font.split(note, (int) (140 / 0.6F))) {
                g.drawString(font, line, 0, ly, st == 2 ? 0xFF8A7C6A : SEAL_DARK, false);
                ly += font.lineHeight + 1;
            }
            g.pose().popPose();
            return;
        }
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
        return opened(ticks) && !titlePage() && state(form()) == 1
                && tx >= SEAL_X && tx <= SEAL_X + SEAL_W && ty >= SEAL_Y && ty <= SEAL_Y + SEAL_H;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && overSeal(mouseX, mouseY)) {
            PacketDistributor.sendToServer(new ManualPayloads.Learn(open.mainHand(), form()));
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
        String path = form().getPath();
        // У иллюстрированных форм на странице текста — полный трактат (.full), подпись — на иллюстрации.
        String key = I18n.exists("book.murim." + path + ".full") ? "book.murim." + path + ".full" : "book.murim." + path;
        String body = I18n.exists(key) ? I18n.get(key)
                : I18n.exists("technique.murim." + path + ".weakness") ? I18n.get("technique.murim." + path + ".weakness") : "";
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(scale, scale, 1.0F);
        int lw = (int) (w / scale);
        int cy = 0;
        for (FormattedCharSequence line : font.split(io.github.verycooltimo.murim.mastery.MasteryService.name(form()), lw)) {
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
