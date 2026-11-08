package io.github.verycooltimo.murim.client.vfx;

/** Pure fractional-tick schedule; see docs/adr/bamboo-scale-2026-11-04.md, round 10. */
final class ClassicBambooGroveEnding {
    static final int START_TICK = 28;
    static final int END_TICK = 54;

    private ClassicBambooGroveEnding() {}

    static double pieceStart(int piece, int pieces, int stalk, int stalks) {
        // Pack actual pieces rather than reserving empty upper-shaft slots.
        return ClassicBambooEnding.start(START_TICK, END_TICK,
                piece * stalks + stalk, pieces * stalks);
    }

    static float pieceFade(float age, int piece, int pieces, int stalk, int stalks) {
        return ClassicBambooEnding.fade(age, pieceStart(piece, pieces, stalk, stalks));
    }
}
