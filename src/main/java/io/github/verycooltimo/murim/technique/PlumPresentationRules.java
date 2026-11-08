package io.github.verycooltimo.murim.technique;

/** Presentation policy only; execution tiers and combat clocks are independent. */
public final class PlumPresentationRules {
    private PlumPresentationRules() {}
    public static boolean fullMastery(int stars) { return stars == 12; }
    /** Outer third leaves first; the centre belongs to the last wave. */
    public static int cohort(double radius) { return radius >= 2.0 / 3 ? 0 : radius >= 1.0 / 3 ? 1 : 2; }
    public static double flowerAlpha(double tick, double radius, int[] waves) {
        double u = Math.max(0, Math.min(1, (tick - waves[cohort(radius)]) / RainRules.FALL));
        return 1 - u * u * (3 - 2 * u);
    }
}
