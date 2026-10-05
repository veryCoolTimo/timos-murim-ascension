package io.github.verycooltimo.murim.sect.seal;

import io.github.verycooltimo.murim.cultivation.Realm;

/**
 * Cold iron (寒鐵) rules without a server, so unit tests can check them (author 05.10: «only above Peak»).
 *
 * <p>A cold iron block gives way only to a cultivator above {@link Realm#PEAK} who strikes with qi: the first
 * strike on a block needs a nearly full circulating reserve ({@link #FULL}), then every tick of digging drains
 * qi; when the reserve runs dry the block stops yielding. Below the rank the block does not crack at all.
 */
public final class ColdIronRules {

    /** Lowest rank that can break cold iron: above Peak (the one constant to tune). */
    public static final int MIN_RANK = Realm.PEAK + 1;

    /** Ticks of holding attack to break one block with qi to spare (6 s: slow on purpose). */
    public static final int BREAK_TICKS = 120;

    /** Share of the circulating maximum a strike needs to begin («full qi»). */
    public static final double FULL = 0.9D;

    /** Share of the circulating maximum one full break costs (spread over {@link #BREAK_TICKS}). */
    public static final double COST = 0.6D;

    /** Broken cold iron is re-forged by the sect after this many ticks (10 game minutes), if the place is free. */
    public static final long REGROW_TICKS = 12_000L;

    /** A permitted cold iron door closes by itself after this many ticks. */
    public static final int DOOR_OPEN_TICKS = 100;

    /** A gap longer than this between digging ticks starts a new strike (needs full qi again). */
    public static final int STRIKE_GAP = 3;

    private ColdIronRules() {
    }

    public static boolean rankAllows(int rank) {
        return rank >= MIN_RANK;
    }

    /** Qi one tick of digging takes. */
    public static double costPerTick(double maxCirculating) {
        return maxCirculating * COST / BREAK_TICKS;
    }

    /** Can a new strike begin with this reserve. */
    public static boolean strikeReady(double circulating, double maxCirculating) {
        return maxCirculating > 0.0D && circulating >= maxCirculating * FULL;
    }

    /**
     * Destroy progress per tick.
     *
     * @param newStrike the player just began digging this block (needs full qi)
     */
    public static float progress(int rank, double circulating, double maxCirculating, boolean newStrike) {
        if (!rankAllows(rank) || maxCirculating <= 0.0D) {
            return 0.0F;
        }
        if (newStrike ? !strikeReady(circulating, maxCirculating) : circulating < costPerTick(maxCirculating)) {
            return 0.0F;
        }
        return 1.0F / BREAK_TICKS;
    }
}
