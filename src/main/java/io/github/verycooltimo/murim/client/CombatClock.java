package io.github.verycooltimo.murim.client;

/**
 * The timing of the combat HUD, without the game: how long a fight is remembered and how the HUD fades.
 * {@link CombatMode} feeds it what the client sees; tests feed it directly.
 *
 * <p>Author 18.10: the technique slots belong to a fight, not to a sword in the hand. A fight is remembered for
 * {@link #HOLD_TICKS} after the last sign of it, the HUD comes in over {@link #FADE_IN_TICKS} and goes out over
 * {@link #FADE_OUT_TICKS}.
 */
public final class CombatClock {

    /** How long a fight lasts after its last sign, ticks: 6 s. */
    public static final int HOLD_TICKS = 120;
    /** The HUD comes in over 0.3 s. */
    public static final int FADE_IN_TICKS = 6;
    /** and goes out over 1.5 s once the fight is over. */
    public static final int FADE_OUT_TICKS = 30;

    private int remaining;
    private float level;

    /** A sign of a fight: the memory starts again from the full {@link #HOLD_TICKS}. */
    public void engage() {
        remaining = HOLD_TICKS;
    }

    /** One client tick; {@code signalled}: the fight is going on right now (lock-on, a mob hunting, a hit this tick). */
    public void tick(boolean signalled) {
        if (signalled) {
            remaining = HOLD_TICKS;
        } else if (remaining > 0) {
            remaining--;
        }
        level = Math.max(0.0F, Math.min(1.0F, level + (remaining > 0 ? 1.0F / FADE_IN_TICKS : -1.0F / FADE_OUT_TICKS)));
    }

    /** Is a fight remembered right now. */
    public boolean active() {
        return remaining > 0;
    }

    /** How much of the HUD shows, 0..1, eased; {@code partial} is the fraction of the current tick. */
    public float presence(float partial) {
        float t = Math.max(0.0F, Math.min(1.0F, level + partial * (remaining > 0 ? 1.0F / FADE_IN_TICKS : -1.0F / FADE_OUT_TICKS)));
        return t * t * (3.0F - 2.0F * t);
    }

    public void reset() {
        remaining = 0;
        level = 0.0F;
    }
}
