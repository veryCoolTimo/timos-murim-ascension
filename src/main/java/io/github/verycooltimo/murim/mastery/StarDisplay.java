package io.github.verycooltimo.murim.mastery;

/** Shared, testable HUD notation. Every art uses twelve positions, including torn manuals. */
public final class StarDisplay {
    public static String gauge(int stars, int cap) {
        int maximum = Arts.MAX_STARS;
        int filled = Math.max(0, Math.min(stars, maximum));
        return "★".repeat(filled) + "☆".repeat(maximum - filled);
    }

    private StarDisplay() {}
}
