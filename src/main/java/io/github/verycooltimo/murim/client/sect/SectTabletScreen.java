package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.network.SectTabletData;
import io.github.verycooltimo.murim.network.SectTabletPayload;
import io.github.verycooltimo.murim.network.SectTabletView;
import io.github.verycooltimo.murim.sect.SectSchedule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/** World-visible tablet and board. Layout source: docs/design/reviews/ui-review-1101.md.
 * API: reference/minecraft-src/net/minecraft/client/gui/{GuiGraphics,Font}.java;
 * screens/Screen.java#renderBackground, #onClose; components/events/GuiEventListener.java#mouseScrolled.
 * Verified against /tmp/mr-ref-1121/minecraft-src; Font#plainSubstrByWidth, #width, #split;
 * ResourceLocation#fromNamespaceAndPath; Component#literal, #getString.
 * All coordinates are GUI pixels; text is never transformed or scaled down. */
public final class SectTabletScreen extends Screen {
    private static final int INK = 0xFF1C241E;
    private static final int MUTED = 0xFF455548;
    private static final int PAPER = 0xE6E3EDE2;
    private static final int ROW = 0x18365F49;
    private static final int BAR_TEXT = 0xFFF2F7EE;
    private static final int BAR = 0xFF244C3C;
    private final List<Integer> rowStops = new ArrayList<>();
    private static final int DONE = 0xFF247045;
    private static final SectTabletView.Tab[] TABS = SectTabletView.Tab.values();
    private SectTabletData data;
    private final boolean board;
    private boolean frozen;
    private boolean replaced;
    private boolean schedule;
    private boolean household;
    private boolean beaten;
    private boolean picking;
    private int tab;
    private int scroll;
    private int ladderScroll = -1;
    private final List<TabletLayout.Rect> ladderRows = new ArrayList<>();
    private int contentHeight;
    private int contentStart;
    private int ticks;
    private int pad;
    private int gap;
    private int bh;
    private int x;
    private int y;
    private int span;
    private double guiScale;
    private TabletLayout.Rect panel;
    private TabletLayout.Rect viewport;
    private List<Component> tip;
    private int mapLabelKey;
    private final List<int[]> mapPlaques = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private record Hit(TabletLayout.Rect rect, Runnable action) { }

    private SectTabletScreen(SectTabletData data, int tab, boolean board) {
        super(t(board ? "board.title" : "title"));
        this.data = data;
        this.tab = tab;
        this.board = board;
    }
    private static boolean capture() { return io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.galleryActive(); }
    private static Component t(String key, Object... args) { return Component.translatable("murim.tablet." + key, args); }

    public static void open(SectTabletData data) { open(data, false); }
    public static void openBoard(SectTabletData data) { open(data, true); }
    private static void open(SectTabletData data, boolean board) {
        Minecraft mc = Minecraft.getInstance();
        ContractTracker.accept(data);
        if (mc.screen instanceof SectTabletScreen old && !old.frozen && old.board == board) {
            old.data = data;
            return;
        }
        if (mc.screen instanceof SectTabletScreen old) old.replaced = true;
        mc.setScreen(new SectTabletScreen(data, 0, board));
    }
    void resume() { replaced = false; send("refresh"); }
    public boolean captureReady(int expectedTab) {
        return !frozen && !board && tab == expectedTab && data.you() != null && data.mapW() > 0
                && (expectedTab != SectTabletView.Tab.STANDING.ordinal() || data.merit() == 37)
                && (expectedTab != SectTabletView.Tab.MAP.ordinal() || SectMapClient.has() && SectMapClient.version() >= data.mapVersion() && data.mapVersion() > 0);
    }
    public static void update(SectTabletData data) {
        ContractTracker.accept(data);
        if (Minecraft.getInstance().screen instanceof SectTabletScreen open && !open.frozen) open.data = data;
    }
    public static void showTab(Screen screen, int tab) {
        if (screen instanceof SectTabletScreen s && s.tab != tab) { s.tab = tab; s.scroll = 0; }
    }
    public static void openForCapture(SectTabletData data, int tab) {
        if (!capture()) return;
        SectTabletScreen s = new SectTabletScreen(data, tab, false);
        s.frozen = true;
        Minecraft.getInstance().setScreen(s);
    }
    public static void openBoardForCapture(SectTabletData data) {
        if (!capture()) return;
        SectTabletScreen s = new SectTabletScreen(data, 0, true);
        s.frozen = true;
        Minecraft.getInstance().setScreen(s);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float partial) {
        // Screen's default background applies blur, and renderTransparentBackground darkens the whole world.
    }
    @Override public void tick() {
        if (++ticks % 40 == 0 && !frozen && !capture()) send("refresh");
    }
    @Override public void removed() {
        if (!replaced && !frozen && !capture()) send("close");
        super.removed();
    }
    @Override public void onClose() { SectJourneyHud.pulse(); super.onClose(); }
    private void send(String action) {
        if (Minecraft.getInstance().player != null && !capture()) PacketDistributor.sendToServer(new SectTabletPayload.Act(action));
    }
    private void act(String op, int slot, String target) {
        if (Minecraft.getInstance().player != null && !capture()) PacketDistributor.sendToServer(new SectTabletPayload.Contract(op, slot, target));
    }

    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        // Gallery cursor is parked before the frame; ignore stale native mouse callbacks.
        if (capture()) { mx = 0; my = 0; }
        guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        panel = TabletLayout.panel(width, height, board);
        pad = TabletLayout.padding(guiScale);
        gap = TabletLayout.gap(guiScale);
        bh = board ? Math.max(24, TabletLayout.button(guiScale)) : TabletLayout.button(guiScale);
        int edge = TabletLayout.frameEdge(panel, guiScale);
        int crown = TabletLayout.frameCrown(panel, guiScale);
        viewport = TabletLayout.viewport(panel, guiScale, board);
        hits.clear();
        ladderRows.clear();
        rowStops.clear();
        tip = null;
        sprite(g, "frame", panel.x(), panel.y(), panel.width(), panel.height());
        if (!board) g.fill(panel.x() + edge, panel.y() + crown, panel.right() - edge, panel.bottom() - pad, PAPER);
        if (board) {
            sprite(g, "header", viewport.x(), panel.y() + 12, viewport.width(), 46);
            sprite(g, "pin", viewport.x() + 3, panel.y() + 12, 5, 5);
            boardHelp(g, mx, my);
        }
        Component header = board ? t("board.title") : t("tab." + TABS[tab].id());
        String deadline = (board || tab == 2) ? t("board.deadline", minutesLeft()).getString() : "";
        int deadlineWidth = font.width(deadline);
        int headerY = board ? panel.y() + 20 : panel.y() + crown;
        if (board) {
            int helpWidth = font.width(t("board.help")) + 20;
            boardTitle(g, header, viewport.x() + helpWidth, headerY, viewport.width() - helpWidth - 8);
        }
        else g.drawString(font, font.plainSubstrByWidth(header.getString(), Math.max(1, viewport.width() - deadlineWidth - gap)),
                viewport.x(), headerY, DONE, false);
        if (tip == null && new TabletLayout.Rect(viewport.x(), headerY, viewport.width(), board ? 30 : 10).contains(mx, my)) tip = List.of(header);
        g.drawString(font, deadline, viewport.right() - deadlineWidth - (board ? 8 : 0), board ? headerY + 23 : headerY, board ? BoardStyle.INK : DONE, false);
        if (!(board || tab == 2)) scroll = Math.max(0, scroll / viewport.height() * viewport.height());
        x = viewport.x(); span = viewport.width(); y = viewport.y() - scroll;
        int start = y;
        contentStart = y;
        g.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
        try {
            if (board || tab == 2) contracts(g, mx, my);
            else switch (tab) {
                case 0 -> today(g, mx, my);
                case 1 -> goal(g, mx, my);
                case 3 -> standing(g, mx, my);
                default -> map(g, mx, my);
            }
            contentHeight = Math.max(viewport.height(), (board || tab == 2) ? y - start : ((y - start + viewport.height() - 1) / viewport.height()) * viewport.height());
        } finally { g.disableScissor(); }
        scroll = TabletLayout.clampScroll(scroll, contentHeight, viewport.height());
        if (contentHeight > viewport.height()) {
            int thumb = Math.max(4, viewport.height() * viewport.height() / contentHeight);
            int sy = viewport.y() + (viewport.height() - thumb) * scroll / Math.max(1, contentHeight - viewport.height());
            g.fill(viewport.right() + 1, viewport.y(), viewport.right() + 5, viewport.bottom(), board ? 0xFF5A2A1F : 0xFFB2D3B4);
            g.fill(viewport.right() + 1, sy, viewport.right() + 5, sy + thumb, board ? 0xFF9A5440 : 0xFF244C3C);
        }
        if (!board) tabs(g, mx, my);
        if (tip != null) g.renderComponentTooltip(font, tip, mx, my);
    }

    /** The same measured wrapping is used for layout and drawing; every line reserves ten pixels. */
    private void paragraph(GuiGraphics g, Component text, int color) {
        if (board) sprite(g, "contract", x - 2, y - 1, span + 4, lines(text, span) * 10 + 2);
        for (String line : TabletLayout.wrap(text.getString(), span, font::width)) {
            reserve(10);
            g.drawString(font, line, x, y, board ? BoardStyle.INK : color, false);
            y += 10;
        }
        y += gap;
    }
    private int drawLines(GuiGraphics g, Component text, int px, int py, int w, int color) {
        for (var line : TabletLayout.wrap(text.getString(), w, font::width)) {
            g.drawString(font, line, px, py, board && color != DONE ? BoardStyle.INK : color, false);
            py += 10;
        }
        return py;
    }
    private void heading(GuiGraphics g, Component text) { paragraph(g, text, DONE); }
    private int lines(Component c, int w) { return TabletLayout.wrap(c.getString(), w, font::width).size(); }

    /** Disabled seals retain localized reason tooltips; tablet controls also draw a padlock. */
    private void button(GuiGraphics g, Component label, int px, int py, int w, TabletLayout.Reason reason,
                        Runnable action, int mx, int my) {
        Component original = label;
        label = Component.literal(TabletLayout.fitLabel(label.getString(), w - (board ? 16 : reason == null ? 10 : 18), font::width));
        int h = Math.max(bh, 10);
        boolean hover = new TabletLayout.Rect(px, py, w, h).contains(mx, my) && viewport.contains(mx, my);
        sprite(g, board ? reason != null ? "seal_disabled" : hover ? "seal_hover" : "seal"
                : reason == null ? "button_gold" : "button", px, py, w, h);
        if (!board) g.fill(px + 2, py + 2, px + w - 2, py + h - 2, BAR);
        if (board) {
            // API: Minecraft 1.21.1 network/chat/MutableComponent.java#withStyle(ChatFormatting),
            // ChatFormatting.java#BOLD; GuiGraphics.java#drawString(Font,Component,int,int,int,boolean).
            label = Component.literal(TabletLayout.fitLabel(label.getString(), w - 16,
                    text -> font.width(Component.literal(text).withStyle(net.minecraft.ChatFormatting.BOLD))))
                    .withStyle(net.minecraft.ChatFormatting.BOLD);
        }
        g.drawString(font, label, board ? px + (w - font.width(label)) / 2 : px + (reason == null ? 5 : 13),
                py + (h - 8) / 2, board ? 0xFFFFE7B5 : BAR_TEXT, false);
        TabletLayout.Rect r = new TabletLayout.Rect(px, py, w, h);
        if (!label.getString().equals(original.getString()) && r.contains(mx, my) && viewport.contains(mx, my)) tip = List.of(original);
        if (reason == null) {
            // Clip the hit area too: scrolled-away buttons must never steal a tab or header click.
            int top = Math.max(py, viewport.y()), bottom = Math.min(py + h, viewport.bottom());
            if (bottom > top) hits.add(new Hit(new TabletLayout.Rect(px, top, w, bottom - top), action));
        } else {
            if (!board) lock(g, px + 3, py + (h - 7) / 2);
            if (r.contains(mx, my) && viewport.contains(mx, my)) tip = List.of(Component.translatable(reason.key));
        }
    }
    static void lock(GuiGraphics g, int x, int y) {
        g.renderOutline(x + 1, y, 4, 4, BAR_TEXT);
        g.fill(x, y + 3, x + 6, y + 7, BAR_TEXT);
    }
    private void fullButton(GuiGraphics g, Component label, TabletLayout.Reason reason, Runnable action, int mx, int my) {
        reserve(Math.max(bh, 10));
        button(g, label, x, y, span, reason, action, mx, my);
        y += Math.max(bh, 10) + gap;
    }
    private void tabs(GuiGraphics g, int mx, int my) {
        for (int i = 0; i < TABS.length; i++) {
            TabletLayout.Rect r = TabletLayout.tab(panel, guiScale, i);
            int tx = r.x(), top = r.y(), tw = r.width(), h = r.height();
            sprite(g, i == tab ? "button_gold" : "button", tx, top, tw, h);
            String label = t("tab." + TabletLayout.TAB_LABELS.get(i)).getString();
            g.fill(tx + 1, top + 1, tx + tw - 1, top + h - 1, BAR);
            g.drawString(font, label, tx + (tw - font.width(label)) / 2, top + (h - 9) / 2, BAR_TEXT, false);
            if (r.contains(mx, my)) tip = List.of(t("tab." + TABS[i].id()));
            final int target = i;
            hits.add(new Hit(r, () -> { tab = target; scroll = 0; }));
        }
    }
    private long time() {
        return !frozen && Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getDayTime()
                : SectSchedule.Period.values()[data.period()] == SectSchedule.Period.TRAINING ? SectSchedule.LESSON_FROM
                : SectSchedule.Period.values()[data.period()].start();
    }
    private void today(GuiGraphics g, int mx, int my) {
        TabletLayout.Clock clock = TabletLayout.clock(time());
        heading(g, t("day_rank", data.day(), rankName()));
        todayBlock(g, t("today.current", t("period." + clock.now())), t("now." + clock.now()));
        todayBlock(g, t("today.next", t("period." + clock.next())),
                t("today.countdown", clock.seconds() / 60, String.format(java.util.Locale.ROOT, "%02d", clock.seconds() % 60)));
        fullButton(g, t("today.schedule"), null, () -> { schedule = !schedule; scroll = 0; }, mx, my);
        if (schedule) for (TabletLayout.ScheduleSlot slot : TabletLayout.schedule()) {
            String hour = String.format(java.util.Locale.ROOT, "%02d:%02d", (slot.start() / 1000 + 6) % 24, slot.start() % 1000 * 60 / 1000);
            paragraph(g, t("today.schedule_row", hour, t("period." + slot.id())), MUTED);
        }
        if (schedule) paragraph(g, t(io.github.verycooltimo.murim.sect.SectCouncil.config().onDay(data.day() - 1L)
                ? "today.lecture.council" : "today.lecture.ordinary"), MUTED);
        for (SectTabletData.Mark mark : data.marks()) {
            Component label = mark.task() ? mark.label() : t("mark." + mark.id());
            g.blitSprite(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "tablet/check_" + (mark.done() ? "on" : "off")), x, y, 10, 10);
            int oldX = x, oldSpan = span; x += 14; span -= 14;
            paragraph(g, label, mark.done() ? DONE : MUTED);
            x = oldX; span = oldSpan;
        }
        int required = (int) data.marks().stream().filter(m -> !m.task() && m.required()).count();
        int done = (int) data.marks().stream().filter(m -> !m.task() && m.required() && m.done()).count();
        paragraph(g, required == 0 ? t("fullday.none") : t("fullday", done, required, data.bonus()), MUTED);
        paragraph(g, data.approved() ? t("streak.approved") : t("streak", data.fullDays(), data.approvalStreak()), MUTED);
    }
    private void todayBlock(GuiGraphics g, Component title, Component detail) {
        int h = 8 + 10 * (lines(title, span - 8) + lines(detail, span - 8)) + gap;
        reserve(h);
        g.fill(x, y, x + span, y + h, ROW);
        int top = y;
        int yy = drawLines(g, title, x + 4, y + 4, span - 8, INK);
        drawLines(g, detail, x + 4, yy + gap, span - 8, MUTED);
        y = top + h + gap;
    }
    private Component rankName() {
        return data.office() ? data.positionTitle() : t("rank." + SectTabletView.ladder().get(SectTabletView.held(data.standing(), 0)));
    }
    private void goal(GuiGraphics g, int mx, int my) {
        heading(g, t("goal.rank", data.realm(), rankName()));
        heading(g, data.goalName());
        if (data.goalTotal() > 0) paragraph(g, t("goal.progress", data.goalDone(), data.goalTotal()), MUTED);
        paragraph(g, t("goal.who", data.goalPerson()), INK);
        paragraph(g, SectGuideHud.whereOf(true, ""), MUTED);
        paragraph(g, data.goalHint(), INK);
        fullButton(g, t("journal.title"), null, () -> { TabletNavigation.remember(this); replaced = true; send("journal"); }, mx, my);
        for (int i = 0; i < data.chain().size(); i++) paragraph(g, data.chain().get(i), i < data.chainCurrent() ? DONE : INK);
        if (data.chainMore()) paragraph(g, t("more"), MUTED);
    }

    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/gui/GuiGraphics.java
    // #blitSprite(ResourceLocation,int,int,int,int), #fill, #drawString; ResourceLocation#fromNamespaceAndPath.
    // Nine-slice schema: client/resources/metadata/gui/GuiSpriteScaling.java#NineSlice (1.21.1).
    private void sprite(GuiGraphics g, String name, int x, int y, int w, int h) {
        g.blitSprite(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", BoardStyle.sprite(board, name)), x, y, w, h);
    }
    private Component compact(Component text, int width, int maxLines) {
        List<String> wrapped = TabletLayout.wrap(text.getString(), width, font::width);
        if (wrapped.size() <= maxLines) return text;
        List<String> visible = new ArrayList<>(wrapped.subList(0, maxLines));
        visible.set(maxLines - 1, TabletLayout.fitLabel(visible.get(maxLines - 1) + "…", width, font::width));
        return Component.literal(String.join("\n", visible));
    }
    private long minutesLeft() {
        long left = Math.floorMod(23000L - Math.floorMod(time(), 24000L), 24000L);
        return Math.max(1, (left + 1199) / 1200);
    }
    private void reserve(int height) {
        if (board || tab == 2) return;
        y = contentStart + TabletLayout.wholeRowTop(y - contentStart, height, viewport.height());
    }

    /** Pinned help tab retains the same expandable help and also exposes its text on hover. */
    private void boardHelp(GuiGraphics g, int mx, int my) {
        int w = font.width(t("board.help")) + 12;
        int top = panel.y() + 16;
        TabletLayout.Rect r = new TabletLayout.Rect(viewport.x(), top, w, 14);
        sprite(g, "help", r.x(), r.y(), w, 14);
        sprite(g, "pin", r.x() + 1, r.y() - 1, 5, 5);
        g.drawString(font, t("board.help"), r.x() + 7, r.y() + 3, BoardStyle.INK, false);
        hits.add(new Hit(r, () -> { schedule = !schedule; scroll = 0; }));
        if (r.contains(mx, my)) tip = List.of(t("board.footer", minutesLeft()), t("board.help_text"));
    }

    private void contracts(GuiGraphics g, int mx, int my) {
        if (!board) fullButton(g, t("board.help"), null, () -> { schedule = !schedule; scroll = 0; }, mx, my);
        if (schedule) { paragraph(g, t("board.footer", minutesLeft()), MUTED); paragraph(g, t("board.help_text"), MUTED); }
        SectTabletData.Board b = data.contracts();
        if (data.offers().isEmpty()) { paragraph(g, t("contracts.none"), INK); paragraph(g, t("contracts.none_hint"), MUTED); }
        for (SectTabletData.Offer o : data.offers()) {
            boolean mine = data.active() != null && data.active().slot() == o.slot();
            Component detail = t("contracts.row_detail", o.merit(), o.giver());
            Component merit = t("contracts.row_merit", o.merit());
            Component actionLabel = mine ? t("board.hand_in") : t("board.take");
            int actionW = board ? font.width(Component.literal(actionLabel.getString()).withStyle(net.minecraft.ChatFormatting.BOLD)) + 20
                    : TabletLayout.buttonWidth(actionLabel.getString(), true, font::width);
            boolean horizontal = span >= 220;
            int textX = board ? 40 : 20;
            int textW = board ? Math.max(1, span - textX - 8 - (horizontal ? actionW + gap : 0))
                    : span - 24 - (horizontal ? actionW + gap : 0);
            Component badge = mine ? t("contracts.status_giver", o.giver(), t("contracts.mine"))
                    : t(o.done() ? "contracts.done" : "contracts.available");
            Component rowTitle = compact(o.title(), textW, board ? 1 : 2);
            Component rowBadge = compact(badge, textW, 1);
            Component progress = board && mine ? boardProgress() : null;
            int rowH = board ? Math.max(46, 8 + 34 + (progress == null ? 0 : 12)) + (horizontal ? 0 : bh + 4)
                    : TabletLayout.contractRowHeight(guiScale, lines(rowTitle, textW), lines(rowBadge, textW), horizontal);
            reserve(rowH);
            int rowTop = y;
            boolean hover = board && viewport.contains(mx, my) && new TabletLayout.Rect(x, rowTop, span, rowH).contains(mx, my);
            int top = rowTop - (hover ? 1 : 0);
            rowStops.add(rowTop - contentStart);
            if (board) {
                g.fill(x + 1, top + 2, x + span + 1, top + rowH + (hover ? 3 : 1), 0x70000000);
                sprite(g, "contract", x, top, span, rowH);
                sprite(g, "pin", x + 1, top, 5, 5);
            } else g.fill(x, top, x + span, top + rowH, ROW);
            sprite(g, o.icon(), x + (board ? 4 : 2), top + (board ? 5 : 3), board ? 32 : 16, board ? 32 : 16);
            int yy = board ? boardTitle(g, o.title(), x + textX, top + 5, textW)
                    : drawLines(g, rowTitle, x + textX, top + 3, textW, INK);
            if (board) sprite(g, "merit", x + textX, yy, 10, 10);
            yy = drawLines(g, compact(merit, textW - (board ? 12 : 0), 1), x + textX + (board ? 12 : 0), yy,
                    textW - (board ? 12 : 0), MUTED);
            yy = drawLines(g, rowBadge, x + textX, yy, textW, o.done() ? DONE : MUTED);
            if (progress != null) drawLines(g, compact(progress, span - 16, 1), x + 8, top + 43, span - 16, INK);
            if (o.done()) sprite(g, "check_on", x + 3, top + rowH - 12, 10, 10);
            if (mine) {
                TabletLayout.Reason reason = !b.ready() ? TabletLayout.Reason.OBJECTIVES : !b.reach() ? TabletLayout.Reason.BOARD
                        : !b.handInHere() ? TabletLayout.Reason.GIVER : null;
                button(g, actionLabel, x + span - actionW - 4, (horizontal ? top + 4 : top + rowH - Math.max(bh, 10) - 3), actionW, reason, () -> act("handin", 0, ""), mx, my);
            } else if (!o.done()) {
                button(g, t("board.take"), x + span - actionW - 4, (horizontal ? top + 4 : top + rowH - Math.max(bh, 10) - 3), actionW,
                        TabletLayout.takeReason(b.reach(), b.open(), data.active() != null, o.done()), () -> act("take", o.slot(), ""), mx, my);
            }
            if (new TabletLayout.Rect(x, top, span, rowH).contains(mx, my) && viewport.contains(mx, my) && tip == null) tip = progress == null ? List.of(o.title(), o.where(), detail, badge) : List.of(o.title(), o.where(), detail, badge, progress);
            y = rowTop + rowH + (board ? 4 : gap);
        }
        if (data.active() != null) {
            if (board) {
                // Normally progress is inside its offer; retain it if a snapshot omits that offer.
                if (data.offers().stream().noneMatch(o -> o.slot() == data.active().slot())) paragraph(g, boardProgress(), INK);
            } else if (data.active().counters().isEmpty()) paragraph(g, data.active().progress().getString().isEmpty() ? data.active().status() : data.active().progress(), INK);
            else for (SectTabletData.Counter counter : data.active().counters()) {
                reserve(Math.max(16, font.split(ContractTracker.counterText(counter), span - 20).size() * 10));
                y = ContractTracker.counter(g, font, counter, x, y, span) + gap;
            }
            paragraph(g, t("board.hand_in_at", b.giver()), MUTED);
            paragraph(g, t("board.group_line", String.join(", ", data.active().members())), MUTED);
            fullButton(g, t("contracts.invite"), !b.reach() ? TabletLayout.Reason.BOARD : data.active().members().size() >= data.active().max() ? TabletLayout.Reason.FULL
                            : Minecraft.getInstance().player != null && !data.active().members().isEmpty()
                            && !data.active().members().get(0).equals(Minecraft.getInstance().player.getGameProfile().getName()) ? TabletLayout.Reason.LEADER
                            : b.invitable().isEmpty() ? TabletLayout.Reason.INVITE : null,
                    () -> picking = !picking, mx, my);
            if (picking) for (SectTabletData.Candidate c : b.invitable()) fullButton(g, Component.literal(c.name()), null, () -> { act("invite", 0, c.id()); picking = false; }, mx, my);
            fullButton(g, t("contracts.leave"), b.reach() ? null : TabletLayout.Reason.BOARD, () -> act("leave", 0, ""), mx, my);
        } else if (b.invite() != null) {
            paragraph(g, t("board.invited", b.invite().from(), b.invite().title()), INK);
            fullButton(g, t("board.accept"), null, () -> act("accept", 0, ""), mx, my);
            fullButton(g, t("board.decline"), null, () -> act("decline", 0, ""), mx, my);
        }
    }
    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/gui/GuiGraphics.java#pose;
    // gui/Gui.java#renderCameraOverlays uses PoseStack pushPose/translate/scale/popPose.
    // Font.java#width(FormattedText), MutableComponent.java#withStyle(ChatFormatting).
    private int boardTitle(GuiGraphics g, Component title, int px, int py, int width) {
        Component fitted = Component.literal(TabletLayout.fitLabel(title.getString(), Math.max(1, (int) (width / 1.25F)),
                text -> font.width(Component.literal(text).withStyle(net.minecraft.ChatFormatting.BOLD))))
                .withStyle(net.minecraft.ChatFormatting.BOLD);
        g.pose().pushPose();
        try {
            g.pose().translate((float) px, (float) py, 0.0F);
            g.pose().scale(1.25F, 1.25F, 1.0F);
            g.drawString(font, fitted, 0, 0, BoardStyle.INK, false);
        } finally { g.pose().popPose(); }
        return py + 14;
    }

    /** One ingredient line per active strip, using the existing localized counter labels. */
    private Component boardProgress() {
        if (data.active().counters().isEmpty()) return data.active().progress().getString().isEmpty()
                ? data.active().status() : data.active().progress();
        return Component.literal(String.join(" · ", data.active().counters().stream()
                .map(c -> ContractTracker.counterText(c).getString()).toList()));
    }

    private void standing(GuiGraphics g, int mx, int my) {
        heading(g, rankName());
        paragraph(g, t("standing.merit", data.merit(), data.contribution()), MUTED);
        paragraph(g, data.challengesOpen() ? t("standing.challenges", data.wins(), data.needed()) : t("standing.challenges_done"), INK);
        paragraph(g, data.reviewDays() == 0 ? t("standing.review_today") : t("standing.review", data.reviewDays(), dayWord(data.reviewDays())), INK);
        if (data.signed()) paragraph(g, t("standing.signed"), DONE);
        else fullButton(g, t("standing.signup"), !data.canSign() ? TabletLayout.Reason.AGE : !data.signupOpen() ? TabletLayout.Reason.SIGNUP : null, () -> send("review_signup"), mx, my);
        // Each rank stays whole; page scrolling preserves the complete ladder order.
        int held = SectTabletView.held(data.standing(), data.position());
        List<String> ladder = SectTabletView.ladder();
        int h = Math.max(TabletLayout.standingRow(guiScale), ladder.stream().mapToInt(id -> lines(t("rank." + id), span - 8) * 10 - 1).max().orElse(0));
        if (ladderScroll < 0) ladderScroll = Math.max(0, Math.min(held, ladder.size() - 3));
        for (int i = ladderScroll; i < Math.min(ladder.size(), ladderScroll + 3); i++) {
            reserve(h);
            if (y >= viewport.y() && y + h <= viewport.bottom()) ladderRows.add(new TabletLayout.Rect(x, y, span, h));
            g.fill(x, y, x + span, y + h, ROW);
            Component label = t("rank." + ladder.get(i));
            drawLines(g, label, x + 4, y + (h - (lines(label, span - 8) * 10 - 1)) / 2, span - 8, i < held ? DONE : INK);
            if (i == held) g.fill(x, y, x + 2, y + h, MUTED);
            y += h + gap;
        }
        fullButton(g, t("standing.beaten_toggle", data.beaten().size()), null, () -> beaten = !beaten, mx, my);
        if (beaten) for (Component name : data.beaten()) paragraph(g, name, INK);
        paragraph(g, t("standing.mentor", data.mentor()), INK);
        paragraph(g, t("standing.teacher", data.teacher().getString().isEmpty() ? t("standing.no_teacher") : data.teacher()), MUTED);
        fullButton(g, t("standing.household"), null, () -> household = !household, mx, my);
        if (household) {
            paragraph(g, t("standing.treasury", data.treasury()), INK);
            for (SectTabletData.Shop shop : data.shop()) {
                paragraph(g, shop.name(), INK);
                paragraph(g, shop.have() >= shop.max() ? t("standing.stock.full", shop.have(), shop.max()) : t("standing.stock.wait", shop.have(), shop.max(), shop.minutes()), MUTED);
            }
            for (Component line : data.chronicle()) paragraph(g, line, MUTED);
        }
    }
    private static Component dayWord(int n) {
        return t("days." + (n % 10 == 1 && n % 100 != 11 ? "one" : n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 10 || n % 100 >= 20) ? "few" : "many"));
    }
    private void map(GuiGraphics g, int mx, int my) {
        if (!SectMapClient.has() || frozen) { paragraph(g, t("map.charting"), MUTED); return; }
        int h = Math.max(50, viewport.height() - 16);
        float scale = Math.min(span / (float) SectMapClient.width(), h / (float) SectMapClient.height());
        int mapTop = y;
        g.pose().pushPose();
        try {
            g.pose().translate(x, mapTop, 0);
            g.pose().scale(scale, scale, 1);
            g.blit(SectMapClient.TEXTURE, 0, 0, 0F, 0F, SectMapClient.width(), SectMapClient.height(), SectMapClient.width(), SectMapClient.height());
        } finally { g.pose().popPose(); }
        // Labels and markers are drawn after the texture transform, always at vanilla text scale.
        int key = java.util.Objects.hash(x, mapTop, span, h, data.labels(), t("map.legend.you").getString());
        boolean placeLabels = key != mapLabelKey || mapPlaques.size() != data.labels().size();
        if (placeLabels) { mapPlaques.clear(); mapLabelKey = key; }
        List<int[]> plaques = new ArrayList<>();
        int labelIndex = 0;
        for (SectTabletData.MapLabel label : data.labels()) {
            int px = x + Math.round(((label.x0() + label.x1()) / 2F - SectMapClient.x0()) * scale);
            int py = mapTop + Math.round(((label.z0() + label.z1()) / 2F - SectMapClient.z0()) * scale);
            g.fill(px - 1, py - 1, px + 2, py + 2, MUTED);
            Component name = t("place." + label.key());
            int[] plaque = placeLabels ? MapLabelPlacer.place(px, py, font.width(name) + 6, 12, x, mapTop, x + span, mapTop + h, plaques, List.of())
                    : mapPlaques.get(labelIndex);
            if (placeLabels) mapPlaques.add(plaque);
            labelIndex++;
            if (plaque != null) {
                plaques.add(plaque);
                g.fill(plaque[0], plaque[1], plaque[0] + plaque[2], plaque[1] + plaque[3], PAPER);
                g.drawString(font, name, plaque[0] + 3, plaque[1] + 2, INK, false);
            }
            if (viewport.contains(mx, my) && Math.abs(mx - px) < 5 && Math.abs(my - py) < 5) tip = List.of(t("place." + label.key()));
        }
        marker(g, data.you(), x, mapTop, scale, 0xFF9E3030);
        marker(g, data.mentorAt(), x, mapTop, scale, 0xFF365D9A);
        marker(g, data.bed(), x, mapTop, scale, DONE);
        for (float[] boardAt : data.boards()) marker(g, boardAt, x, mapTop, scale, INK);
        y += h + gap;
        paragraph(g, t("map.legend.you"), INK);
        paragraph(g, t("map.legend.mentor", data.mentor()), MUTED);
        paragraph(g, t("map.legend.bed"), MUTED);
        paragraph(g, t("map.legend.board"), MUTED);
    }
    private void marker(GuiGraphics g, float[] at, int px, int py, float scale, int color) {
        if (at == null || at[0] < SectMapClient.x0() || at[0] >= SectMapClient.x0() + SectMapClient.width()
                || at[1] < SectMapClient.z0() || at[1] >= SectMapClient.z0() + SectMapClient.height()) return;
        int xx = px + Math.round((at[0] - SectMapClient.x0()) * scale), yy = py + Math.round((at[1] - SectMapClient.z0()) * scale);
        g.fill(xx - 2, yy - 2, xx + 3, yy + 3, color);
    }
    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) for (Hit hit : hits) if (hit.rect().contains(mx, my)) { hit.action().run(); return true; }
        return super.mouseClicked(mx, my, button);
    }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (sy == 0) return super.mouseScrolled(mx, my, sx, sy);
        if (!board && tab == 3 && ladderRows.stream().anyMatch(row -> row.contains(mx, my))) {
            ladderScroll = Math.max(0, Math.min(SectTabletView.ladder().size() - 3, ladderScroll + (sy < 0 ? 1 : -1)));
            return true;
        }
        if (viewport != null && viewport.contains(mx, my) && (board || tab == 2)) {
            int limit = Math.max(0, contentHeight - viewport.height());
            // Large illustrated strips may exceed a short viewport: keep every part reachable.
            if (board) {
                scroll = TabletLayout.clampScroll(scroll + (sy < 0 ? 1 : -1) * Math.max(8, Math.min(24, viewport.height() / 2)),
                        contentHeight, viewport.height());
                return true;
            }
            if (sy < 0) scroll = rowStops.stream().filter(pos -> pos > scroll).findFirst().orElse(limit);
            else scroll = rowStops.stream().filter(pos -> pos < scroll).mapToInt(Integer::intValue).max().orElse(0);
            scroll = TabletLayout.clampScroll(scroll, contentHeight, viewport.height());
            return true;
        }
        if (viewport != null && viewport.contains(mx, my)) { scroll = TabletLayout.clampScroll(scroll + (sy < 0 ? viewport.height() : -viewport.height()), contentHeight, viewport.height()); return true; }
        return super.mouseScrolled(mx, my, sx, sy);
    }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256 && (picking || schedule)) { picking = false; schedule = false; return true; }
        if (!board && key >= 49 && key <= 53) { tab = key - 49; scroll = 0; return true; }
        if (!board && (key == 263 || key == 262)) { tab = Math.floorMod(tab + (key == 263 ? -1 : 1), TABS.length); scroll = 0; return true; }
        if (viewport != null && (key == 264 || key == 265)) { scroll = TabletLayout.clampScroll(scroll + (key == 264 ? viewport.height() : -viewport.height()), contentHeight, viewport.height()); return true; }
        if (Minecraft.getInstance().options.keyInventory.matches(key, scan)) { onClose(); return true; }
        return super.keyPressed(key, scan, mods);
    }
}
