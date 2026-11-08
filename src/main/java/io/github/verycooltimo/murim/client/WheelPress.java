package io.github.verycooltimo.murim.client;

/** Tick-based V gesture, shared by live input and the capture cue. */
final class WheelPress {
    private int ticks;
    private boolean cancelled;
    private boolean clicked;

    void click() { clicked = true; }
    boolean hold() { return ++ticks >= 4 && !cancelled; }
    void cancel() { cancelled = true; }
    boolean release() {
        boolean tap = (ticks > 0 || clicked) && ticks < 4 && !cancelled;
        reset();
        return tap;
    }
    void reset() { ticks = 0; cancelled = false; clicked = false; }
}
