package io.github.verycooltimo.murim.quest.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Shape of Dry Stream Cave, the cave of Chung Myung's cache (quest 1), as pure functions of the cave's own frame - no
 * Minecraft types, so the walk-through is unit tested. {@code d} is depth into the rock (the mouth faces {@code -d}, towards
 * the sect), {@code w} is the lateral offset, {@code y} is relative to the entrance floor (floor blocks are at {@code y = -1}).
 *
 * <p>Layout, about 70 blocks deep: the open gully and the entrance hall; a ramp down to the lower water hall (the belt and
 * the note of the old seeker); beyond it the cave-in that blocks the direct way; a dry side gallery that climbs past soot marks
 * to the old campsite (box and the wall with five waves); a narrow cleft from the campsite out to the open air. The cave is a
 * stamped mound of rock: its roof is the mound's own profile, so the cave is closed whatever the mountain does around it.
 */
public final class DryStreamCaveShape {

    /** Mound footprint, in cave frame: an ellipse centred at {@code (CD, 0)}. */
    public static final int CD = 36, RD = 54, RW = 32;
    /** Peak height of the mound above the entrance floor. */
    public static final int PEAK = 24;
    /** Rock kept between the cave and the open air. */
    public static final int ROOF = 3;
    /** The gully in front of the mouth reaches this far out. */
    public static final int GULLY_FROM = -18;
    /** Where the mouth stops being a gully and the roof starts. */
    public static final int MOUTH = 2;
    /** The lowest cave floor, below the entrance. */
    public static final int DEEPEST = -9;

    /** Fixed points of the story, in cave frame {@code (d, w, y)} of the standing level. */
    public static final int[] BOX = {66, 3, 4};
    public static final int[] WALL = {73, 1, 5};
    public static final int[] BELT = {33, -7, -6};
    public static final int[] CAMPFIRE = {62, -2, 4};
    public static final int[] CUP = {-6, 2, 0};
    public static final int[] PINE = {-12, -7, 0};
    /** The cave-in: rubble fills the passage from the lower hall to the camp, a little gap is left under the roof. */
    public static final int RUBBLE_FROM = 47, RUBBLE_TO = 51;
    /** Soot marks start at the lower hall and follow the gallery. */
    public static final int[][] SOOT = {{33, 8, -5}, {36, 11, -3}, {40, 13, -1}, {45, 15, 1}};

    private DryStreamCaveShape() {
    }

    // ------------------------------------------------------------------ regions

    private interface Region {
        /** Normalised inside-ness: below 1 inside, smaller is deeper; above 1 outside. */
        double dist(int d, int w);

        double floor(int d, int w);

        double height(int d, int w);
    }

    private record Blob(double cd, double cw, double rd, double rw, double floor, double height) implements Region {
        @Override
        public double dist(int d, int w) {
            double a = (d - cd) / rd;
            double b = (w - cw) / rw;
            return Math.sqrt(a * a + b * b);
        }

        @Override
        public double floor(int d, int w) {
            return floor;
        }

        @Override
        public double height(int d, int w) {
            return height;
        }
    }

    /** A passage along a polyline of {@code {d, w, floor}}; the floor is interpolated along it. */
    private record Path(double[][] pts, double half, double height) implements Region {
        private double[] nearest(int d, int w) {
            double best = Double.MAX_VALUE;
            double floor = 0;
            for (int i = 0; i + 1 < pts.length; i++) {
                double ax = pts[i][0], ay = pts[i][1], bx = pts[i + 1][0], by = pts[i + 1][1];
                double vx = bx - ax, vy = by - ay;
                double t = Math.max(0, Math.min(1, ((d - ax) * vx + (w - ay) * vy) / (vx * vx + vy * vy)));
                double dist = Math.hypot(d - (ax + vx * t), w - (ay + vy * t));
                if (dist < best) {
                    best = dist;
                    floor = pts[i][2] + (pts[i + 1][2] - pts[i][2]) * t;
                }
            }
            return new double[] {best, floor};
        }

        @Override
        public double dist(int d, int w) {
            return nearest(d, w)[0] / half;
        }

        @Override
        public double floor(int d, int w) {
            return nearest(d, w)[1];
        }

        @Override
        public double height(int d, int w) {
            return height;
        }
    }

    private static final List<Region> REGIONS = new ArrayList<>();

    static {
        // the entrance hall and the ramp down to the lower hall
        REGIONS.add(new Blob(8, 0, 11, 10, 0, 10));
        REGIONS.add(new Path(new double[][] {{12, 0, 0}, {18, -3, -2}, {24, -5, -4}, {29, -6, -6}}, 3.5, 7));
        // the lower water hall, with its pool
        REGIONS.add(new Blob(40, -6, 12, 11, -6, 12));
        // the passage beyond the hall, up to the camp: the cave-in sits in it
        REGIONS.add(new Path(new double[][] {{48, -6, -6}, {52, -4, -3}, {56, -2, 0}, {58, 0, 3}}, 3.0, 6));
        // the dry gallery: from the lower hall's north wall, climbing, to the camp
        REGIONS.add(new Path(new double[][] {{33, 2, -6}, {36, 9, -5}, {40, 13, -3}, {46, 15, -1}, {52, 15, 1}, {57, 11, 3}, {60, 6, 4}}, 2.5, 5));
        // the old campsite
        REGIONS.add(new Blob(65, 2, 9, 9, 4, 9));
        // the cleft out: narrow, down to the open air on the south side
        REGIONS.add(new Path(new double[][] {{62, -5, 4}, {58, -12, 3}, {53, -19, 1}, {49, -26, 0}, {47, -36, 0}}, 1.6, 4));
    }

    // ------------------------------------------------------------------ the mound

    /** Hash noise in [-1, 1], stable. */
    static double noise(int a, int b, int c) {
        long h = a * 73856093L ^ b * 19349663L ^ c * 83492791L;
        h ^= h >>> 13;
        h *= 0x5bd1e995L;
        h ^= h >>> 15;
        return ((h & 0xFFFF) / 32767.5) - 1.0;
    }

    /** Smooth value noise at a coarse scale, so the roof rolls instead of jittering. */
    static double smooth(double d, double w, double scale, int salt) {
        double x = d / scale, z = w / scale;
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = x - x0, fz = z - z0;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = noise(x0, z0, salt), b = noise(x0 + 1, z0, salt), c = noise(x0, z0 + 1, salt), e = noise(x0 + 1, z0 + 1, salt);
        return (a + (b - a) * fx) * (1 - fz) + (c + (e - c) * fx) * fz;
    }

    /** The mound's top, relative to the entrance floor; below 0 means no rock stamped here (the mound has no edge wall). */
    public static int top(int d, int w) {
        double a = (d - CD) / (double) RD;
        double b = w / (double) RW;
        double r2 = a * a + b * b;
        if (r2 >= 1) return -1;
        double t = PEAK * Math.pow(1 - r2, 0.55) + 2.2 * smooth(d, w, 7, 1);
        return (int) Math.round(t);
    }

    /** True if the column may receive the stamp at all. */
    public static boolean inFootprint(int d, int w) {
        return d >= GULLY_FROM - 14 && d <= CD + RD + 1 && Math.abs(w) <= RW + 3;
    }

    // ------------------------------------------------------------------ the cave

    /** Floor level (first air block) of the cave column, or {@link Integer#MIN_VALUE} if the column holds no cave. */
    public static int floor(int d, int w) {
        if (d < MOUTH) {
            // the gully and the mouth: open to the sky from the standing level up
            return gully(d, w) ? 0 : Integer.MIN_VALUE;
        }
        Region best = null;
        double bestDist = 1.0;
        for (Region r : REGIONS) {
            double dist = r.dist(d, w);
            if (dist < bestDist) {
                bestDist = dist;
                best = r;
            }
        }
        if (best == null) return Integer.MIN_VALUE;
        int floor = (int) Math.round(best.floor(d, w) + 0.6 * smooth(d, w, 4, 2) * Math.min(1, (1 - bestDist) * 2));
        // the pool
        double pd = Math.hypot(d - 42, w + 8);
        if (pd < 5.5 && best == REGIONS.get(2)) {
            floor = pd < 3.5 ? -9 : -8;
        }
        return floor;
    }

    /** Ceiling (first rock block) of the cave column; {@code <= floor} means the column is solid. */
    public static int ceiling(int d, int w) {
        int f = floor(d, w);
        if (f == Integer.MIN_VALUE) return Integer.MIN_VALUE;
        if (d < MOUTH) return Integer.MAX_VALUE;
        Region best = null;
        double bestDist = 1.0;
        for (Region r : REGIONS) {
            double dist = r.dist(d, w);
            if (dist < bestDist) {
                bestDist = dist;
                best = r;
            }
        }
        double h = best.height(d, w) * (0.75 + 0.25 * Math.min(1, (1 - bestDist) * 4)) + 1.3 * smooth(d, w, 5, 3);
        int roof = top(d, w) - ROOF;
        // the cleft is cut open where the mound is too thin to roof it: it ends in a gully in the open air
        if (best == REGIONS.get(6) && roof < f + 5) return Integer.MAX_VALUE;
        // pool floor is below the hall's floor: the hall's height counts from its own level
        int base = best == REGIONS.get(2) ? -6 : f;
        return Math.min(base + (int) Math.round(h), roof);
    }

    private static boolean gully(int d, int w) {
        if (d < GULLY_FROM || d >= MOUTH) return false;
        double half = 4.5 + (MOUTH - d) * 0.12 + 0.8 * smooth(d, w, 5, 4);
        return Math.abs(w) <= half;
    }

    /** Air at a point of the cave frame. */
    public static boolean open(int d, int w, int y) {
        int f = floor(d, w);
        if (f == Integer.MIN_VALUE || y < f) return false;
        if (rubble(d, w, y)) return false;
        return y < ceiling(d, w);
    }

    /** Water of the pool: sources up to y = -7 (the surface is at the lower hall's floor level). */
    public static boolean water(int d, int w, int y) {
        return d >= MOUTH && y <= -7 && y >= floor(d, w) && open(d, w, y) && Math.hypot(d - 42, w + 8) < 5.5;
    }

    /** The cave-in: fills the passage between the lower hall and the camp except a gap too small to pass. */
    public static boolean rubble(int d, int w, int y) {
        if (d < RUBBLE_FROM || d > RUBBLE_TO) return false;
        int f = floor(d, w);
        if (f == Integer.MIN_VALUE || f > 4) return false;
        // only the passage itself, not the gallery or the hall
        if (Math.abs(w - passageW(d)) > 4.5) return false;
        return y < ceiling(d, w) - 1 && y >= f;
    }

    private static double passageW(int d) {
        return -6 + (d - 48) * 0.5;
    }

    /** Rock at a point inside the mound (no air, no rubble gap). */
    public static boolean rock(int d, int w, int y) {
        return y <= top(d, w) && !open(d, w, y);
    }

    /** Bounding box of everything the stamp touches: {@code {dMin, dMax, wMin, wMax, yMin, yMax}}. */
    public static int[] bounds() {
        return new int[] {GULLY_FROM - 14, CD + RD + 1, -RW - 3, RW + 3, DEEPEST - 6, PEAK + 14};
    }
}
