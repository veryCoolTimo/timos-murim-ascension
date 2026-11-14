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

    /** The illustration takes this share of the paper height at every GUI scale (author 11.14: the picture was far too small). */
    public static final float ART_SHARE = 0.65F;
    /** The scroll uses this share of the window height. */
    public static final float HEIGHT_SHARE = 0.90F;
    /** Paper reserved under the text for the Learn seal (18 px seal, 12 px below it). */
    public static final int SEAL_RESERVE = 34;
    public static final int GAP = 3;
    /** Text sizes: the body is smaller than the font's own size so the picture can grow. */
    public static final float TITLE_SCALE = 1.0F;
    public static final float BODY_SCALE = 0.75F;

    public static MethodScrollLayout of(int guiWidth, int guiHeight, int artWidth, int artHeight) {
        return of(guiWidth, guiHeight, artWidth, artHeight, 0);
    }

    public static MethodScrollLayout of(int guiWidth, int guiHeight, int artWidth, int artHeight, int textHeight) {
        // Committed art: top 186x28 (band 8..178), bottom 200x40 (band 15..185).
        // Both bands are 170 px wide. Prefer whole frame scales, then round every edge alike.
        int preferredWidth = Math.max(220, Math.min(300, Math.round(guiWidth * 0.38F)));
        // The full bottom texture includes the jade tassel; reserve HUD space below it.
        int h = Math.max(1, Math.round(guiHeight * HEIGHT_SHARE));
        // Reserve at least half the height for paper when shrinking to a short GUI.
        double scale = Math.min(Math.min(preferredWidth, guiWidth) / 200.0, h / 136.0);
        if (scale >= 1) scale = Math.floor(scale);
        int paperW = Math.max(1, (int) Math.round(170 * scale));
        int topW = (int) Math.round(186 * scale);
        int bottomW = (int) Math.round(200 * scale);
        int topH = (int) Math.round(28 * scale);
        int bottomH = (int) Math.round(40 * scale);
        int x = (guiWidth - bottomW) / 2;
        int y = 4;
        int paperX = x + (int) Math.round(15 * scale);
        Rect paper = new Rect(paperX, y + topH, paperW, h - topH - bottomH);
        // The picture takes ART_SHARE of the paper whatever the text says; the text block sits right under it and scrolls.
        // Only a very short paper keeps 14 px for the text (a title line and the arrow).
        int maxArt = Math.max(1, paper.height - SEAL_RESERVE - GAP - 14);
        int artAreaHeight = Math.min(Math.round(paper.height * ART_SHARE), maxArt);
        Rect artBox = new Rect(paper.x + 1, paper.y, paper.width - 2, artAreaHeight);
        Rect art = fit(artBox, artWidth, artHeight);
        int textY = paper.y + artAreaHeight + GAP;
        return new MethodScrollLayout(new Rect(x, y, bottomW, h),
                new Rect(paperX - (int) Math.round(8 * scale), y, topW, topH), paper,
                new Rect(x, paper.bottom(), bottomW, bottomH), art,
                new Rect(paper.x + 8, textY, paper.width - 16, Math.max(0, paper.bottom() - SEAL_RESERVE - textY)),
                new Rect(paper.x + (paper.width - 72) / 2, paper.bottom() - 30, 72, 18));
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
