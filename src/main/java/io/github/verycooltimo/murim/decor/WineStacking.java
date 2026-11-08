package io.github.verycooltimo.murim.decor;

/** Pure cup arithmetic, in ticks. Amplifiers are zero based. */
public final class WineStacking {
    private WineStacking() { }
    public static int amplifier(int previous) { return Math.min(2, previous + 1); }
    public static int duration(int remaining) { return Math.min(6000, Math.max(0, remaining) + 1800); }
    public static float damageFactor(int level) { return 1.0F + .05F * level; }
}
