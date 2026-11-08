package io.github.verycooltimo.murim.client.vfx;

/** Round-two shape envelopes only: tier visibility and authoritative times stay in RiverRules. */
final class RiverShapes {
    private static double clamp(double x) { return Math.max(0, Math.min(1, x)); }
    private static double smooth(double x) { x = clamp(x); return x * x * (3 - 2 * x); }

    static double seed(int identity) {
        double value = Math.sin(identity * 127.1 + 311.7) * 43758.5453;
        return value - Math.floor(value);
    }
    static int parcelsPerPlane(int petalBudget) { return petalBudget <= 0 ? 0 : Math.min(8, Math.max(6, petalBudget / 40)); }
    static int crestStreaks(int strands) { return strands <= 0 ? 0 : Math.min(5, Math.max(3, strands)); }
    static double petalSize(double seed, int plane, boolean crest) {
        return (seed < .75 ? .08 + .14 * clamp(seed / .75) : .25 + .20 * clamp((seed - .75) / .25)) * (plane == 0 ? .8 : plane == 1 ? .9 : 1);
    }
    static double rayLength(int ray, double scale, double seed) {
        return ray == 0 ? Math.min(9, 8.5 * scale) : ray == 1 ? Math.min(4, 3.5 * scale)
                : Math.min(5, (2.5 + 2 * clamp(seed)) * scale);
    }
    static double rayAlpha(double age, double life) {
        return age < 0 ? 0 : smooth(age / .5) * (1 - smooth((age - 2) / 2));
    }
    /** Only the first five impact ticks may use a sub-.08-block full width. */
    static double coreHalfWidth(double age, double bodyHalfWidth) { return bodyHalfWidth * .10 + (.04 - bodyHalfWidth * .10) * smooth((age - 3) / 2); }
    static double coreDiameter(double age) { return .7 + .3 * smooth(age / 1.5); }
    static double shockRadius(double age, double scale) { return .3 + (Math.min(6, 5 * scale) - .3) * smooth(age / 8); }
    private RiverShapes() { }
}
