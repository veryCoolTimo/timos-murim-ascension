package io.github.verycooltimo.murim.technique;

/** Star tables: docs/design/techniques/hua-footwork-design-1031.md (footwork caps at twelve). */
public final class ShadowRules {
    public static final double WALK = 4.317D;
    public static final double OBSERVER_RANGE = 32.0D;
    private static int band(int stars) {
        return stars >= 12 ? 7 : stars >= 11 ? 6 : stars >= 10 ? 5 : stars >= 8 ? 4 : stars >= 5 ? 3 : stars >= 3 ? 2 : stars >= 1 ? 1 : 0;
    }
    public static int duration(int stars) { return new int[] {0, 40, 60, 80, 100, 120, 130, 140}[band(stars)]; }
    public static double speedBonus(int stars) { return .6D + Math.max(0, Math.min(9, stars - 1)) / 15.0D + .1D * Math.max(0, Math.min(2, stars - 10)); }
    public static double speed(int stars) { return WALK * (1 + speedBonus(stars)); }
    public static double deathDamage(int stars) { return 1 + .05D * Math.max(0, stars - 3); }
    public static int observers(int stars) { return new int[] {0, 1, 3, 6, 12, 30, 40, 50}[band(stars)]; }
    public static int cooldown(int stars) { return 80; }
    public static double entryQi(int stars) { return new double[] {0, 6, 5, 5, 4, 4, 4, 4}[band(stars)]; }
    public static double qiPerSecond(int stars) { return new double[] {0, 4, 3.5, 3, 2.5, 2, 1.75, 1.5}[band(stars)]; }
    public static double deathDistance(int stars) { return new double[] {0, 0, 5, 6, 7, 8, 8.5, 9}[band(stars)]; }
    public static double deathQi(int stars) { return new double[] {0, 0, 14, 12, 11, 10, 9.5, 9}[band(stars)]; }
    public static int deathCooldown(int stars) { return new int[] {0, 0, 120, 110, 100, 90, 85, 80}[band(stars)]; }
    public static int clones(int stars) { return stars >= 12 ? 6 : stars >= 10 ? 5 : stars >= 8 ? 4 : stars >= 5 ? 3 : stars >= 1 ? 2 : 0; }
    public static int cloneLifetime(int stars) { return 80 + (int) Math.round(40 * Math.max(0, Math.min(11, stars - 1)) / 11.0); }
    public static int chainTargets(int stars) { return stars >= 12 ? 3 : stars >= 8 ? 2 : 1; }
    public static double exposedBonus(int stars) { return .4 + .4 * Math.max(0, Math.min(9, stars - 3)) / 9.0; }
    private ShadowRules() {}
}
