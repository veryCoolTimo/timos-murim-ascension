package io.github.verycooltimo.murim.training;

/**
 * Per-player training in progress on the server (attachment, not saved: a set lives here and now). Holds the set
 * machine, the two route runs and the carry tally. One instance per player — never shared, never static.
 */
public final class TrainingSession {

    final SetMachine set = new SetMachine();
    RouteRun climb;
    RouteRun trail;
    /** Trail run: ticks spent sprinting, ticks in all. */
    int trailSprint;
    int trailTicks;
    /** Carry: highest feet block reached with the stone since it was lifted; blocks gained. */
    boolean carrying;
    int carryTop;
    int carried;
    /** Previous tick position (movement check) and whether it is set. */
    double lastX;
    double lastY;
    double lastZ;
    boolean hasLast;
    /** Last sync to the client: what was shown, to send only on change. */
    int sentExercise = -1;
    int sentReps = -1;
    int sentNext = -1;
    long lastSync;
    /** Gain of the current set / run (for the end line). */
    double setGain;

    /** Exercise shown right now: a route run, the carry, or the set. */
    public Exercise current() {
        if (climb != null && climb.active()) {
            return Exercise.PEAK_CLIMB;
        }
        if (trail != null && trail.active()) {
            return Exercise.TRAIL_SPRINT;
        }
        if (carrying) {
            return Exercise.CARRY_STONE;
        }
        return set.exercise();
    }

    public SetMachine set() {
        return set;
    }

    public RouteRun climb() {
        return climb;
    }

    public RouteRun trail() {
        return trail;
    }
}
