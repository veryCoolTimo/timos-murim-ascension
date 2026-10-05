package io.github.verycooltimo.murim.sect.seal;

/**
 * The penance cave in numbers (author 03.10 p.7: «a few in-game days confined in the cave, meditation is faster
 * there; refusing costs merit»; docs/design/23-mount-hua-sect.md §6.3). All the knobs in one place; the rules are
 * pure so unit tests check them.
 *
 * <ul>
 *   <li>Offences: raising a hand on a brother outside a spar, forcing a closed place (each time the guard has to
 *       escalate). The first offence within {@link #WINDOW_DAYS} days is a warning, the {@link #OFFENCES}-th —
 *       the sentence.</li>
 *   <li>The sentence: {@link #DAYS} game days in the cave, or sooner — {@link #MEDITATION_QUOTA} ticks of meditation
 *       inside (meditation there is ×{@link #MEDITATION_FACTOR}). Refusing — −{@link #REFUSE_COST} merit.</li>
 * </ul>
 */
public final class PenanceRules {

    /** Offences within the window that bring the sentence. */
    public static final int OFFENCES = 2;
    /** Offences older than this many game days are forgiven. */
    public static final long WINDOW_DAYS = 3L;
    /** A flurry of blows on a brother counts once per this many ticks. */
    public static final int ASSAULT_GAP = 200;

    /** Sentence length in game days (one day — 20 real minutes). */
    public static final long DAYS = 2L;
    /** Or released sooner after this many ticks of meditation in the cell (5 real minutes). */
    public static final int MEDITATION_QUOTA = 6_000;
    /** Meditation in the cave gathers qi this much faster. */
    public static final double MEDITATION_FACTOR = 1.5D;
    /** Refusing the sentence costs this much merit. */
    public static final int REFUSE_COST = 10;
    /** The sentenced may move within this many blocks of the cell; further — the elders bring him back. */
    public static final double CELL_RADIUS = 6.0D;
    /** On release the cave door stands open this long (ticks). */
    public static final int RELEASE_DOOR_TICKS = 600;

    private PenanceRules() {
    }

    /**
     * Offences counted after a new one.
     *
     * @param before  offences on record
     * @param lastDay game day of the last one on record
     * @param today   game day now
     */
    public static int count(int before, long lastDay, long today) {
        return (today - lastDay > WINDOW_DAYS ? 0 : before) + 1;
    }

    public static boolean sentence(int count) {
        return count >= OFFENCES;
    }

    /** Day time (ticks) when a sentence that began at {@code start} ends by itself. */
    public static long until(long start) {
        return start + DAYS * 24_000L;
    }

    public static boolean served(long dayTime, long until, int meditated) {
        return dayTime >= until || meditated >= MEDITATION_QUOTA;
    }
}
