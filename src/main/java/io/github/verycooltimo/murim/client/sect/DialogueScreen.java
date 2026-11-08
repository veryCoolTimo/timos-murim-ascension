package io.github.verycooltimo.murim.client.sect;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.DialoguePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Разговор с NPC (автор 03.10: «как в играх — экран, и там NPC стоит, и диалог, а не в виде картинки»):
 * мир виден, камера за плечом ({@link DialogueCamera}), сверху и снизу — тонкие кинополосы, внизу —
 * полупрозрачный свиток в стиле экрана раскладки (тот же пиксель-арт: бумага, бирюзовая парча, валики).
 * На свитке тушью: имя и титул, реплика печатается, справа — список ответов, мышью или клавишами 1–9.
 *
 * <p>Экран ничего не решает: номер варианта уходит серверу, ответ — новая реплика или конец разговора.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = MurimMod.MODID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class DialogueScreen extends Screen {

    private static final ResourceLocation PANEL = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/gui/dialogue_panel.png");
    private static final int PW = 380;
    /** Height follows the wrapped reply rows and the available GUI space (playtest 21.10). */
    private int panelHeight = 114;
    /** Rows of the texture that stay as they are at the top and the bottom; the paper between them stretches. */
    private static final int KEEP = 30;
    private static final int TEX_H = 88;
    /** Поля бумаги внутри свитка (валики 16, парча 10). */
    private static final int IN_X = 22;
    private static final int IN_Y = 13;
    /** Колонка ответов справа (codex 03.10: ~40 % ширины, ряды фиксированной высоты). */
    private static final int OPT_X = 222;
    private static final int OPT_RIGHT = PW - 26;
    /** Reply rows grow to fit every wrapped line and keep four pixels of space between them. */
    private int rowHeight = 13;
    private int rowStep = 18;
    private int maxRows = 5;
    private static final int OPT_TOP = IN_Y + 3;
    /** Масштаб реплики и ответов (codex: текст крупнее, иерархия имя > реплика > титул). */
    private static final float SPEECH = 1.0F;
    private static final float OPTION = 0.9F;

    private static final int INK = 0xFF1B1612;
    private static final int FADED = 0xFF5A4E42;
    private static final int SEAL = 0xFFB3262C;
    private static final int SEAL_TEXT = 0xFFF3E6D0;

    /** Печать текста, символов за тик. */
    private static final float CPS = 1.7F;

    private DialoguePayloads.Open line;
    private String full;
    /** The whole line and its pages: a line that does not fit the scroll at the smallest size is read page by page, never cut. */
    private String whole;
    private java.util.List<String> pages;
    private int page;
    /** Lines the player has already read this session show at once (UX review 21.10: no waiting for a known text). */
    private static final java.util.Set<String> READ = new java.util.HashSet<>();
    private static Object readWorld;
    /** Input right after the text was skipped does not pick an answer: tick until which a choice is ignored. */
    private int guardUntil;
    private int skipKey = -1;
    private float typed;
    private int ticks;
    private int lineTicks;
    private boolean sent;
    private int hover = -1;
    private int lastMouseX = Integer.MIN_VALUE;
    private int lastMouseY = Integer.MIN_VALUE;
    /** Выбранный клавишами стрелок/наведением ответ (подсветка строки). */
    private int focus = -1;
    /** Index of the first scrolling answer shown (the pinned closing option never scrolls). */
    private int scroll;
    private int px;
    private int py;
    private float k = 1.0F;

    private DialogueScreen(DialoguePayloads.Open line) {
        super(line.name());
        setLine(line);
    }

    /** Новая реплика: открыть экран и камеру или продолжить разговор. */
    public static void open(DialoguePayloads.Open payload) {
        Minecraft mc = Minecraft.getInstance();
        if (readWorld != mc.level) {
            READ.clear();
            readWorld = mc.level;
        }
        DialogueCamera.start(payload.npc(), payload.shot().orElse(null));
        if (mc.screen instanceof DialogueScreen ds) {
            ds.setLine(payload);
        } else {
            mc.setScreen(new DialogueScreen(payload));
        }
        io.github.verycooltimo.murim.sect.DialogueStop.stop(mc.player);
        if (!payload.playerAnim().isEmpty() && mc.player != null) {
            io.github.verycooltimo.murim.client.MurimPlayerAnimations.play(mc.player, ResourceLocation.parse(payload.playerAnim()));
        }
    }

    /** Сервер закончил разговор. */
    public static void closeFromServer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DialogueScreen ds) {
            ds.sent = true;
            mc.setScreen(null);
        }
        DialogueCamera.stop();
    }

    private void setLine(DialoguePayloads.Open l) {
        this.line = l;
        if (Boolean.getBoolean("murim.capture") && "dialogue".equals(System.getProperty("murim.capture.technique"))) {
            MurimMod.LOGGER.info("SMOKE-DIALOGUE-NODE line={} options={}", l.line(), l.options());
        }
        this.whole = l.line().getString();
        this.full = whole;
        this.pages = null;
        this.page = 0;
        this.typed = READ.contains(whole) ? full.length() : 0.0F;
        this.guardUntil = 0;
        this.lineTicks = 0;
        this.sent = false;
        this.hover = -1;
        this.focus = -1;
        this.scroll = 0;
        this.typedDoneTick = 0;
        if (font != null) init();
    }

    private boolean typing() {
        return typed < full.length();
    }

    /** Is there a page after this one? Then the answers wait. */
    private boolean morePages() {
        return pages != null && page < pages.size() - 1;
    }

    private static final float MIN_SCALE = SPEECH * 0.75F;

    /** Splits a line that does not fit the room at the smallest size into pages of whole lines. */
    private void ensurePages() {
        if (pages != null || font == null) {
            return;
        }
        int w = line.options().isEmpty() ? PW - 2 * IN_X : OPT_X - IN_X - 10;
        int room = panelHeight - IN_Y - 20 - 9;
        int perPage = Math.max(1, (int) (room / (font.lineHeight * MIN_SCALE)));
        List<FormattedCharSequence> all = font.split(FormattedText.of(whole), (int) (w / MIN_SCALE));
        java.util.List<String> out = new java.util.ArrayList<>();
        if (all.size() <= perPage) {
            out.add(whole);
        } else {
            for (int i = 0; i < all.size(); i += perPage) {
                StringBuilder sb = new StringBuilder();
                for (int j = i; j < Math.min(all.size(), i + perPage); j++) {
                    all.get(j).accept((idx, st, cp) -> {
                        sb.appendCodePoint(cp);
                        return true;
                    });
                    if (j + 1 < Math.min(all.size(), i + perPage)) {
                        sb.append(' ');
                    }
                }
                out.add(sb.toString());
            }
        }
        pages = out;
        full = pages.get(0);
        if (READ.contains(whole)) {
            typed = full.length();
        }
    }

    private void nextPage() {
        page++;
        full = pages.get(page);
        typed = READ.contains(whole) ? full.length() : 0.0F;
        typedDoneTick = READ.contains(whole) ? lineTicks : 0;
        guardUntil = lineTicks + 8;
    }

    @Override
    protected void init() {
        // Source: author playtest 26.10. Grow the paper before hiding any answer.
        k = Math.min(1.0F, (width - 16) / (float) PW);
        px = (int) ((width - PW * k) / 2);
        int textWidth = OPT_RIGHT - OPT_X - 22;
        int[] textHeights = new int[count()];
        for (int i = 0; i < count(); i++) {
            textHeights[i] = (int) Math.ceil(font.split(line.options().get(i), (int) (textWidth / OPTION)).size()
                    * font.lineHeight * OPTION);
        }
        int available = (int) ((height - 2 * bar(1.0F) - 16) / k);
        DialogueLayout layout = DialogueLayout.fit(available, textHeights);
        rowHeight = layout.rowHeight();
        rowStep = rowHeight + 2;
        maxRows = layout.rows();
        panelHeight = layout.panelHeight();
        pages = null;
        page = 0;
        // Свиток целиком над нижней кинополосой.
        py = (int) (height - panelHeight * k - bar(1.0F) - 8);
    }

    /** While the talk is open the movement input is ignored, whatever the cutscene camera does. */
    @net.neoforged.bus.api.SubscribeEvent
    static void onMovementInput(net.neoforged.neoforge.client.event.MovementInputUpdateEvent event) {
        if (!(Minecraft.getInstance().screen instanceof DialogueScreen)) {
            return;
        }
        var input = event.getInput();
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    @Override
    public void tick() {
        io.github.verycooltimo.murim.sect.DialogueStop.stop(minecraft == null ? null : minecraft.player);
        ensurePages();
        ticks++;
        lineTicks++;
        // Первая реплика пишется, когда камера доехала: наезд не перебивает текст (codex 03.10).
        if (typing() && DialogueCamera.amount(0.0F) > 0.9F && DialogueCamera.settled()) {
            int before = (int) typed;
            typed = Math.min(full.length(), typed + CPS);
            if (!typing()) {
                typedDoneTick = lineTicks;
                if (!morePages()) READ.add(whole);
            }
            // Тихий шорох кисти раз в несколько символов — текст «пишется».
            if ((int) typed / 6 != before / 6 && minecraft != null) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.8F, 0.08F));
            }
        }
        if (minecraft != null && minecraft.player != null && minecraft.level != null
                && !(minecraft.level.getEntity(line.npc()) instanceof net.minecraft.world.entity.LivingEntity)) {
            onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Мир не размывается и не затемняется: разговор идёт в кадре. Только кинополосы.
        float a = DialogueCamera.amount(partial);
        int bar = bar(a);
        if (bar > 0) {
            g.fill(0, 0, width, bar, 0xFF000000);
            g.fill(0, height - bar, width, height, 0xFF000000);
        }
    }

    private int bar(float a) {
        return Math.round(height * 0.05F * a);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        ensurePages();
        renderBackground(g, mouseX, mouseY, partial);
        // Свиток проявляется, когда камера почти доехала (codex: не поверх пролёта камеры).
        float appear = Mth.clamp((DialogueCamera.amount(partial) - 0.8F) / 0.2F, 0.0F, 1.0F);
        if (appear <= 0.0F) {
            return;
        }
        g.pose().pushPose();
        // Свиток выезжает снизу на несколько пикселей и проявляется.
        g.pose().translate(px, py + (1.0F - appear) * 10.0F, 0.0F);
        g.pose().scale(k, k, 1.0F);
        RenderSystem.enableBlend();
        g.setColor(1.0F, 1.0F, 1.0F, 0.88F * appear);
        // top and bottom of the scroll as drawn, the plain paper between them stretched to the height
        g.blit(PANEL, 0, 0, PW, KEEP, 0.0F, 0.0F, PW, KEEP, PW, TEX_H);
        g.blit(PANEL, 0, KEEP, PW, panelHeight - 2 * KEEP, 0.0F, KEEP, PW, TEX_H - 2 * KEEP, PW, TEX_H);
        g.blit(PANEL, 0, panelHeight - KEEP, PW, KEEP, 0.0F, TEX_H - KEEP, PW, KEEP, PW, TEX_H);
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        int alpha = Math.max(4, (int) (appear * 255)) << 24;
        speaker(g, alpha);
        speech(g, alpha, partial);
        options(g, mouseX, mouseY, alpha);
        g.pose().popPose();
        RenderSystem.disableBlend();
    }

    /** Имя тушью и титул бледнее; у имени — маленькая красная печать. */
    private void speaker(GuiGraphics g, int alpha) {
        int x = IN_X;
        int y = IN_Y;
        g.fill(x, y + 1, x + 3, y + 8, SEAL & 0x00FFFFFF | alpha);
        g.drawString(font, line.name(), x + 6, y, INK & 0x00FFFFFF | alpha, false);
        if (!line.title().getString().isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(x + 6, y + 10, 0.0F);
            g.pose().scale(0.7F, 0.7F, 1.0F);
            g.drawString(font, line.title(), 0, 0, FADED & 0x00FFFFFF | alpha, false);
            g.pose().popPose();
        }
        // Тонкий мазок туши под именем.
        g.fill(x, y + 17, OPT_X - 12, y + 18, 0x401B1612);
    }

    /** Реплика: печатается посимвольно, перенос по ширине левой колонки. */
    private void speech(GuiGraphics g, int alpha, float partial) {
        int w = (line.options().isEmpty() ? PW - 2 * IN_X : OPT_X - IN_X - 10);
        if (pages != null && pages.size() > 1) {
            // a page counter, so the player knows there is more
            g.drawString(font, (page + 1) + "/" + pages.size(), IN_X + w - font.width((page + 1) + "/" + pages.size()), IN_Y + 9, FADED & 0x00FFFFFF | alpha, false);
        }
        float shown = Math.min(full.length(), typed + (typing() ? CPS * partial : 0.0F));
        String part = full.substring(0, (int) shown);
        float s = SPEECH;
        int room = panelHeight - IN_Y - 20 - 9;
        // Long lines (EN runs ~20 % longer) were cut at the bottom: shrink from the full line, so the size does not jump while typing.
        while (s > SPEECH * 0.75F && font.split(FormattedText.of(full), (int) (w / s)).size() * font.lineHeight * s > room) {
            s -= SPEECH * 0.05F;
        }
        g.pose().pushPose();
        g.pose().translate(IN_X, IN_Y + 20, 0.0F);
        g.pose().scale(s, s, 1.0F);
        List<FormattedCharSequence> lines = font.split(FormattedText.of(part), (int) (w / s));
        int y = 0;
        int max = (int) ((panelHeight - IN_Y - 20 - 9) / s);
        for (FormattedCharSequence l : lines) {
            if (y + font.lineHeight > max) {
                break;
            }
            g.drawString(font, l, 0, y, INK & 0x00FFFFFF | alpha, false);
            y += font.lineHeight;
        }
        g.pose().popPose();
        if (!typing() && (line.options().isEmpty() || morePages()) && (ticks / 10) % 2 == 0) {
            g.drawString(font, "▾", PW - IN_X - 6, panelHeight - 20, FADED & 0x00FFFFFF | alpha, false);
        }
    }

    // ------------------------------------------------------------------ the answers

    private int count() {
        return line.options().size();
    }

    /** The last option is the pinned way out. */
    private boolean hasCloser() {
        return line.closer() && count() > 1;
    }

    /** How many scrolling answers fit: all of them, or the rows minus the pinned closing one. */
    private int windowSize() {
        int n = count();
        if (n <= maxRows) {
            return n;
        }
        return hasCloser() ? maxRows - 1 : maxRows;
    }

    /** Scrolling answers (without the pinned closing one). */
    private int scrolling() {
        return hasCloser() && count() > maxRows ? count() - 1 : count();
    }

    private boolean overflow() {
        return count() > maxRows;
    }

    private int maxScroll() {
        return Math.max(0, scrolling() - windowSize());
    }

    /** The option shown in a slot (row from the top), or -1. */
    private int optionInSlot(int slot) {
        int n = count();
        if (!overflow()) {
            return slot < n ? slot : -1;
        }
        int w = windowSize();
        if (slot < w) {
            return scroll + slot;
        }
        return hasCloser() && slot == w ? n - 1 : -1;
    }

    private int rows() {
        return Math.min(count(), Math.min(9, maxRows));
    }

    private void showFocus() {
        if (!overflow() || focus < 0 || (hasCloser() && focus == count() - 1)) {
            return;
        }
        int w = windowSize();
        if (focus < scroll) {
            scroll = focus;
        } else if (focus >= scroll + w) {
            scroll = focus - w + 1;
        }
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    /** The key that picks an answer: 1..9 in order, 0 for the closing one. Nothing past the ninth. */
    private String badge(int i) {
        if (hasCloser() && i == count() - 1) {
            return "0";
        }
        int slot = i - (overflow() ? scroll : 0);
        return slot >= 0 && slot < 9 ? String.valueOf(slot + 1) : "";
    }

    /** Answers on the right: every one a button of its own; the focused one darker with a seal bar; the closing one pinned below a hairline. */
    private void options(GuiGraphics g, int mouseX, int mouseY, int alpha) {
        if (count() == 0) {
            return;
        }
        // A vertical brush stroke between the line and the answers.
        g.fill(OPT_X - 8, IN_Y + 2, OPT_X - 7, panelHeight - 14, 0x30000000);
        float fade = morePages() ? 0.0F : typing() ? 1.0F : Mth.clamp((lineTicks - typedDoneTick) / 5.0F, 0.0F, 1.0F);
        if (fade <= 0.0F) {
            return;
        }
        int oa = (int) (fade * (alpha >>> 24)) << 24;
        if (focus < 0) {
            // The first answer is in focus at the start: Enter/E work at once, as in an RPG.
            focus = 0;
        }
        int at = optionAt(mouseX, mouseY);
        if (mouseX != lastMouseX || mouseY != lastMouseY) {
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            hover = at;
            if (at >= 0) {
                focus = at;
            }
        }
        for (int slot = 0; slot < rows(); slot++) {
            int i = optionInSlot(slot);
            if (i < 0) {
                continue;
            }
            int y = OPT_TOP + slot * rowStep;
            boolean hot = i == focus;
            boolean closer = hasCloser() && i == count() - 1;
            if (closer && slot > 0) {
                // the hairline above the way out
                g.fill(OPT_X, y - 2, OPT_RIGHT, y - 1, 0x601B1612 & 0x00FFFFFF | (int) (0x60 * fade) << 24);
            }
            // the button: paper a shade darker than the scroll, so two rows never run together; the focused one darker still
            g.fill(OPT_X, y, OPT_RIGHT, y + rowHeight, (int) ((hot ? 0x3A : closer ? 0x10 : 0x1E) * fade) << 24 | 0x1B1612);
            g.fill(OPT_X, y + rowHeight - 1, OPT_RIGHT, y + rowHeight, (int) (0x2A * fade) << 24 | 0x1B1612);
            if (hot) {
                g.fill(OPT_X, y, OPT_X + 2, y + rowHeight, SEAL & 0x00FFFFFF | oa);
            }
            String num = badge(i);
            int tx = OPT_X + 5;
            if (!num.isEmpty()) {
                g.fill(tx, y + 3, tx + 10, y + 13, (hot ? 0x7E1A1F : closer ? 0x5A4E42 : SEAL & 0x00FFFFFF) | oa);
                g.pose().pushPose();
                g.pose().translate(tx + 3.0F, y + 4.5F, 0.0F);
                g.pose().scale(0.9F, 0.9F, 1.0F);
                g.drawString(font, num, 0, 0, SEAL_TEXT & 0x00FFFFFF | oa, false);
                g.pose().popPose();
            }
            tx += 14;
            int w = OPT_RIGHT - tx - 3;
            float sc = OPTION;
            List<FormattedCharSequence> wrapped = font.split(line.options().get(i), (int) (w / sc));
            float textH = wrapped.size() * font.lineHeight * sc;
            g.pose().pushPose();
            g.pose().translate(tx, y + (rowHeight - textH) / 2.0F, 0.0F);
            g.pose().scale(sc, sc, 1.0F);
            int col = closer ? FADED : INK;
            for (int j = 0; j < wrapped.size(); j++) {
                g.drawString(font, wrapped.get(j), 0, j * font.lineHeight, col & 0x00FFFFFF | oa, false);
            }
            g.pose().popPose();
        }
        if (overflow()) {
            scrollbar(g, oa, fade);
        }
    }

    /** A thin track with a thumb and an arrow at each end that still has more to show. */
    private void scrollbar(GuiGraphics g, int oa, float fade) {
        int x = OPT_RIGHT + 3;
        int top = OPT_TOP;
        int h = (windowSize()) * rowStep - 2;
        g.fill(x, top, x + 5, top + h, (int) (0x28 * fade) << 24 | 0x1B1612);
        int total = scrolling();
        int thumb = Math.max(8, h * windowSize() / total);
        int ty = top + (maxScroll() == 0 ? 0 : (h - thumb) * scroll / maxScroll());
        g.fill(x, ty, x + 5, ty + thumb, SEAL & 0x00FFFFFF | oa);
        if (scroll > 0) {
            g.drawString(font, "\u25B2", x - 1, top - 8, FADED & 0x00FFFFFF | oa, false);
        }
        if (scroll < maxScroll()) {
            g.pose().pushPose();
            g.pose().translate(OPT_RIGHT, panelHeight - 16, 0.0F);
            Component more = Component.translatable("dialogue.murim.general.more_down", maxScroll() - scroll);
            g.drawString(font, more, -font.width(more), 0, FADED & 0x00FFFFFF | oa, false);
            g.pose().popPose();
        }
    }

    private int typedDoneTick;

    private int optionAt(double mouseX, double mouseY) {
        double tx = (mouseX - px) / k;
        double ty = (mouseY - py) / k;
        if (morePages() || count() == 0 || tx < OPT_X || tx > OPT_RIGHT) {
            return -1;
        }
        for (int slot = 0; slot < rows(); slot++) {
            int y = OPT_TOP + slot * rowStep;
            if (ty >= y && ty < y + rowHeight) {
                return optionInSlot(slot);
            }
        }
        return -1;
    }

    private void skipTyping() {
        typed = full.length();
        typedDoneTick = lineTicks;
        guardUntil = lineTicks + 8;
        if (!morePages()) READ.add(whole);
    }

    private void choose(int i) {
        if (sent) {
            return;
        }
        boolean skipped = false;
        if (typing()) {
            skipTyping();
            // A click on an answer while the line is still printing is the choice itself, not only "skip": one click, one choice
            // (author 22.10: the first click was eaten and only the second worked). skipTyping() arms the guard against a
            // second, stray click; it must not swallow this very click, so only the first instant of a new line still waits.
            if (i < 0 || count() == 0 || morePages()) {
                return;
            }
            skipped = true;
        }
        if (morePages()) {
            if (DialogueClick.live(skipped, lineTicks, guardUntil)) {
                nextPage();
            }
            return;
        }
        if (count() == 0) {
            if (!DialogueClick.live(skipped, lineTicks, guardUntil)) {
                return;
            }
            // A line without answers: a click ends the talk.
            sent = true;
            PacketDistributor.sendToServer(new DialoguePayloads.Choose(-1));
            return;
        }
        if (i < 0 || i >= count()) {
            return;
        }
        if (!DialogueClick.live(skipped, lineTicks, guardUntil)) {
            return;
        }
        sent = true;
        if (minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.4F, 0.25F));
        }
        PacketDistributor.sendToServer(new DialoguePayloads.Choose(i));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int at = optionAt(mouseX, mouseY);
            choose(count() == 0 || morePages() ? 0 : at);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (overflow()) {
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == skipKey) return true;
        int digit = key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9 ? key - GLFW.GLFW_KEY_0
                : key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9 ? key - GLFW.GLFW_KEY_KP_0 : -1;
        if (digit >= 0) {
            if (typing()) skipKey = key;
            if (digit == 0) {
                if (hasCloser()) {
                    choose(count() - 1);
                }
            } else {
                int visible = digit <= rows() ? optionInSlot(digit - 1) : -1;
                if (visible >= 0) choose(visible);
            }
            return true;
        }
        int n = count();
        if ((key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_S) && n > 0 && !morePages()) {
            focus = (focus + 1) % n;
            showFocus();
            return true;
        }
        if ((key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_W) && n > 0 && !morePages()) {
            focus = focus <= 0 ? n - 1 : focus - 1;
            showFocus();
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN && overflow()) {
            scroll = Mth.clamp(scroll + windowSize(), 0, maxScroll());
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_UP && overflow()) {
            scroll = Mth.clamp(scroll - windowSize(), 0, maxScroll());
            return true;
        }
        if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_E) {
            if (typing()) {
                // reading only: the key that skips the text never also picks an answer (it is guarded for a moment after)
                skipKey = key;
                skipTyping();
            } else if (n == 0 || morePages()) {
                choose(0);
            } else if (focus >= 0) {
                choose(focus);
            }
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    // API: NeoForge 1.21.1 patches/net/minecraft/client/gui/screens/options/controls/KeyBindsScreen.java.patch#keyReleased
    @Override
    public boolean keyReleased(int key, int scan, int modifiers) {
        if (key == skipKey) skipKey = -1;
        return super.keyReleased(key, scan, modifiers);
    }

    @Override
    public void onClose() {
        if (!sent) {
            sent = true;
            PacketDistributor.sendToServer(new DialoguePayloads.Choose(-1));
        }
        DialogueCamera.stop();
        super.onClose();
    }

    @Override
    public void removed() {
        // Экран сменили (смерть, другой экран) — камера возвращается в глаза.
        if (!(Minecraft.getInstance().screen instanceof DialogueScreen)) {
            DialogueCamera.stop();
        }
    }

    /** Для стенда: печать закончена и ответы видны. */
    public boolean ready() {
        return !typing();
    }

    /** For the stand: move the focus down (positive) or up, as the arrow keys do. */
    public void nudge(int by) {
        if (count() > 0 && !morePages()) {
            focus = Mth.clamp((focus < 0 ? 0 : focus) + by, 0, count() - 1);
            showFocus();
        }
    }

    /** For the stand: scroll the list by rows, as the mouse wheel does. */
    public void wheel(int rows) {
        scroll = Mth.clamp(scroll + rows, 0, maxScroll());
    }

    // Capture compares payloads: redisplaying the same line/options does not acknowledge a new node.
    Object captureLine() { return line; }

    boolean captureReady() {
        if (!sent && !typing() && lineTicks >= guardUntil && morePages()) choose(-1);
        return !sent && !typing() && !morePages() && lineTicks >= guardUntil;
    }

    int captureAnswer(String key) {
        for (int i = 0; i < count(); i++) {
            if (line.options().get(i).getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                    && text.getKey().equals(key)) return i;
        }
        return -1;
    }

    /** Для стенда: выбрать ответ как клавишей. */
    public void press(int i) {
        choose(i);
    }
}
