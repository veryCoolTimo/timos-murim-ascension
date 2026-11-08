package io.github.verycooltimo.murim.client.vfx;

/** Shared sheet paint order: the accent belongs inside the sheet's early-return branch. */
public final class FootworkRibbonLayers {
    public static boolean sheet(float alpha, Runnable surface, Runnable edge, Runnable accent) {
        if (alpha <= 0) return false;
        surface.run();
        edge.run();
        accent.run();
        return true;
    }
    private FootworkRibbonLayers() { }
}
