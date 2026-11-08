package io.github.verycooltimo.murim.training;

import java.util.Locale;

/**
 * The explicit sect drills (docs/design/27-body-training.md §4). They are the only exercises left: each one is a physical
 * thing in the world that the player does on purpose (lift the stone, step on the first ledge, sprint from the lower
 * gate), never a key tap that can start by accident. Canon: carts hauled up the rock and the run to Lotus Peak
 * (ch. 339), cliffs «five times a day» (ch. 159).
 *
 * <p>One unit is a block of height gained with the stone (carry) or one finished run (climb, trail).
 *
 * @param points raw drill points per unit before the sect bonus; they pay through the {@link BodySource#DRILL} daily limit
 */
public enum Exercise {
    /** Carrying the training stone uphill; a unit is a block of new height. */
    CARRY_STONE(TrainingBalance.CARRY_EACH),
    /** South Peak climb, climb_1 → climb_16, no qi; a unit is a finished run. */
    PEAK_CLIMB(TrainingBalance.CLIMB_RUN),
    /** Sprint up the trail from the lower gate to the sect gate; a unit is a finished run. */
    TRAIL_SPRINT(TrainingBalance.TRAIL_RUN);

    private final double points;

    Exercise(double points) {
        this.points = points;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@code murim.training.exercise.<id>} */
    public String nameKey() {
        return "murim.training.exercise." + id();
    }

    public double points() {
        return points;
    }

    /** A timed route run rather than a carry. */
    public boolean route() {
        return this != CARRY_STONE;
    }

    /** Sync value: ordinal, −1 for none. */
    public static Exercise byIndex(int i) {
        return i >= 0 && i < values().length ? values()[i] : null;
    }
}
