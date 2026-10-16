package io.github.verycooltimo.murim.mastery;

/** Shared HUD notation. The denominator is the full art cap, including for torn manuals. */
public final class StarDisplay {
    public static String gauge(int stars, int cap) {
        int maximum = Math.max(0, cap);
        int filled = Math.max(0, Math.min(stars, maximum));
        return "★".repeat(filled) + "☆".repeat(maximum - filled);
    }

    private StarDisplay() {}
}
