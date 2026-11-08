package io.github.verycooltimo.murim.client.library;

/** Pure GUI-pixel geometry, independent of Minecraft and rendering state. */
public record MethodScrollLayout(Rect bounds, Rect top, Rect paper, Rect bottom, Rect illustration, Rect text, Rect seal) {
    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
        public boolean contains(double px, double py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }
    }

    public static MethodScrollLayout of(int guiWidth, int guiHeight, int artWidth, int artHeight) {
        return of(guiWidth, guiHeight, artWidth, artHeight, 0);
    }

    public static MethodScrollLayout of(int guiWidth, int guiHeight, int artWidth, int artHeight, int textHeight) {
        // Committed art: top 186x28 (band 8..178), bottom 200x40 (band 15..185).
        // Both bands are 170 px wide. Prefer whole frame scales, then round every edge alike.
        int preferredWidth = Math.max(220, Math.min(300, Math.round(guiWidth * 0.38F)));
        // The full bottom texture includes the jade tassel; reserve HUD space below it.
        int h = Math.max(1, Math.min(Math.round(guiHeight * 0.92F), guiHeight - 60));
        // Reserve at least half the height for paper when shrinking to a short GUI.
        double scale = Math.min(Math.min(preferredWidth, guiWidth) / 200.0, h / 136.0);
        if (scale >= 1) scale = Math.floor(scale);
        int paperW = Math.max(1, (int) Math.round(170 * scale));
        int topW = (int) Math.round(186 * scale);
        int bottomW = (int) Math.round(200 * scale);
        int topH = (int) Math.round(28 * scale);
        int bottomH = (int) Math.round(40 * scale);
        int x = (guiWidth - bottomW) / 2;
        int y = 6;
        int paperX = x + (int) Math.round(15 * scale);
        Rect paper = new Rect(paperX, y + topH, paperW, h - topH - bottomH);
        // Measure text at its unchanged font sizes before spending space on illustration.
        // Shrink art first (52% -> 36%), then the six-pixel gap above the title.
        int gap = 6;
        int preferredArt = Math.max(1, Math.round(paper.height * 0.52F));
        int available = Math.max(0, paper.height - 48);
        // On the shortest paper, reserve a title line before applying the 36% art floor.
        int minimumArt = Math.max(1, Math.min(Math.round(paper.height * 0.36F), available - 12));
        int artAreaHeight = Math.min(available,
                Math.max(minimumArt, Math.min(preferredArt, available - gap - textHeight)));
        gap = Math.max(0, Math.min(gap, available - artAreaHeight - textHeight));
        Rect artBox = new Rect(paper.x + 8, paper.y, paper.width - 16, artAreaHeight);
        Rect art = fit(artBox, artWidth, artHeight);
        int textY = paper.y + artAreaHeight + gap;
        return new MethodScrollLayout(new Rect(x, y, bottomW, h),
                new Rect(paperX - (int) Math.round(8 * scale), y, topW, topH), paper,
                new Rect(x, paper.bottom(), bottomW, bottomH), art,
                new Rect(paper.x + 8, textY, paper.width - 16, Math.max(0, paper.bottom() - 48 - textY)),
                new Rect(paper.x + (paper.width - 72) / 2, paper.bottom() - 32, 72, 18));
    }

    /** Preserve aspect and use an integer enlargement whenever the image fits at native resolution. */
    public static Rect fit(Rect box, int sourceWidth, int sourceHeight) {
        double scale = Math.min(box.width / (double) Math.max(1, sourceWidth),
                box.height / (double) Math.max(1, sourceHeight));
        if (scale >= 1) scale = Math.floor(scale);
        int w = Math.max(1, (int) Math.round(Math.max(1, sourceWidth) * scale));
        int h = Math.max(1, (int) Math.round(Math.max(1, sourceHeight) * scale));
        return new Rect(box.x + (box.width - w) / 2, box.y + (box.height - h) / 2, w, h);
    }
}
