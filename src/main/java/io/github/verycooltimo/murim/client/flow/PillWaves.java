package io.github.verycooltimo.murim.client.flow;

/**
 * Air waves of the pill scene (refs pills-ar «absorbing snowy pill»: thin bright ribbons looping round the seated body in figure-eights;
 * heavenly pill: spiral bands that tighten on the core). Pure maths in the body frame (x screen right, y up from the belly, z towards the
 * lens, blocks). A wave is a bright head that travels round the body on its own loop; the tail is the same loop behind the head, so the
 * ribbon MOVES and never sways. {@code gather} 0..1 tightens every loop onto the belly, {@code spread} > 0 throws them outwards (the burst).
 */
public final class PillWaves {
    public static final int MAX_LOOPS = 7;

    private PillWaves() { }

    /** Radius of loop {@code i} in blocks at rest: all stay close to the body (arms reach about 0.38). */
    public static double radius(int i) { return .27 + .025 * (i % 3) + .01 * (i / 3); }

    /** Angular speed of the head, rad/s; the loops turn both ways. */
    public static double omega(int i) { return (i % 2 == 0 ? 1 : -1) * (1.35 + .22 * (i % 4)); }

    /** Where the head of loop {@code i} is at {@code t} seconds, as an angle. */
    public static double head(int i, double t) { return omega(i) * t + i * 2.399; }

    /** Point of loop {@code i} at angle {@code a}; y is relative to the belly. */
    public static double[] point(int i, double a, double gather, double spread) {
        double r = radius(i) * (1 - .72 * gather) * (1 + 2.6 * spread);
        double wobble = 1 + .12 * Math.sin(2 * a + i * 1.3);
        double tilt = .09 + .045 * (i % 4);
        double x = Math.cos(a) * r * wobble;
        double z = Math.sin(a) * r * wobble;
        // Figure-eight in height: the loop rises on one side and dips on the other; its mean height climbs with the index.
        double y = (.01 + .03 * i) * (1 - .8 * gather) + Math.sin(2 * a + i) * (.09 - .025 * (i % 3)) * (1 - gather)
                + (Math.cos(a) * Math.sin(i * 1.9) * tilt * 1.4) * (1 - gather);
        return new double[] {x, y, z};
    }
}
