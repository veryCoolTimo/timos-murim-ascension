package io.github.verycooltimo.murim.client.flow;

/**
 * The visible motion of the flawless high pill lift (author 16.11: "he just floats up with no proper speed curve and sits down with no animation").
 * The server only says WHEN (PerfectLift: 110 ticks, rise to tick 42, burst at 52, descent from 80, landed at 110) and HOW HIGH; the client draws the
 * body (ClientPillState#lift in PillVfx, the stand in FlowBurstLift) and this class is the one place that says how the height moves.
 * Pure and client-free (LiftCurveTest). Heights in blocks above the seated rest position, ages in ticks (20 per second).
 *
 * <pre>
 *   0 -  6  anticipation: sink {@link Params#sink} (0.05) and gather, 0.3 s, smooth in and out
 *   6 - 34  launch: ease-out cubic to {@code max + overshoot}, velocity ramps in over the first 0.17 s so the start is not a snap
 *  34 - 46  settle: from the overshoot back to {@code max}, smooth
 *  46 - 80  hover: a slow bob ({@link Params#bob} 0.04 blocks, one period 2.5 s), faded in and out so it never pops
 *  80 -102  descent: smootherstep (eases in, brakes before the floor, zero speed at touchdown), 1.1 s
 * 102 -110  landing: the knees take it, a dip of {@link Params#dip} (0.06) and back, sin squared (zero speed both ends), 0.4 s
 * </pre>
 * The body is also squashed a little about its feet during the anticipation and the landing ({@link #squash}).
 */
public final class LiftCurve {
    public static final int ANTIC = 6, LAUNCH_END = 34, SETTLE_END = 46, DESCENT = 80, TOUCH = 102, TOTAL = 110;

    /** The numbers of the curve; {@link #DEFAULT} is what the game uses, the stand can tune them through FxTune. */
    public record Params(double sink, double overshoot, double bob, double bobPeriod, double dip, double squash) {
        public static final Params DEFAULT = new Params(.05, .04, .04, 2.5, .06, .03);
    }

    private LiftCurve() {}

    static double smooth(double k) { k = Math.clamp(k, 0, 1); return k * k * (3 - 2 * k); }
    static double smoother(double k) { k = Math.clamp(k, 0, 1); return k * k * k * (k * (6 * k - 15) + 10); }
    static double outCubic(double k) { k = Math.clamp(k, 0, 1); double q = 1 - k; return 1 - q * q * q; }

    public static double height(double age, double max) { return height(age, max, Params.DEFAULT); }

    /** Height at {@code age} ticks (fractional) for a lift whose hover height is {@code max}. Zero outside the scene. */
    public static double height(double age, double max, Params p) {
        if (age <= 0 || age >= TOTAL) return 0;
        double over = Math.min(p.overshoot(), .06 * max);
        if (age < ANTIC) return -p.sink() * smooth(age / ANTIC);
        if (age < LAUNCH_END) {
            double u = (age - ANTIC) / (LAUNCH_END - ANTIC);
            // ease-out cubic, started with a short ramp: the first 0.17 s the speed builds up instead of jumping
            double e = outCubic(u) * smooth(u / .12) + (1 - smooth(u / .12)) * 0;
            return -p.sink() + (max + over + p.sink()) * e;
        }
        if (age < SETTLE_END) return max + over * (1 - smooth((age - LAUNCH_END) / (SETTLE_END - LAUNCH_END)));
        if (age < DESCENT) return max + bob(age, p);
        if (age < TOUCH) {
            double b = bob(DESCENT, p) * (1 - smoother((age - DESCENT) / 6.0));   // the bob dies away in the first 0.3 s of the descent
            return (max + b) * (1 - smoother((age - DESCENT) / (TOUCH - DESCENT)));
        }
        double u = (age - TOUCH) / (TOTAL - TOUCH), s = Math.sin(Math.PI * u);
        return -p.dip() * s * s;
    }

    /** The hover bob: zero at the start of the hover, faded in over 0.5 s and out before the descent. */
    static double bob(double age, Params p) {
        double tau = (age - SETTLE_END) / 20.0, fade = smooth((age - SETTLE_END) / 10.0);
        return p.bob() * fade * Math.sin(2 * Math.PI * tau / p.bobPeriod());
    }

    public static double squash(double age) { return squash(age, Params.DEFAULT); }

    /** Vertical scale of the body about its feet: a gather in the anticipation, a squash on the landing, 1 elsewhere. */
    public static double squash(double age, Params p) {
        if (age <= 0 || age >= TOTAL) return 1;
        if (age < ANTIC) return 1 - p.squash() * .5 * smooth(age / ANTIC);
        if (age < ANTIC + 4) return 1 - p.squash() * .5 * (1 - smooth((age - ANTIC) / 4.0));
        if (age >= TOUCH) { double s = Math.sin(Math.PI * (age - TOUCH) / (TOTAL - TOUCH)); return 1 - p.squash() * s * s; }
        return 1;
    }

    /** Speed in blocks per second (for effects that follow the body), by a small central difference. */
    public static double speed(double age, double max, Params p) {
        return (height(age + .5, max, p) - height(age - .5, max, p)) * 20.0;
    }
}
