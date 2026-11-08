package io.github.verycooltimo.murim.training;

/**
 * Per-player drill in progress on the server (attachment, not saved: a carry or a run lives here and now). Holds the
 * two route runs and the carry tally. One instance per player — never shared, never static.
 */
public final class TrainingSession {

    RouteRun climb;
    RouteRun trail;
    /** Trail run: ticks spent sprinting, ticks in all. */
    int trailSprint;
    int trailTicks;
    /** Carry: highest feet block reached with the stone since it was lifted; blocks gained. */
    boolean carrying;
    int carryTop;
    /** Feet block where the stone was lifted. */
    int carryFrom;
    int carried;
    /** Previous tick position (movement check) and whether it is set. */
    double lastX;
    double lastY;
    double lastZ;
    boolean hasLast;
    /** Game tick of the last sync to the client. */
    long lastSync;
    /** Gain of the current carry / run (for the end line). */
    double setGain;

    /** Drill shown right now: a route run or the carry; null — none. */
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
        return null;
    }

    public RouteRun climb() {
        return climb;
    }

    public RouteRun trail() {
        return trail;
    }
}
