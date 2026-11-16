package io.github.verycooltimo.murim.client.vfx.fx;

/**
 * The automatic quality governor of the cultivation aura (author 14.11: «чтобы не взрывало компы»). It is fed the frame time of the game
 * (FxPerf#lastFrameMs) and walks over four tiers: 0 full, 1 fewer particles and a coarser glow mesh, 2 fewer particles, thin branches dropped,
 * 3 the cheapest. It steps DOWN after the smoothed frame time has been above {@link #SLOW_MS} for {@link #DOWN_HOLD} seconds and UP again
 * after it has stayed under {@link #FAST_MS} for {@code upHold} seconds; the hold grows when an upward step is taken back soon after, so
 * the tiers never flicker. Particles do not pop: the particle share slews smoothly and only governs how many are BORN, the old ones die of age.
 * Pure and client-free (unit test FxQualityTest).
 */
public final class FxQuality {
    public static final int TIERS = 4;
    /** Share of the particles and of the dust puffs per tier. */
    public static final double[] COUNT = {1, .65, .4, .2};
    /** Glow mesh cells per side, ground spokes and rings per tier. */
    public static final int[] GRID = {10, 8, 6, 5}, SPOKES = {24, 18, 14, 10}, RINGS = {8, 6, 5, 4};
    /** Thin branches (traced mean half-width, blocks) that are not drawn at a tier. */
    public static final double[] DETAIL_CUT = {0, 0, .03, .05};
    public static final double SLOW_MS = 24, FAST_MS = 18.5, DOWN_HOLD = 1.5, UP_HOLD = 5, MAX_HOLD = 40, SMOOTH = .5, SLEW_DOWN = .5, SLEW_UP = .3;

    private double ema = -1, sinceChange, upHold = UP_HOLD, sinceUp = 1e9, scale = 1, overFor, underFor;
    private int tier;
    private boolean forced;

    /** Pins the tier (dev and test: {@code -Dmurim.fx.tier=n}); -1 releases it. */
    public void force(int t) { forced = t >= 0; if (forced) { tier = Math.min(TIERS - 1, t); scale = COUNT[tier]; } }

    /** One frame: {@code frameMs} is the time the last frame took, {@code dt} the seconds since the previous call. */
    public void update(double frameMs, double dt) {
        dt = Math.clamp(dt, 0, .25);
        frameMs = Math.clamp(frameMs, 0, 100);        // one hitch must not read as a slow game
        ema = ema < 0 ? frameMs : ema + (frameMs - ema) * Math.min(1, dt / SMOOTH);
        sinceChange += dt; sinceUp += dt;
        if (forced) return;
        overFor = ema > SLOW_MS && frameMs > FAST_MS ? overFor + dt : 0;   // the smoothed time lags: a frame that is already fast does not count
        underFor = ema < FAST_MS ? underFor + dt : 0;
        if (overFor >= DOWN_HOLD && tier < TIERS - 1) {
            if (sinceUp < 10) upHold = Math.min(MAX_HOLD, upHold * 2);   // we went up and it was too much: wait longer next time
            tier++; sinceChange = 0; overFor = 0; underFor = 0;
        } else if (underFor >= upHold && tier > 0) {
            tier--; sinceChange = 0; sinceUp = 0; underFor = 0;
        }
        if (tier == 0 && sinceChange > 30) upHold = UP_HOLD;
        double target = COUNT[tier], rate = (scale > target ? SLEW_DOWN : SLEW_UP) * dt;
        scale = scale > target ? Math.max(target, scale - rate) : Math.min(target, scale + rate);
    }

    public int tier() { return tier; }
    /** The smooth share of particles and dust (1 .. 0.2), for the number born per second. */
    public double particleScale() { return scale; }
    public int grid() { return GRID[tier]; }
    public int spokes() { return SPOKES[tier]; }
    public int rings() { return RINGS[tier]; }
    public double detailCut() { return DETAIL_CUT[tier]; }
    public double smoothedMs() { return ema; }
}
