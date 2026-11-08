package io.github.verycooltimo.murim.network;

/**
 * How big the jade tablet is drawn. Plain arithmetic with no client types, so it can be tested headless. The tablet is
 * not stretched to the GUI scale: it takes the largest whole number of screen pixels per texel that keeps its width within
 * {@link #MAX_WIDTH_SHARE} of the window (and its height within {@link #MAX_HEIGHT_SHARE}); the world stays visible around it.
 */
public final class SectTabletFit {
    /** Slab width and height in texels, and how far the medallion rises above the slab. */
    public static final int W = 392;
    public static final int H = 278;
    public static final int TOP = 19;
    public static final double MAX_WIDTH_SHARE = 0.65D;
    public static final double MAX_HEIGHT_SHARE = 0.90D;

    private SectTabletFit() { }

    /** Screen pixels per texel: a whole number when at least one fits, otherwise a fraction. */
    public static double pixelsPerTexel(int windowW, int windowH) {
        double limit = Math.min(MAX_WIDTH_SHARE * windowW / W, MAX_HEIGHT_SHARE * windowH / (H + TOP));
        return limit >= 1.0D ? Math.floor(limit) : limit;
    }

    /** The factor for the GUI pose stack, which counts in GUI pixels. */
    public static float guiFactor(int windowW, int windowH, int guiScale) {
        return (float) (pixelsPerTexel(windowW, windowH) / Math.max(1, guiScale));
    }
}
