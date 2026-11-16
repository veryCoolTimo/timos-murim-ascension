package io.github.verycooltimo.murim.client.flow;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The meridian of the high pill game on the player's own model (director's brief 15.11, section A). Pure maths: no world, no rendering.
 *
 * <p>The way is laid on the posed anatomy: dantian -> up the right side of the belly -> right chest -> right shoulder -> DOWN the right arm
 * (outer lane) to the palm and back UP (inner lane) -> across the collar line (below the neck, never on it) -> left shoulder -> down the left
 * arm to the palm and back -> left chest -> left side -> dantian. The torso part is laid in torso pixels (across, up from the torso's
 * bottom edge); the arm part on each arm's front face between the arm's own anchors, so the whole line follows the seated pose.
 * The way is a dense, arc-length parametrised polyline; {@code u} in 0..1 is the share of its length (the game's {@code s}).
 */
public final class FlowMeridian {
    private FlowMeridian() { }

    /** Torso frame: {@code o} the bottom centre of the torso's front face, {@code right} the screen right (model +x), {@code px} blocks per model pixel. */
    public record Body(Vec3 o, Vec3 right, Vec3 up, Vec3 out, double px) { }

    /** An arm's front face: the centre line from the shoulder cap to the hand, and {@code lat}, one model pixel across (screen right). */
    public record Arm(Vec3 top, Vec3 bot, Vec3 lat) { }

    public record Anchors(Body body, Arm rightArm, Arm leftArm) {
        /** From the raw bone points: torso origin (0,12,-2.2), +4 across, (0,0,-2.2) top, (0,12,-3.2) out; per arm top, bottom and one pixel across. */
        public static Anchors of(Vec3 o, Vec3 x, Vec3 y, Vec3 n, Vec3 rTop, Vec3 rBot, Vec3 rLat, Vec3 lTop, Vec3 lBot, Vec3 lLat) {
            Vec3 up = y.subtract(o);
            // The posed anchors read about 4 model pixels low on the seated body (measured on the Mac film 15.11 and on the 26.10 stand films, see
            // FlowBodySurface.LIFT_PX): every point is lifted along the torso's up.
            Vec3 lift = up.scale(LIFT_PX / 12);
            Body body = new Body(o.add(lift), x.subtract(o).normalize(), up.normalize(), n.subtract(o).normalize(), up.length() / 12);
            return new Anchors(body, new Arm(rTop.add(lift), rBot.add(lift), rLat.subtract(rTop)), new Arm(lTop.add(lift), lBot.add(lift), lLat.subtract(lTop)));
        }
    }

    public static final int SAMPLES = 360;
    /** Model pixels the posed anchors sit below the visible body. Round 3: 0, the 4 px of the first rounds were the missing view rotation of the AFTER_LEVEL stage (see FlowBodyRenderer). */
    public static final double LIFT_PX = 0.0D;

    /** The kind of body a part of the way lies on. */
    public enum Zone { TORSO, COLLAR, ARM }

    /** The way: {@code SAMPLES} points evenly spaced by arc length. */
    public static final class Path {
        public final Vec3[] p = new Vec3[SAMPLES];
        public final Vec3[] t = new Vec3[SAMPLES];
        public final double length;
        /** The zone of every sample. */
        public final Zone[] zone = new Zone[SAMPLES];
        /**
         * An arm is ONE channel: the way goes down its centre line to the palm and comes back up the same line. {@code second[i]} is true on the
         * way back; {@code mate[i]} is the sample of the other pass at the same place (-1 off the arm line). Only the first pass is drawn;
         * the second pass makes it burn brighter.
         */
        public final boolean[] second = new boolean[SAMPLES];
        public final int[] mate = new int[SAMPLES];
        /** Sample indices of the two palm turns. */
        public final int[] turns;

        Path(Vec3[] pts, Zone[] zones, double length, boolean[] second, int[] mate, int[] turns) {
            System.arraycopy(pts, 0, p, 0, SAMPLES);
            System.arraycopy(zones, 0, zone, 0, SAMPLES);
            System.arraycopy(second, 0, this.second, 0, SAMPLES);
            System.arraycopy(mate, 0, this.mate, 0, SAMPLES);
            this.turns = turns;
            this.length = length;
            for (int i = 0; i < SAMPLES; i++) {
                Vec3 d = p[Math.min(SAMPLES - 1, i + 1)].subtract(p[Math.max(0, i - 1)]);
                t[i] = d.lengthSqr() < 1e-12 ? new Vec3(0, 1, 0) : d.normalize();
            }
        }

        private static double idx(double u) { return Math.max(0, Math.min(1, u)) * (SAMPLES - 1); }

        public Vec3 at(double u) {
            double x = idx(u);
            int i = Math.min(SAMPLES - 2, (int) x);
            return p[i].lerp(p[i + 1], x - i);
        }

        public Vec3 tangent(double u) {
            double x = idx(u);
            int i = Math.min(SAMPLES - 2, (int) x);
            Vec3 v = t[i].lerp(t[i + 1], x - i);
            return v.lengthSqr() < 1e-12 ? t[i] : v.normalize();
        }

        public Zone zoneAt(double u) { return zone[(int) Math.round(idx(u))]; }

        /** Sample index of {@code u}. */
        public static int index(double u) { return (int) Math.round(idx(u)); }

        /** The share of the way of the drawn (first pass) place of {@code u}: on the way back up an arm it is the place of the way down. */
        public double canon(double u) {
            int i = index(u);
            return second[i] && mate[i] >= 0 ? mate[i] / (double) (SAMPLES - 1) : u;
        }
    }

    private record Ctl(Zone zone, boolean arm, boolean right, double a, double v) { }

    private static Ctl torso(double a, double v) { return new Ctl(Zone.TORSO, false, false, a, v); }
    private static Ctl collar(double a, double v) { return new Ctl(Zone.COLLAR, false, false, a, v); }
    /** {@code t} along the arm from the shoulder cap (0) to the hand (1), {@code lane} pixels across from the arm's centre line. */
    private static Ctl arm(boolean right, double t, double lane) { return new Ctl(Zone.ARM, true, right, lane, t); }

    // The way, in torso pixels (across a, up v from the bottom edge) and arm coordinates (t from the shoulder cap 0 to the hand 1, lane across in pixels).
    // Up the right side of the belly, into the right arm from its inner side; the arm is one channel down its centre line to the palm and back;
    // over the shoulder, across the collar line, into the left arm, back over the left shoulder, down the left side to the dantian.
    private static final double ARM_START = .36D, ARM_END = 1.04D;
    /** The arm channel lies on the outer edge of the arm's front face: pixels across from the centre line (right arm: screen left, left arm: screen right). */
    private static final double ARM_LANE = 1.1D;
    private static final Ctl[] PIECE1 = {torso(0.0, 3.2), torso(-1.5, 3.9), torso(-2.4, 5.4), torso(-2.8, 7.2), torso(-3.5, 8.6),
            arm(true, .25, 1.3), arm(true, .30, .5), arm(true, ARM_START, -ARM_LANE)};
    private static final Ctl[] PIECE2 = {arm(true, ARM_START, -ARM_LANE), arm(true, .30, -ARM_LANE), arm(true, .20, -ARM_LANE), arm(true, .10, -ARM_LANE), arm(true, .03, -ARM_LANE * .3),
            torso(-4.6, 11.2), torso(-3.7, 10.4), collar(-2.4, 9.9), collar(-1.2, 9.5), collar(0.0, 9.2), collar(1.2, 9.5), collar(2.4, 9.9),
            torso(3.6, 10.0), arm(false, .25, -1.3), arm(false, .30, -.5), arm(false, ARM_START, ARM_LANE)};
    private static final Ctl[] PIECE3 = {arm(false, ARM_START, ARM_LANE), arm(false, .30, ARM_LANE), arm(false, .20, ARM_LANE), arm(false, .10, ARM_LANE), arm(false, .03, ARM_LANE * .3),
            torso(4.6, 11.2), torso(3.8, 10.2), torso(3.2, 9.0), torso(2.7, 7.0), torso(2.2, 5.2), torso(1.3, 3.8), torso(0.0, 3.2)};

    private static Vec3 eval(Anchors an, Ctl c) {
        if (c.arm) {
            Arm a = c.right ? an.rightArm : an.leftArm;
            return a.top.add(a.bot.subtract(a.top).scale(c.v)).add(a.lat.scale(c.a));
        }
        Body b = an.body;
        return b.o.add(b.right.scale(c.a * b.px)).add(b.up.scale(c.v * b.px));
    }

    /** A Catmull-Rom spline through the controls (tangents from the neighbours, scaled by the square root of the chord), appended to the dense line. */
    private static void spline(Anchors an, Ctl[] ctls, List<Vec3> dense, List<Zone> dz, boolean skipFirst) {
        int n = ctls.length;
        Vec3[] ctl = new Vec3[n];
        double[] knot = new double[n];
        for (int i = 0; i < n; i++) {
            ctl[i] = eval(an, ctls[i]);
            knot[i] = i == 0 ? 0 : knot[i - 1] + Math.max(1e-4, Math.sqrt(ctl[i].distanceTo(ctl[i - 1])));
        }
        Vec3[] tan = new Vec3[n];
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
            tan[i] = ctl[b].subtract(ctl[a]).scale(1 / (knot[b] - knot[a]));
        }
        int per = 24;
        for (int i = 0; i < n - 1; i++) {
            double h = knot[i + 1] - knot[i];
            for (int k = (i == 0 && skipFirst) ? 1 : 0; k < per; k++) {
                double s = k / (double) per, s2 = s * s, s3 = s2 * s;
                dense.add(ctl[i].scale(2 * s3 - 3 * s2 + 1).add(tan[i].scale(h * (s3 - 2 * s2 + s)))
                        .add(ctl[i + 1].scale(-2 * s3 + 3 * s2)).add(tan[i + 1].scale(h * (s3 - s2))));
                dz.add(s < .5 ? ctls[i].zone : ctls[i + 1].zone);
            }
        }
        dense.add(ctl[n - 1]);
        dz.add(ctls[n - 1].zone);
    }

    /** Down the arm's centre line to the palm and back (the same points twice); returns {start, turn, end} indices in the dense line. */
    private static int[] armLine(Anchors an, boolean right, List<Vec3> dense, List<Zone> dz) {
        int steps = 40;
        int start = dense.size() - 1, turn;
        for (int k = 1; k <= steps; k++) {
            dense.add(eval(an, arm(right, ARM_START + (ARM_END - ARM_START) * k / steps, right ? -ARM_LANE : ARM_LANE)));
            dz.add(Zone.ARM);
        }
        turn = dense.size() - 1;
        for (int k = steps - 1; k >= 0; k--) {
            dense.add(eval(an, arm(right, ARM_START + (ARM_END - ARM_START) * k / steps, right ? -ARM_LANE : ARM_LANE)));
            dz.add(Zone.ARM);
        }
        return new int[] {start, turn, dense.size() - 1};
    }

    /** The way on this pose, resampled by arc length. */
    public static Path build(Anchors an) {
        List<Vec3> dense = new ArrayList<>();
        List<Zone> dz = new ArrayList<>();
        spline(an, PIECE1, dense, dz, false);
        int[] r = armLine(an, true, dense, dz);
        spline(an, PIECE2, dense, dz, true);
        int[] l = armLine(an, false, dense, dz);
        spline(an, PIECE3, dense, dz, true);
        int m = dense.size();
        double[] cum = new double[m];
        for (int i = 1; i < m; i++) cum[i] = cum[i - 1] + dense.get(i).distanceTo(dense.get(i - 1));
        double len = cum[m - 1];
        Vec3[] out = new Vec3[SAMPLES];
        Zone[] zones = new Zone[SAMPLES];
        int j = 1;
        for (int i = 0; i < SAMPLES; i++) {
            double d = len * i / (SAMPLES - 1);
            while (j < m - 1 && cum[j] < d) j++;
            double f = (d - cum[j - 1]) / Math.max(1e-9, cum[j] - cum[j - 1]);
            out[i] = dense.get(j - 1).lerp(dense.get(j), Math.max(0, Math.min(1, f)));
            zones[i] = dz.get(f < .5 ? j - 1 : j);
        }
        boolean[] second = new boolean[SAMPLES];
        int[] mate = new int[SAMPLES];
        java.util.Arrays.fill(mate, -1);
        int[] turns = new int[2];
        int ti = 0;
        for (int[] arm : new int[][] {r, l}) {
            double d0 = cum[arm[0]], dT = cum[arm[1]], d1 = cum[arm[2]];
            turns[ti++] = (int) Math.round(dT / len * (SAMPLES - 1));
            for (int i = 0; i < SAMPLES; i++) {
                double d = len * i / (SAMPLES - 1);
                if (d <= d0 || d > d1) continue;
                double reflected = 2 * dT - d;
                mate[i] = (int) Math.round(Math.max(0, Math.min(len, reflected)) / len * (SAMPLES - 1));
                second[i] = d > dT;
            }
        }
        return new Path(out, zones, len, second, mate, turns);
    }

    /** The surface lateral direction at a sample: along the surface, across the way. */
    public static Vec3 lateral(Path path, int i, Vec3 out) {
        Vec3 l = path.t[i].cross(out);
        return l.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : l.normalize();
    }

    /** Smooth 0..1..0 bump over {@code [c-w, c+w]}: zero value and zero slope at both ends. */
    public static double bump(double u, double c, double w) {
        double x = (u - (c - w)) / (2 * w);
        if (x <= 0 || x >= 1) return 0;
        double s = Math.sin(Math.PI * x);
        return s * s;
    }

    /** How far apart the two branches of a fork stand (blocks, each side) in a zone: the arm is narrow. */
    public static double forkSpread(Zone z) { return z == Zone.ARM ? .06 : .11; }

    /** Half-length of a fork in the share of the way. */
    public static final double FORK_HALF = .07;

    // ------------------------------------------------------------------ nodes (narrow spots)

    /** The game's own opening of a narrow spot, -1 (shut) .. 1 (wide): the sine its {@code open} flag thresholds at -0.15. Continuous. */
    public static double aperture(double t, double period, double phase) {
        return Math.sin(t * 6.283D / period + phase);
    }

    private static double smooth(double e0, double e1, double x) {
        double k = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return k * k * (3 - 2 * k);
    }

    /** Ring radius (blocks) of a node for an aperture value: a tight knot when shut, wide when open; continuous. */
    public static double nodeRadius(double a) { return .04 + .02 * smooth(-.55, .6, a); }

    /** 0..1 how much of the ring the hot knot fills: 1 when shut (below the game's -0.15), 0 when open. Continuous. */
    public static double nodeKnot(double a) { return 1 - smooth(-.5, .05, a); }

    // ------------------------------------------------------------------ the drawn s

    /**
     * Critically damped follower: {@code state = {x, v}} chases {@code target} with angular rate {@code omega}; exact for any step. A wrong fork's step
     * back of the game's {@code s} becomes a recoil of about half a second instead of a pop.
     */
    public static void follow(double[] state, double target, double omega, double dt) {
        double d = state[0] - target, e = Math.exp(-omega * dt), j = state[1] + omega * d;
        state[0] = target + (d + j * dt) * e;
        state[1] = (state[1] - omega * j * dt) * e;
    }

    /** Heat of the channel at an age (seconds since the clot passed): 1 at the head, a warm steady glow after about 3 s. */
    public static double heat(double age) { return STEADY + (1 - STEADY) * Math.exp(-age / 1.0D); }

    public static final double STEADY = .42D;

    /** Colour of the channel's glow for the pressure (0..100): the pill's colour, white from 30 %, red from 60 %. As floats r, g, b. */
    public static float[] pressureColour(int pill, double pressure) {
        double p = Math.max(0, Math.min(100, pressure)) / 100;
        float[] c = {((pill >> 16) & 255) / 255F, ((pill >> 8) & 255) / 255F, (pill & 255) / 255F};
        float[] white = {1F, 1F, 1F}, red = {.93F, .22F, .2F};
        double toWhite = smooth(.25, .5, p) * (1 - smooth(.55, .7, p));
        double toRed = smooth(.55, .85, p);
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            double v = c[i] + (white[i] * .55 + c[i] * .45 - c[i]) * toWhite;
            out[i] = (float) (v + (red[i] - v) * toRed);
        }
        return out;
    }
}
