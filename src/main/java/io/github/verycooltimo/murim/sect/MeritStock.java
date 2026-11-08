package io.github.verycooltimo.murim.sect;

/**
 * The personal stock of a merit-shop item (docs/adr/0012). One unit comes back for every {@code minutes} of the player's
 * own time in the world (the vanilla {@code play_time} statistic, which counts the player's ticks and does not jump when the
 * night is slept through or the clock is set), up to {@code max}. Nothing here reads the day: sleeping, a new day or
 * {@code /time set} do not refill anything.
 *
 * <p>The state is two numbers in the sect journal per stocked item ({@code shop.<id>.n} units left, {@code shop.<id>.t} the
 * play-time tick when the clock of the next unit started); an item never touched has a full stock. The refill is computed
 * when it is read, so there is no tick handler. Pure arithmetic, tested without a world.
 */
public final class MeritStock {
    /** Ticks in a minute of play. */
    public static final long TICKS_PER_MINUTE = 1200L;

    private MeritStock() {
    }

    /** What is in stock and since when the next unit is being earned (meaningless while the stock is full). */
    public record Level(int count, long clock) {
    }

    public static String countKey(String id) {
        return "shop." + id + ".n";
    }

    public static String clockKey(String id) {
        return "shop." + id + ".t";
    }

    public static long periodTicks(MeritShop.Refill r) {
        return r.minutes() * TICKS_PER_MINUTE;
    }

    /** The stock at play time {@code now}: units earned since the clock started are added, never above {@code max}. */
    public static Level refill(int count, long clock, long now, MeritShop.Refill r) {
        if (count >= r.max()) {
            return new Level(r.max(), now);
        }
        if (now < clock) {
            // The play-time counter went back (the statistics file was reset): count from its new zero. The clock is not
            // moved to "now": a read does not save, so a sliding clock would never get to run out.
            clock = 0L;
        }
        long units = (now - clock) / periodTicks(r);
        if (units >= r.max() - count) {
            return new Level(r.max(), now);
        }
        return new Level(Math.max(0, count) + (int) units, clock + units * periodTicks(r));
    }

    /** The stock of this player's journal at play time {@code now}. */
    public static Level level(SectState s, String id, MeritShop.Refill r, long now) {
        int stored = (int) Math.max(0L, Math.min(r.max(), s.entry(countKey(id), r.max())));
        return refill(stored, s.entry(clockKey(id), now), now, r);
    }

    /** Ticks of play until the next unit; 0 when the stock is full. */
    public static long ticksToNext(Level level, long now, MeritShop.Refill r) {
        if (level.count() >= r.max()) {
            return 0L;
        }
        return Math.max(1L, periodTicks(r) - (now - level.clock()));
    }

    /** Whole minutes of play until the next unit, rounded up; 0 when the stock is full. */
    public static int minutesToNext(Level level, long now, MeritShop.Refill r) {
        return (int) ((ticksToNext(level, now, r) + TICKS_PER_MINUTE - 1L) / TICKS_PER_MINUTE);
    }

    /**
     * The journal after one unit is taken (the caller has checked {@code count > 0}). Taking from a full stock starts the
     * clock now; taking from a part-full one leaves the clock running, so a second unit does not delay the first.
     */
    public static SectState take(SectState s, String id, MeritShop.Refill r, long now) {
        Level l = level(s, id, r, now);
        long clock = l.count() >= r.max() ? now : l.clock();
        return s.record(countKey(id), Math.max(0, l.count() - 1)).record(clockKey(id), clock);
    }
}
