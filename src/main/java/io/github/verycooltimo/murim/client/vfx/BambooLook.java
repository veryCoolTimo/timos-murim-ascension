package io.github.verycooltimo.murim.client.vfx;

/** Presentation contract from docs/adr/bamboo-looks-2026-11-05.md. Times are fractional 50 ms ticks. */
final class BambooLook {
    enum Shape { PALM, WALL, BLADE_SHARDS, BEND, HAND_PALM, GROVE, TOUCH, LEAVES }
    static final double MIN_STRIP = .08;
    static double stripHalfWidth(double width) { return Math.max(MIN_STRIP, width) * .5; }
    static final int WALL_STEMS = 9;
    static final int GROVE_STEMS = 6;
    static double stemThickness(long seed, int index) { return .30 + noise(seed, index, 3) * .15; }
    static double wallHeight(long seed, int index) { return 1.8 + noise(seed, index, 2) * 1.2; }
    static double groveHeight(long seed, int index) { return 2.3 + noise(seed, index, 2) * 1.7; }
    static boolean mastered(int stars) { return stars == 12; }
    // BambooPayload kind numbers are stable wire constants; keep this policy independent of Minecraft initialization.
    static Shape shape(int kind) {
        return switch (kind) {
            case 3 -> Shape.PALM;
            case 4 -> Shape.WALL;
            case 5 -> Shape.BLADE_SHARDS;
            case 2 -> Shape.BEND;
            case 7 -> Shape.HAND_PALM;
            case 9 -> Shape.GROVE;
            case 8 -> Shape.TOUCH;
            default -> Shape.LEAVES;
        };
    }
    static int lifetime(int kind) { return kind == 9 ? 44 : kind == 11 ? 10 : 30; }
    static double noise(long seed, int piece, int channel) {
        long x = seed + piece * 0x9E3779B97F4A7C15L + channel * 0x632BE59BD9B4E019L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return ((x ^ (x >>> 31)) >>> 11) * 0x1.0p-53;
    }
    static double smooth(double t) { t = Math.clamp(t, 0, 1); return t * t * (3 - 2 * t); }
    static float fade(double age, double born, double end) {
        return (float) (smooth((age - born) / .4) * (1 - smooth((age - (end - 6)) / 6)));
    }
    static double breakAt(long seed, int piece, boolean grove) {
        return (grove ? 16 : 4) + noise(seed, piece, 7) * (grove ? 12 : 10);
    }
    static double endAt(long seed, int piece, boolean grove) { return breakAt(seed, piece, grove) + 12; }
    static double palmEnd(long seed, int piece) { return 16 + noise(seed, piece, 1) * 10; }
    static double shardEnd(long seed, int piece) { return 14 + noise(seed, piece, 7) * 10; }
    static double bendEnd(long seed, int piece) { return 17 + noise(seed, piece, 0) * 5; }
    static double ribbonEnd(long seed, int piece, boolean turned) { return (turned ? 18 : 26) + noise(seed,piece,0)*6; }
    static float pulse(double age, double ticks) {
        return (float) (smooth(age / .16) * (1 - smooth(age / ticks)));
    }
    private BambooLook() {}
}
