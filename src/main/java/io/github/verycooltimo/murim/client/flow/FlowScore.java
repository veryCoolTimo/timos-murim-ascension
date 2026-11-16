package io.github.verycooltimo.murim.client.flow;

import io.github.verycooltimo.murim.client.vfx.fx.FxTune;

/**
 * THE ONE ENTRY the effects of the left side read the game's score from (the fire, the core and the burst are separate elements that plug in here):
 * <ul>
 *   <li>{@link #progress()}  0..1 the drawn progress along the way (the director's smoothed s);</li>
 *   <li>{@link #pressure()}  0..1 the smoothed pressure (heat);</li>
 *   <li>{@link #fire()}      0..1 the fire level: one step up for every node passed or right fork, eased over about 0.4 s; a mistake drops it by
 *       {@code flow.scroll.dropSteps} steps over 0.4 s and it climbs back over {@code flow.scroll.dropBack} s;</li>
 *   <li>{@link #stepsDone()} / {@link #stepsTotal()} the plain counts, and {@link #event(Event)} where the director reports what happened.</li>
 * </ul>
 * Pure state, one writer ({@link FlowMeridianFx}), no rendering.
 */
final class FlowScore {
    private FlowScore() { }

    enum Event { NODE_PASSED, NODE_CLOSED, FORK_RIGHT, FORK_WRONG }

    private static boolean active;
    private static int total = 1, done;
    private static double progress, pressure, level, levelV, dipAge = 99, dipDepth;
    private static final double[] ST = new double[2];

    /** A new game: {@code steps} = narrow spots + forks of the run. */
    static void begin(int steps) {
        active = FlowScrollBoard.scrollMode(); total = Math.max(1, steps); done = 0; progress = pressure = 0; ST[0] = ST[1] = 0; dipAge = 99; dipDepth = 0;
    }

    static void end() { active = false; }

    static boolean active() { return active; }

    static int stepsDone() { return done; }

    static int stepsTotal() { return total; }

    static double progress() { return progress; }

    static double pressure() { return pressure; }

    /** Fire level 0..1 (with the dip of a mistake); falls back to {@code fallback} when no game feeds the score (the calm absorptions). */
    static double fire(double fallback) {
        if (!active) return fallback;
        double dip = 0;
        double in = FxTune.f("flow.scroll.dropIn", .4F), back = FxTune.f("flow.scroll.dropBack", 1.6F);
        if (dipAge < in + back) {
            double k = dipAge < in ? ease(dipAge / in) : 1 - ease((dipAge - in) / back);
            dip = dipDepth * k;
        }
        return Math.max(0, Math.min(1, ST[0] - dip));
    }

    /** 0..1 how deep the fire is dipped right now (for flakes and tests). */
    static double dip() { return dipAge < 2.0 ? Math.max(0, 1 - dipAge / 2.0) : 0; }

    static void event(Event e) {
        switch (e) {
            case NODE_PASSED, FORK_RIGHT -> done = Math.min(total, done + 1);
            case NODE_CLOSED, FORK_WRONG -> { dipAge = 0; dipDepth = FxTune.f("flow.scroll.dropSteps", 1.5F) / total; }
        }
    }

    static void update(double dt, double progressDrawn, double pressure01) {
        if (!active) return;
        progress = progressDrawn;
        pressure = pressure01;
        dipAge += dt;
        FlowMeridian.follow(ST, done / (double) total, FxTune.f("flow.scroll.fireOmega", 9F), dt);
        ST[0] = Math.max(0, Math.min(1, ST[0]));
    }

    private static double ease(double k) { k = Math.max(0, Math.min(1, k)); return k * k * (3 - 2 * k); }
}
