package io.github.verycooltimo.murim.client.vfx;

/** Pure visual envelopes. Source: River spec and the 6 November River art direction.
 * No gameplay times, tier mapping, or server state are owned here. */
final class RiverPresentation {
    private static double clamp(double x) { return Math.max(0, Math.min(1, x)); }
    private static double smooth(double x) { x = clamp(x); return x * x * (3 - 2 * x); }

    static int veins(int tier) { return tier <= 0 ? 0 : tier < 4 ? 3 : tier < 7 ? 4 : 5; }
    static int petalCount(int tier) {
        return switch (Math.max(0, Math.min(8, tier))) {
            case 3 -> 80; case 4 -> 120; case 5 -> 170; case 6 -> 220;
            case 7 -> 270; case 8 -> 300; default -> 0;
        };
    }
    /** Includes folded petals, release accents, glints, ribbons and point quads; enforced at submission. */
    static int quadCeiling(int tier) { return tier <= 0 ? 0 : 2400 + 2 * petalCount(tier); }
    static int groundLife(double seed) { return 60 + (int) Math.round(100 * clamp(seed)); }
    static double coreDiameter(double age) { return .4 + .4 * smooth(age / 2); }
    static double rayAlpha(double age, double life) {
        return age < 0 ? 0 : smooth(age / 2) * (1 - smooth((age - 2) / Math.max(6, life - 2)));
    }
    /** Delayed parcels vary their acceleration by ±45%, then all reach the server contact tick. */
    static double progress(double age, double flight, double speedSeed, double delaySeed) {
        double delay = clamp(delaySeed) * Math.min(2, flight * .4);
        double u = clamp((age - delay) / Math.max(1, flight - delay));
        double speed = .55 + .9 * clamp(speedSeed);
        return clamp(u + (speed - 1) * Math.sin(Math.PI * u) / Math.PI);
    }
    static double currentRadius(double progress, boolean contact) {
        double u = clamp(progress);
        return .9 - .6 * u + (contact ? .48 * Math.exp(-Math.pow((u - .88) / .12, 2)) : 0);
    }
    /** Needle tips are intentional; material bodies retain an .08-block full width. */
    static double halfWidth(double width) { return width <= 0 ? 0 : Math.max(.04, width); }
    private RiverPresentation() { }
}
