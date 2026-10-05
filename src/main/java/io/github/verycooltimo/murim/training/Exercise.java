package io.github.verycooltimo.murim.training;

import java.util.Locale;

/**
 * Body exercises (docs/design/27-body-training.md §2; canon: Return of the Mount Hua Sect — push-ups with shouts,
 * ch. 66; cliffs «five times a day», ch. 159; carts hauled up the rock and the run to Lotus Peak, ch. 339).
 *
 * <p>One unit is a rep (squat, push-up), a second held (horse stance), a block of height gained with the stone
 * (carry), or one finished run (climb, trail).
 *
 * @param beat     ticks per beat for rhythmic sets; 0 — no rhythm
 * @param points   tempering points per unit before quality, sect bonus and diminishing returns
 * @param fatigue  day fatigue per unit (0..1 scale)
 * @param stamina  set stamina per unit (0..1 scale); 0 — not limited by set stamina
 */
public enum Exercise {
    SQUAT(24, 1.0D, 0.006D, 0.07D),
    PUSHUP(28, 1.4D, 0.008D, 0.08D),
    /** Push-ups with the weight slab strapped on the back. */
    PUSHUP_WEIGHTED(36, 2.2D, 0.013D, 0.11D),
    /** Horse stance: the bottom of a squat held; a unit is a second. */
    HORSE_STANCE(20, 0.6D, 0.004D, 0.012D),
    /** Carrying the training stone uphill; a unit is a block of new height. */
    CARRY_STONE(0, 1.5D, 0.01D, 0.0D),
    /** South Peak climb, climb_1 → climb_16, no qi; a unit is a finished run. */
    PEAK_CLIMB(0, 40.0D, 0.15D, 0.0D),
    /** Sprint up the trail from the lower gate to the sect gate; a unit is a finished run. */
    TRAIL_SPRINT(0, 30.0D, 0.12D, 0.0D);

    private final int beat;
    private final double points;
    private final double fatigue;
    private final double stamina;

    Exercise(int beat, double points, double fatigue, double stamina) {
        this.beat = beat;
        this.points = points;
        this.fatigue = fatigue;
        this.stamina = stamina;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@code murim.training.exercise.<id>} */
    public String nameKey() {
        return "murim.training.exercise." + id();
    }

    public int beat() {
        return beat;
    }

    public boolean rhythmic() {
        return beat > 0 && this != HORSE_STANCE;
    }

    public double points() {
        return points;
    }

    public double fatigue() {
        return fatigue;
    }

    public double stamina() {
        return stamina;
    }

    /** A timed route run rather than a set in place. */
    public boolean route() {
        return this == PEAK_CLIMB || this == TRAIL_SPRINT;
    }

    public boolean pushup() {
        return this == PUSHUP || this == PUSHUP_WEIGHTED;
    }

    /** Sync value: ordinal, −1 for none. */
    public static Exercise byIndex(int i) {
        return i >= 0 && i < values().length ? values()[i] : null;
    }
}
