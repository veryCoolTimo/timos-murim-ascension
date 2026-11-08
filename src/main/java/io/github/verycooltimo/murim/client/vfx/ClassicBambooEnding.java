package io.github.verycooltimo.murim.client.vfx;

/** Pure presentation clocks; docs/adr/bamboo-scale-2026-11-04.md, rounds 10–11. */
final class ClassicBambooEnding {
    static final int FADE_TICKS = 6;

    private ClassicBambooEnding() {}

    static double start(double from, double end, int order, int count) {
        return from + (end - from - FADE_TICKS) * order / Math.max(1, count - 1);
    }

    static float fade(float age, double start) {
        double remaining = Math.clamp((start + FADE_TICKS - age) / FADE_TICKS, 0, 1);
        return (float) (remaining * remaining * (3 - 2 * remaining));
    }

    // Round 11: twenty ticks total, four ticks of opaque travel before a four-tick fade.
    // Ordered geometry runs from fingertips through fingers to the wrist.
    static double pieceStart(double end, int order, int count) {
        return end - 20 + 12.0 * order / Math.max(1, count - 1);
    }

    static double pieceTravel(double age, double start) {
        return Math.max(0, age - start);
    }

    static double pieceDistance(double age, double start) {
        return .14 * pieceTravel(age, start);
    }

    static float pieceFade(double age, double start) {
        double t = Math.clamp((age - start - 4) / 4, 0, 1);
        return (float) (1 - t * t * (3 - 2 * t));
    }

    static float pointFade(float age, int point) {
        double local = age - point * 1.5;
        if (local <= 0 || local >= 1.5) return 0;
        return (float) (Math.min(1, local / .25) * Math.min(1, (1.5 - local) / .75));
    }
}
