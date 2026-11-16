package io.github.verycooltimo.murim.cultivation;

/**
 * Length of the pill absorption scene (ordinary and middle pills): the full scene for the first three pills of a player,
 * from the fourth only the melting and the core beats. Pure, shared by the server (how long the player sits) and the tests.
 */
public final class PillScenePlanner {
    /** Scenes played in full before the short form begins. */
    public static final int FULL_SCENES = 3;
    /** The plan (client {@code PillScenePlan}) is written in 144 tick units; the scene plays this many times slower (author 15.11: «слишком быстро»). */
    public static final float SLOWDOWN = 1.5F;
    public static final int FULL_TICKS = 216;
    public static final int SHORT_TICKS = 105;
    /** Ticks the sitting lasts after the plan's end (the camera eases out over the last 10). */
    public static final int STAND_MARGIN = 24;

    private PillScenePlanner() { }

    /**
     * The cinematic scene belongs to MIDDLE-tier pills only (author 9.11): common pills keep the plain 2.5 s absorption because
     * players eat them often. A stack with any middle pill counts as middle; high pills and utility pills never get it.
     */
    public static boolean eligible(java.util.List<PillKind> kinds) {
        return PillService.shortAbsorption(kinds) && kinds.stream().anyMatch(k -> k.tier() == PillTier.MIDDLE);
    }

    /** @param seen scenes the player has seen so far; @param alwaysFull the client setting «full pill scene always» */
    public static int ticks(int seen, boolean alwaysFull) {
        return alwaysFull || seen < FULL_SCENES ? FULL_TICKS : SHORT_TICKS;
    }
}
