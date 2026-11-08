package io.github.verycooltimo.murim.cultivation.flow;

import java.util.ArrayList;
import java.util.List;

/**
 * «Провести поток» - the pill absorption game (author 25.10, approved mockup plan-site/extra/mockups/pill-flow, variant A).
 *
 * <p>The pill's qi flows through the body by itself; the player restrains it ({@code hold}). Narrow spots pulse open and closed:
 * flowing through a closed one raises the pressure, so restrain before it. A flicker is a surge: hold, or the pressure spikes.
 * At a fork the light branch is the way ({@code side} -1 left, +1 right), the dark one is a blockage. Poison spans burn when
 * the flow runs long (break it in pulses). Holding builds a little back-pressure, so nobody holds for ever. The pill dissolves
 * on a timer. Pressure 100 is a backlash.
 *
 * <p>A port of {@code update()}/{@code finish()} of the mockup, step by step. Pure: no world, no rendering. The client runs it
 * from the server's seed and reports the result; the server derives the quality from the report itself ({@link #quality}).
 */
public final class FlowGame {

    public enum State { RUN, DONE, BURST, TIMEOUT, TORN }

    /** A narrow spot. */
    public static final class Narrow {
        public final double s;
        public final double phase;
        public final double period;
        public boolean open = true;

        Narrow(double s, double phase, double period) {
            this.s = s;
            this.phase = phase;
            this.period = period;
        }
    }

    /**
     * A fork. The light branch is {@code good}: -1 or +1 of the way's own sides (the dark one is the blockage). The player does not
     * think in the way's sides but in the screen's: the key of the left branch is -1 (A), of the right one +1 (D); {@code leftSide} says
     * which of the way's sides is the left branch on the screen, so {@code goodKey} is the key the player must press.
     */
    public static final class Fork {
        public final double s;
        public final int good;
        /** The way's side (-1 or +1) of the branch that lies on the left of the screen (the upper one when the way runs level). */
        public final int leftSide;
        /** The key of the light branch: -1 left, +1 right. */
        public final int goodKey;
        /** The key the player picked (-1, 0, +1). */
        public int chosen;
        public boolean done;
        public boolean wrong;

        Fork(double s, int good, int leftSide) {
            this.s = s;
            this.good = good;
            this.leftSide = leftSide;
            this.goodKey = good == leftSide ? -1 : 1;
        }

        /** The way's side of the branch a key points to. */
        public int sideOf(int key) {
            return key < 0 ? leftSide : -leftSide;
        }
    }

    /** A trail point: position on the picture and age in seconds. */
    public static final class Trail {
        public final double x;
        public final double y;
        public double age;

        Trail(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    public static final int TRAIL_MAX = 70;
    /** The choice at a fork counts from this far before it (share of the way): about a second at normal speed. */
    public static final double FORK_ZONE = 0.12D;

    // ---- the stream
    public final FlowCharacter pill;
    public final boolean hard;
    public final FlowRoute route;
    public final List<Narrow> narrows = new ArrayList<>();
    public final List<Fork> forks = new ArrayList<>();
    public final List<double[]> poison = new ArrayList<>();
    private final Rng rng;
    private final double wildRate;
    private final double speed;
    private final boolean poisonNature;

    // ---- the run
    public State state;
    /** Progress along the way, 0..1. */
    public double s;
    public double t;
    public double pressure;
    public double lost;
    public double braked;
    public double flowT;
    public double surgeT = -1.0D;
    public double bounceT = -1.0D;
    public double shake;
    public double flash;
    /** Seconds since the run ended. */
    public double end;
    public final List<Trail> trail = new ArrayList<>();
    /** Dissolve time, seconds. */
    public final double time;

    // ---- the player's hands
    public boolean hold;
    /** The branch key the player picked: -1 A (left), +1 D (right), 0 none yet. Reset when a fork is passed. */
    public int side;
    private boolean auto;

    /**
     * @param pill         the merged stream
     * @param seed         the server's seed
     * @param hard         the player's rank is one below the pill's: faster spots, more surges, more burning
     * @param torn         the body does not endure: no game, only the tearing
     * @param poisonNature the player's method has the poison nature: poison pills are easier
     */
    public FlowGame(FlowCharacter pill, long seed, boolean hard, boolean torn, boolean poisonNature) {
        this.pill = pill;
        this.hard = hard;
        this.poisonNature = poisonNature;
        this.rng = new Rng(Math.floorMod(seed, 999_999_999L) + 1L);
        this.route = new FlowRoute(pill.len());
        for (int i = 0; i < pill.narrows(); i++) {
            narrows.add(new Narrow(.16D + .7D * (i + .5D) / pill.narrows() + (rng.next() - .5D) * .06D, rng.next() * 6.28D,
                    pill.pulse() * (hard ? .72D : 1.0D) * (0.85D + rng.next() * .3D)));
        }
        for (int i = 0; i < pill.forks(); i++) {
            double fs = .22D + .62D * (i + .6D) / (pill.forks() + .2D);
            double[] dir = route.dir(fs);
            // the branch at side s lies at (-dy, dx) * s: the one with the smaller x is on the left (level way: the upper one)
            double nx = -dir[1];
            double ny = dir[0];
            int left = Math.abs(nx) > .2D ? (nx > 0 ? -1 : 1) : (ny > 0 ? -1 : 1);
            forks.add(new Fork(fs, rng.next() < .5D ? -1 : 1, left));
        }
        if (pill.poison()) {
            for (int i = 0; i < 3; i++) {
                double ps = .12D + .25D * i + rng.next() * .08D;
                poison.add(new double[] {ps, ps + .07D});
            }
        }
        double wild = pill.wild() * (poisonNature && pill.poison() ? 0.5D : 1.0D);
        this.wildRate = (wild + (hard ? .3D : 0.0D)) * (pill.hot() ? 1.3D : 1.0D);
        this.speed = 2.1D / pill.time() * (pill.cold() ? .85D : 1.0D);
        this.time = pill.time();
        this.state = torn ? State.TORN : State.RUN;
    }

    /** Quality of an absorption in percent (the mockup's {@code finish()}): clean 40..100, timeout by progress, burst 10. */
    public static double quality(State outcome, double progress, double lost) {
        return switch (outcome) {
            case DONE -> Math.max(40.0D, 100.0D - lost);
            case TIMEOUT -> Math.max(0.0D, Math.round((100.0D - lost) * progress * .8D));
            case BURST -> 10.0D;
            default -> 0.0D;
        };
    }

    public double quality() {
        return quality(state, s, lost);
    }

    /** True while the dissolve ring still burns. */
    public boolean running() {
        return state == State.RUN;
    }

    public boolean surging() {
        return surgeT >= 0.0D && surgeT < .38D;
    }

    /** Index of the narrow spot the clot is in, or null. */
    private Narrow inNarrow() {
        Narrow in = null;
        for (Narrow n : narrows) {
            if (Math.abs(s - n.s) < .022D) {
                in = n;
            }
        }
        return in;
    }

    private boolean inPoison() {
        for (double[] a : poison) {
            if (s > a[0] && s < a[1]) {
                return true;
            }
        }
        return false;
    }

    /** A bot's hands for the demo, the tests and the capture stand: restrain before closed spots and in a surge, break poison, pick the light branch. */
    public void autopilot() {
        Narrow in = null;
        Narrow ahead = null;
        for (Narrow n : narrows) {
            if (Math.abs(s - n.s) < .022D) {
                in = n;
            }
            if (ahead == null && n.s - s > 0 && n.s - s < .03D && !n.open) {
                ahead = n;
            }
        }
        hold = ahead != null || (in != null && !in.open) || (surgeT > .25D) || (inPoison() && flowT > .4D);
        for (Fork f : forks) {
            if (!f.done) {
                side = f.goodKey;
                break;
            }
        }
    }

    /** Switches the bot on: every {@link #update} runs {@link #autopilot} first. */
    public void setAuto(boolean on) {
        this.auto = on;
    }

    public boolean isAuto() {
        return auto;
    }

    /** One step. {@code dt} in seconds; the mockup caps a frame at 50 ms. */
    public void update(double dt) {
        t += dt;
        shake = Math.max(0.0D, shake - dt * 2);
        flash = Math.max(0.0D, flash - dt * 2.2D);
        if (state == State.DONE && end < .45D && end + dt >= .45D) {
            flash = .55D;
            shake = .5D;
        }
        if (state != State.RUN) {
            end += dt;
            return;
        }
        if (t > time) {
            finish(State.TIMEOUT);
            return;
        }
        for (Narrow n : narrows) {
            n.open = Math.sin(t * 6.283D / n.period + n.phase) > -.15D;
        }
        Narrow inNarrow = inNarrow();
        if (auto) {
            autopilot();
        }
        for (Fork f : forks) {
            if (!f.done && s > f.s - FORK_ZONE && s < f.s) {
                f.chosen = side;
            }
        }
        if (surgeT < 0.0D && rng.next() < wildRate * dt * .35D && s > .05D && s < .95D) {
            surgeT = 0.0D;
        }
        boolean braking = hold && bounceT < 0.0D;
        double hotK = pill.hot() ? 1.35D : 1.0D;
        double v = speed * (braking ? .1D : 1.0D);
        if (surgeT >= 0.0D) {
            surgeT += dt;
            if (surgeT > .38D && surgeT < .9D) {
                if (!braking) {
                    s = Math.min(1.0D, s + dt * .1D * hotK);
                    add(dt * 95 * hotK);
                    shake = .6D;
                } else {
                    pressure = Math.min(100.0D, pressure + dt * 8);
                }
            }
            if (surgeT > .9D) {
                surgeT = -1.0D;
            }
        }
        if (bounceT >= 0.0D) {
            bounceT += dt;
            v = 0.0D;
            if (bounceT > .8D) {
                bounceT = -1.0D;
            }
        }
        boolean inP = inPoison();
        double burn = poisonNature && pill.poison() ? .6D : 1.0D;
        if (braking) {
            flowT = 0.0D;
            pressure = Math.min(100.0D, pressure + dt * 6);
            braked += dt;
        } else if (bounceT < 0.0D) {
            flowT += dt;
            if (inNarrow != null && !inNarrow.open) {
                v *= .5D;
                add(dt * 60 * (hard ? 1.3D : 1.0D));
                shake = Math.max(shake, .35D);
            } else if (inP && flowT > .55D) {
                add(dt * 55 * burn);
                shake = Math.max(shake, .25D);
            } else {
                pressure = Math.max(0.0D, pressure - dt * 12);
            }
        }
        s = Math.min(1.0D, s + v * dt);
        for (Fork f : forks) {
            if (!f.done && s >= f.s) {
                f.done = true;
                if (f.chosen != f.goodKey) {
                    add(28);
                    shake = .9D;
                    s = Math.max(0.0D, f.s - .035D);
                    bounceT = 0.0D;
                    f.wrong = true;
                }
                side = 0;
            }
        }
        double[] pos = route.at(s);
        trail.add(new Trail(pos[0], pos[1]));
        if (trail.size() > TRAIL_MAX) {
            trail.remove(0);
        }
        for (Trail q : trail) {
            q.age += dt;
        }
        if (pressure >= 100.0D) {
            finish(State.BURST);
            return;
        }
        if (s >= 1.0D) {
            finish(State.DONE);
        }
    }

    /** Short automatic scene: the same route, clot, trail and finale, completed in 2.5 seconds. */
    public void updateAutomatic(double dt) {
        if (state != State.RUN) {
            update(dt);
            return;
        }
        t += dt;
        s = Math.min(1.0D, t / 2.5D);
        for (Narrow n : narrows) n.open = true;
        for (Fork f : forks) {
            f.chosen = f.goodKey;
            f.done = s >= f.s;
        }
        double[] pos = route.at(s);
        trail.add(new Trail(pos[0], pos[1]));
        if (trail.size() > TRAIL_MAX) trail.remove(0);
        for (Trail point : trail) point.age += dt;
        if (s >= 1.0D) finish(State.DONE);
    }

    /** The session was cut by the server (a blow, a stand-up): the stream stops, the picture fades. */
    public void abort() {
        if (state == State.RUN) {
            state = State.TIMEOUT;
            end = 0.0D;
        }
    }

    private void add(double x) {
        pressure = Math.min(100.0D, pressure + x);
        lost += x * .45D;
    }

    private void finish(State outcome) {
        state = outcome;
        end = 0.0D;
        flash = outcome == State.DONE ? 0.0D : .6D;
    }

    /** The mockup's minimal-standard generator, so a seed always gives the same stream. */
    private static final class Rng {
        private long state;

        Rng(long seed) {
            this.state = seed;
        }

        double next() {
            state = (state * 16807L) % 2147483647L;
            return (state - 1) / 2147483646.0D;
        }
    }
}
