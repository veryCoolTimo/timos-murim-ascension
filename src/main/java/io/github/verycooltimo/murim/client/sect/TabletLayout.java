package io.github.verycooltimo.murim.client.sect;

/** Pure layout in GUI pixels. Source: docs/design/reviews/ui-review-1101.md.
 * At high GUI scales content scrolls instead of shrinking the vanilla font. */
public final class TabletLayout {
    private TabletLayout() { }
    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
        public boolean contains(double px, double py) { return px >= x && px < right() && py >= y && py < bottom(); }
    }
    public static Rect panel(int width, int height, boolean board) {
        int w = Math.round(width * (board ? .58F : .46F));
        // Five full-size localized labels plus 3px padding and the jade edges.
        if (!board) w = Math.min(width - 8, Math.max(290, w));
        int h = Math.round(height * (board ? .58F : .63F));
        return new Rect(board ? (width - w) / 2 : width - w - 4, (height - h) / 2, w, h);
    }
    // frame.png is 392×297: side/bottom curls reach x=32; the straight inner rule is x=12.
    // Use the furthest ornament, then reserve twelve GUI pixels for content.
    public static int frameEdge(Rect panel, double scale) {
        return Math.max(padding(scale), (int) Math.ceil(panel.width() * 32.0 / 392));
    }
    public static int frameCrown(Rect panel, double scale) {
        return Math.max(padding(scale), Math.round(panel.height() * .18F));
    }
    public static Rect viewport(Rect panel, double scale, boolean board) {
        // Board frame has a fixed 12px plank border; reserve its full-height header and scroll gutter.
        if (board) return new Rect(panel.x() + 12, panel.y() + 64, panel.width() - 28,
                Math.max(1, panel.height() - 76));
        int edge = frameEdge(panel, scale) + 12, crown = frameCrown(panel, scale);
        return new Rect(panel.x() + edge, panel.y() + crown + 14, panel.width() - edge * 2 - 4,
                panel.height() - crown - padding(scale) - 14 - (board ? 0 : tabs(scale)));
    }
    public static final int TAB_PADDING = 3;
    public static final java.util.List<String> TAB_LABELS = java.util.List.of("today", "goal", "tasks", "rank", "map");
    public static Rect tab(Rect panel, double scale, int index) {
        int edge = frameEdge(panel, scale), inner = panel.width() - 2 * edge;
        int left = panel.x() + edge + inner * index / TAB_LABELS.size();
        int right = panel.x() + edge + inner * (index + 1) / TAB_LABELS.size();
        return new Rect(left, panel.bottom() - padding(scale) - tabs(scale), right - left, tabs(scale));
    }
    public static Rect tracker(int width, int height) {
        int w = Math.round(width * .30F);
        return new Rect(width - w - 4, 4, w, Math.round(height * .22F));
    }
    public static int padding(double guiScale) { return Math.max(4, (int) Math.ceil(16 / guiScale)); }
    public static int gap(double guiScale) { return Math.max(4, (int) Math.ceil(14 / guiScale)); }
    public static int tabs(double guiScale) { return Math.max(11, (int) Math.round(42 / guiScale)); }
    public static int button(double guiScale) { return Math.max(10, (int) Math.ceil(38 / guiScale)); }
    public static int standingRow(double guiScale) { return Math.max(9, (int) Math.ceil(34 / guiScale)); }
    public static int contractRow(double guiScale, int titleLines, int detailLines) {
        return Math.max((int) Math.ceil(80 / guiScale), Math.max(18 + 10 * (titleLines + detailLines), button(guiScale) + 8));
    }
    public static int clampScroll(int scroll, int content, int viewport) { return Math.max(0, Math.min(scroll, Math.max(0, content - viewport))); }
    /** Leave the tail of a page empty rather than splitting a semantic row. */
    public static int wholeRowTop(int offset, int rowHeight, int viewport) {
        if (viewport <= 0 || rowHeight > viewport) return offset;
        int remaining = viewport - Math.floorMod(offset, viewport);
        return rowHeight <= remaining ? offset : offset + remaining;
    }
    public static int buttonWidth(String label, boolean locked, java.util.function.ToIntFunction<String> measure) {
        return measure.applyAsInt(label) + (locked ? 18 : 10);
    }
    public static String fitLabel(String label, int width, java.util.function.ToIntFunction<String> measure) {
        if (measure.applyAsInt(label) <= width) return label;
        if (measure.applyAsInt("…") > width) return "";
        int end = label.length();
        while (end > 0 && measure.applyAsInt(label.substring(0, end) + "…") > width)
            end = label.offsetByCodePoints(end, -1);
        return label.substring(0, end) + "…";
    }
    public static int compactContractRow(double scale, int titleLines, int statusLines) {
        return contractRowHeight(scale, titleLines, statusLines, false);
    }
    public static int contractRowHeight(double scale, int titleLines, int statusLines, boolean horizontal) {
        int text = 8 + 10 * (Math.min(2, titleLines) + 1 + Math.min(1, statusLines));
        return horizontal ? Math.max(text - 4, button(scale) + 4) : text + Math.max(button(scale), 10);
    }
    /** Wrap with the renderer's width function; a long unbroken word is split at code-point boundaries. */
    public static java.util.List<String> wrap(String text, int width, java.util.function.ToIntFunction<String> measure) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String paragraph : text.split("\\n", -1)) {
            String rest = paragraph;
            while (!rest.isEmpty()) {
                int end = 0, space = -1;
                while (end < rest.length()) {
                    int next = end + Character.charCount(rest.codePointAt(end));
                    if (measure.applyAsInt(rest.substring(0, next)) > Math.max(1, width)) break;
                    if (Character.isWhitespace(rest.codePointAt(end))) space = end;
                    end = next;
                }
                if (end == rest.length()) { lines.add(rest); break; }
                if (end == 0) end = Character.charCount(rest.codePointAt(0));
                if (space > 0) end = space;
                lines.add(rest.substring(0, end));
                rest = rest.substring(end).stripLeading();
            }
            if (paragraph.isEmpty()) lines.add("");
        }
        return lines;
    }
    public enum Reason {
        BOARD("board"), STANDING("standing"), ACTIVE("active"), DONE("done"), OBJECTIVES("objectives"),
        GIVER("giver"), INVITE("invite"), LEADER("leader"), FULL("full"), AGE("age"), SIGNUP("signup");
        public final String key;
        Reason(String suffix) { key = "murim.tablet.reason." + suffix; }
    }
    public static Reason takeReason(boolean reach, boolean open, boolean active, boolean done) {
        return done ? Reason.DONE : !open ? Reason.STANDING : active ? Reason.ACTIVE : !reach ? Reason.BOARD : null;
    }
    /** One clock model feeds both labels and the countdown, including the pre-lesson break. */
    public record Clock(String now, String next, long seconds) { }
    public record ScheduleSlot(String id, int start) { }
    public static java.util.List<ScheduleSlot> schedule() {
        return java.util.List.of(new ScheduleSlot("formation", 23000), new ScheduleSlot("breakfast", 1000),
                new ScheduleSlot("break", 2000), new ScheduleSlot("training", 3800), new ScheduleSlot("dinner", 9000),
                new ScheduleSlot("evening", 11000), new ScheduleSlot("night", 13000));
    }
    public static Clock clock(long ticks) {
        int t = (int) Math.floorMod(ticks, 24000L);
        int[] starts = {1000, 2000, 3800, 9000, 11000, 13000, 23000, 25000};
        String[] ids = {"formation", "breakfast", "break", "training", "dinner", "evening", "night", "formation", "breakfast"};
        int i = 0;
        while (i < 7 && t >= starts[i]) i++;
        return new Clock(ids[i], ids[i + 1], (starts[i] - t + 19L) / 20);
    }
}
