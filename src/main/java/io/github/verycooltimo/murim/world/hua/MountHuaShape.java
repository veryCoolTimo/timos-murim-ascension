package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.world.hua.MountHuaPlan.Gorge;
import io.github.verycooltimo.murim.world.hua.MountHuaPlan.Peak;
import io.github.verycooltimo.murim.world.hua.MountHuaPlan.Ridge;
import io.github.verycooltimo.murim.world.hua.MountHuaPlan.TrailPoint;
import io.github.verycooltimo.murim.world.hua.MountHuaPlan.Zone;
import java.util.List;

/**
 * Pure height function of Mount Hua in the local frame (see {@link MountHuaPlan}).
 *
 * <p>No Minecraft types: the same object drives the chunk writer and the offline preview renderer
 * ({@code src/test/.../MountHuaPreview}). Every value depends only on the seed and (u, v), so chunks
 * can be generated in any order on any thread.
 *
 * <p>Shape language (real Huashan): granite monoliths with rounded bald tops and stacked sheer walls
 * separated by ledges (pines grow on the ledges), vertical ribs and couloirs on every wall, a
 * knife-edge ridge, high saddles, deep narrow gorges, and an abrupt rise from a hilly foot.
 */
public final class MountHuaShape {

    /** Local bounding box of everything the mountain touches (apron included). */
    public static final double MIN_U = -900;
    public static final double MAX_U = 900;
    public static final double MIN_V = -1000;
    public static final double MAX_V = 1080;

    /** Approach belt (Qinling foothills) around the massif: ellipse half-axes and centre v. */
    private static final double BELT_U = 790;
    private static final double BELT_V = 960;
    private static final double BELT_V0 = 40;

    /** Lateral/southern extent of the massif (ellipse half-axes; the north is the scarp line). */
    private static final double SIDE_U = 340;
    private static final double SOUTH_V0 = 40;
    private static final double SOUTH_V = 440;
    /** Fang lattice cell: one lesser granite peak per cell. */
    private static final double CELL = 44;

    private final long seed;
    private final HuaNoise warp;
    private final HuaNoise relief;
    private final HuaNoise flute;
    private final HuaNoise hills;

    public MountHuaShape(long seed) {
        this(seed, true);
    }

    /** {@code climb = false}: the terrain without the training climb (tests compare the two). */
    MountHuaShape(long seed, boolean climb) {
        this.seed = seed;
        this.warp = new HuaNoise(seed * 31 + 1);
        this.relief = new HuaNoise(seed * 31 + 2);
        this.flute = new HuaNoise(seed * 31 + 3);
        this.hills = new HuaNoise(seed * 31 + 4);
        // The stair profile was approved on the rounder rock (hua-overview-ok); it is computed
        // from that rock so the later reshaping (sharper peaks, spires) leaves the stair as it is.
        legacyRock = true;
        buildTrail();
        legacyRock = false;
        buildSpires();
        this.climb = climb;
        if (climb) {
            buildClimbSpots();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Training climb (author2/sect-high-on-mountain.png): rock lobes with flat mossy tops stepping
    // up the South Peak's north face behind the ancestors' hall. Only inside CLIMB_BOX, never on a
    // terrace (+2 blocks); applied after the terraces' grading, so the pads stay exact.

    /** Local box the climb may change: {minU, maxU, minV, maxV}. */
    static final double[] CLIMB_BOX = {-42, 52, 96, 122};

    private final boolean climb;
    /** Ledges sorted by height: a higher ledge's top wins where two overlap. */
    private static final List<MountHuaPlan.Ledge> CLIMB_ORDER = MountHuaPlan.CLIMB.stream()
            .sorted(java.util.Comparator.comparingDouble(MountHuaPlan.Ledge::y)).toList();
    /** Per ledge (plan order): the stand spot {u, v} — a flat column near the centre, null if none. */
    private double[][] climbSpot;
    /** Per ledge (plan order): rock seat columns {u0, v0, u1, v1} on rests, else null. */
    private double[][] climbSeat;
    /**
     * Rope posts at the hardest route steps (rise ≥ 5): {lowerU, lowerV, upperU, upperV} — a column
     * of the lower ledge and its neighbour on the upper one.
     */
    private final java.util.List<double[]> climbRopes = new java.util.ArrayList<>();

    public static boolean inClimbBox(double u, double v) {
        return u >= CLIMB_BOX[0] && u <= CLIMB_BOX[1] && v >= CLIMB_BOX[2] && v <= CLIMB_BOX[3];
    }

    /** True within {@code margin} blocks of a building pad (caves excluded). */
    private static boolean onPad(double u, double v, double margin) {
        for (Zone z : MountHuaPlan.ZONES) {
            if (!z.cave() && Math.abs(u - z.u()) <= z.width() / 2.0 + margin
                    && Math.abs(v - z.v()) <= z.depth() / 2.0 + margin) {
                return true;
            }
        }
        return false;
    }

    /** Elliptic distance of (u, v) to a ledge, with a wandering rim: below 1 is on the ledge. */
    private double ledgeQ(MountHuaPlan.Ledge l, double u, double v) {
        double du = (u - l.u()) / l.ru();
        double dv = (v - l.v()) / l.rv();
        double a = Math.atan2(dv, du);
        double salt = l.u() * 0.37 + l.y() * 0.11;
        double rim = 1 + 0.18 * relief.noise(Math.cos(a) * 1.5 + salt, Math.sin(a) * 1.5, 111.0)
                + 0.07 * relief.noise(u / 2.3, v / 2.3, 113.0);
        return Math.hypot(du, dv) * rim;
    }

    /**
     * The face carved into lobes: a ledge top is flat at its height (fill and cut); in front of it
     * the rock rounds over its lip and drops sheer; behind it a rock wall rises 4 per block back to
     * the slope. Higher ledges are laid last, so where two meet, the riser is the upper one's lip.
     */
    private double climb(double u, double v, double h) {
        if (!inClimbBox(u, v) || onPad(u, v, 2)) {
            return h;
        }
        boolean onLedge = false;
        for (MountHuaPlan.Ledge l : CLIMB_ORDER) {
            double ex = u - l.u();
            double ey = v - l.v();
            if (Math.abs(ex) > l.ru() + 8 || Math.abs(ey) > l.rv() + 8) {
                continue;
            }
            double q = ledgeQ(l, u, v);
            if (q <= 1) {
                h = l.y();
                onLedge = true;
                continue;
            }
            double d = Math.hypot(ex, ey) * (1 - 1 / q);
            if (h < l.y()) {
                // Over bare rock the lip rounds off; over a lower ledge the riser is sheer, so the
                // ledge below keeps its depth.
                h = Math.max(h, onLedge ? l.y() - 2 * d - 20 * d * d : l.y() - 1.2 * d - 2.5 * d * d);
            } else {
                double cut = Math.min(h, l.y() + 4.0 * d);
                h = cut + (h - cut) * smooth(4, 7, d);
            }
        }
        return h;
    }

    /** The climb ledge whose flat top covers (u, v) (the highest if several), or null. */
    public MountHuaPlan.Ledge climbLedgeAt(double u, double v) {
        if (!climb || !inClimbBox(u, v) || onPad(u, v, 2)) {
            return null;
        }
        MountHuaPlan.Ledge best = null;
        for (MountHuaPlan.Ledge l : CLIMB_ORDER) {
            if (Math.abs(u - l.u()) <= l.ru() + 3 && Math.abs(v - l.v()) <= l.rv() + 3 && ledgeQ(l, u, v) <= 1) {
                best = l;
            }
        }
        return best;
    }

    /** True where the climb changed the ground (the writer uses natural rock and its own greenery there). */
    public boolean climbChanged(double u, double v) {
        if (!climb || !inClimbBox(u, v)) {
            return false;
        }
        return Math.abs(height(u, v, true) - height(u, v, false)) > 1e-9;
    }

    private void buildClimbSpots() {
        List<MountHuaPlan.Ledge> plan = MountHuaPlan.CLIMB;
        climbSpot = new double[plan.size()][];
        climbSeat = new double[plan.size()][];
        for (int i = 0; i < plan.size(); i++) {
            MountHuaPlan.Ledge l = plan.get(i);
            // Block centres sit on half-integers in the local frame for every rotation.
            double best = Double.MAX_VALUE;
            double seatBest = Double.MAX_VALUE;
            for (double u = Math.floor(l.u() - l.ru()) + 0.5; u <= l.u() + l.ru(); u++) {
                for (double v = Math.floor(l.v() - l.rv()) + 0.5; v <= l.v() + l.rv(); v++) {
                    if (!flatOn(l, u, v)) {
                        continue;
                    }
                    boolean wide = flatOn(l, u - 1, v) && flatOn(l, u + 1, v) && flatOn(l, u, v - 1) && flatOn(l, u, v + 1);
                    double score = Math.hypot(u - l.u(), (v - l.v()) * 1.5) + (wide ? 0 : 6);
                    if (score < best) {
                        best = score;
                        climbSpot[i] = new double[] {u, v};
                    }
                    // Rock seat: two flat columns along the back wall of a rest ledge.
                    if (l.kind() == MountHuaPlan.Kind.REST && flatOn(l, u + 1, v)
                            && height(u, v + 1) >= l.y() + 2 && height(u + 1, v + 1) >= l.y() + 2) {
                        double s = Math.abs(u + 0.5 - l.u());
                        if (s < seatBest) {
                            seatBest = s;
                            climbSeat[i] = new double[] {u, v, u + 1, v};
                        }
                    }
                }
            }
        }
        // Hardest steps: a rope post where the upper ledge's rim meets the lower ledge.
        List<MountHuaPlan.Ledge> route = plan.stream().filter(MountHuaPlan.Ledge::onRoute).toList();
        for (int i = 0; i + 1 < route.size(); i++) {
            MountHuaPlan.Ledge lo = route.get(i);
            MountHuaPlan.Ledge hi = route.get(i + 1);
            if (hi.y() - lo.y() < 5) {
                continue;
            }
            double mu = (lo.u() + hi.u()) / 2;
            double mv = (lo.v() + hi.v()) / 2;
            double best = Double.MAX_VALUE;
            double[] pick = null;
            for (double u = Math.floor(lo.u() - lo.ru()) + 0.5; u <= lo.u() + lo.ru(); u++) {
                for (double v = Math.floor(lo.v() - lo.rv()) + 0.5; v <= lo.v() + lo.rv(); v++) {
                    if (!flatOn(lo, u, v)) {
                        continue;
                    }
                    for (double[] d : new double[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        double hn = height(u + d[0], v + d[1]);
                        // The upper ledge itself or the lip just in front of it.
                        if (hn >= lo.y() + 3 && hn <= hi.y() + 0.5) {
                            double s = Math.hypot(u - mu, v - mv) + (hi.y() - hn);
                            if (s < best) {
                                best = s;
                                pick = new double[] {u, v, u + d[0], v + d[1]};
                            }
                        }
                    }
                }
            }
            if (pick != null) {
                climbRopes.add(pick);
            }
        }
    }

    private boolean flatOn(MountHuaPlan.Ledge l, double u, double v) {
        return climbLedgeAt(u, v) == l && Math.abs(height(u, v) - l.y()) < 1e-9;
    }

    /** Stand spot {u, v} of a climb ledge (block centre on its flat top), or null. */
    public double[] climbSpot(MountHuaPlan.Ledge ledge) {
        int i = MountHuaPlan.CLIMB.indexOf(ledge);
        return climbSpot == null || i < 0 ? null : climbSpot[i];
    }

    /** Rock seat columns {u0, v0, u1, v1} of a rest ledge, or null. */
    public double[] climbSeat(MountHuaPlan.Ledge ledge) {
        int i = MountHuaPlan.CLIMB.indexOf(ledge);
        return climbSeat == null || i < 0 ? null : climbSeat[i];
    }

    /** Rope posts at the hardest steps: {lowerU, lowerV, upperU, upperV}. */
    public List<double[]> climbRopes() {
        return climbRopes;
    }

    // ---------------------------------------------------------------------------------------------
    // Spire forests (author 04.10: «больше маленьких пиков»): clusters of thin granite pinnacles,
    // 20-80 blocks above the ground they stand on, in the foothills, between the main peaks and
    // around the sect shelf as a backdrop. Never on the pads, the stair or the plank road.

    private static final int SPIRE_CELL = 64;
    /** Per cell: flat list of {u, v, radius, height, salt} spires (null = none). */
    private final java.util.Map<Long, double[]> spires = new java.util.HashMap<>();

    private void buildSpires() {
        for (int i = (int) Math.floor(MIN_U / SPIRE_CELL); i <= MAX_U / SPIRE_CELL; i++) {
            for (int j = (int) Math.floor(MIN_V / SPIRE_CELL); j <= MAX_V / SPIRE_CELL; j++) {
                long hsh = hash(i + 91019, j + 33331);
                double cu = (i + 0.25 + 0.5 * rnd(hsh, 1)) * SPIRE_CELL;
                double cv = (j + 0.25 + 0.5 * rnd(hsh, 2)) * SPIRE_CELL;
                double belt = beltDistance(cu, cv);
                double core = massif(cu, cv);
                double toSect = Math.hypot(cu, (cv - 40) * 0.9);
                // Fewer, tighter groups with clear gaps (codex: not needles strewn everywhere);
                // a ring of groups round the sect shelf, kept back from its skyline.
                double chance = belt >= 0.6 ? 0 : core > 0.3 ? 0.14 : 0.2 * (1 - smooth(0.3, 0.6, belt));
                if (toSect > 125 && toSect < 240) {
                    chance = 0.45;
                }
                if (toSect <= 125 || rnd(hsh, 3) >= chance) {
                    continue;
                }
                double near = toSect < 240 ? 0.6 : 1.0;
                int count = 4 + (int) (5 * rnd(hsh, 4));
                java.util.ArrayList<Double> list = new java.util.ArrayList<>();
                double spread = 8 + 8 * rnd(hsh, 5);
                double sumTall = 0;
                int kept = 0;
                for (int k = 0; k < count; k++) {
                    double ang = rnd(hsh, 10 + k) * Math.PI * 2;
                    double d = spread * Math.sqrt(rnd(hsh, 30 + k));
                    double u = cu + Math.cos(ang) * d;
                    double v = cv + Math.sin(ang) * d;
                    double r = 6 + 6 * rnd(hsh, 50 + k);
                    double tall = (20 + 48 * Math.pow(rnd(hsh, 70 + k), 2.0)) * near;
                    if (!spireAllowed(u, v, r * 1.8)) {
                        continue;
                    }
                    list.add(u);
                    list.add(v);
                    list.add(r);
                    list.add(tall);
                    list.add((double) ((hsh >>> (k * 3)) & 1023));
                    list.add(0.0);
                    sumTall += tall;
                    kept++;
                }
                if (kept >= 2 && spireAllowed(cu, cv, spread + 10)) {
                    // Shared rocky base the group stands in (bases embedded, not posts on a lawn).
                    list.add(cu);
                    list.add(cv);
                    list.add(spread + 14);
                    list.add(0.3 * sumTall / kept);
                    list.add((double) (hsh & 1023));
                    list.add(1.0);
                }
                if (kept > 0) {
                    double[] arr = new double[list.size()];
                    for (int k = 0; k < arr.length; k++) {
                        arr[k] = list.get(k);
                    }
                    spires.put(((long) i << 32) ^ (j & 0xffffffffL), arr);
                }
            }
        }
    }

    /** Spires keep clear of the pads, the stair, the plank road and the gate gorge. */
    private boolean spireAllowed(double u, double v, double r) {
        if (beltDistance(u, v) >= 0.97) {
            return false;
        }
        // Not on the high rock: a pinnacle on top of a peak reads as a needle over the skyline.
        if (Math.max(natural(u, v), belt(u, v)) > 150) {
            return false;
        }
        for (Zone z : MountHuaPlan.ZONES) {
            double dx = Math.max(0, Math.abs(u - z.u()) - z.width() / 2.0);
            double dz = Math.max(0, Math.abs(v - z.v()) - z.depth() / 2.0);
            if (Math.hypot(dx, dz) < r + 16) {
                return false;
            }
        }
        if (polyDistance(MountHuaPlan.TRAIL, u, v) < r + 14 || polyDistance(MountHuaPlan.PLANK_ROAD, u, v) < r + 10) {
            return false;
        }
        for (double[] p : MountHuaPlan.PATHS) {
            double[] q = pathAt(p, u, v);
            if (q[0] < r + 8) {
                return false;
            }
        }
        for (Gorge g : MountHuaPlan.GORGES) {
            for (int i = 0; i + 1 < g.u().length; i++) {
                if (segDistance(g.u()[i], g.v()[i], g.u()[i + 1], g.v()[i + 1], u, v) < r + 12) {
                    return false;
                }
            }
        }
        // The gate valley stays open (the approach view of the scarp).
        return !(Math.abs(u - (-104)) < 70 && v < MountHuaPlan.SCARP_V + 10);
    }

    private static double polyDistance(List<TrailPoint> pts, double u, double v) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < pts.size(); i++) {
            best = Math.min(best, segDistance(pts.get(i).u(), pts.get(i).v(), pts.get(i + 1).u(), pts.get(i + 1).v(), u, v));
        }
        return best;
    }

    private static double segDistance(double ax, double ay, double bx, double by, double u, double v) {
        double lx = bx - ax;
        double ly = by - ay;
        double t = clamp(((u - ax) * lx + (v - ay) * ly) / (lx * lx + ly * ly), 0, 1);
        return Math.hypot(u - (ax + lx * t), v - (ay + ly * t));
    }

    /** Extra height of the spire forests here (added on top of the ground). */
    private double spireLift(double u, double v) {
        int ci = (int) Math.floor(u / SPIRE_CELL);
        int cj = (int) Math.floor(v / SPIRE_CELL);
        double best = 0;
        for (int i = ci - 1; i <= ci + 1; i++) {
            for (int j = cj - 1; j <= cj + 1; j++) {
                double[] arr = spires.get(((long) i << 32) ^ (j & 0xffffffffL));
                if (arr == null) {
                    continue;
                }
                for (int k = 0; k < arr.length; k += 6) {
                    double du = u - arr[k];
                    double dv = v - arr[k + 1];
                    double r = arr[k + 2];
                    double salt = arr[k + 4];
                    if (arr[k + 5] > 0) {
                        // Group base: a rough rocky mound.
                        double q = Math.hypot(du, dv) / r * (1 + 0.25 * warp.noise(u / 9.0, v / 9.0, 63.0 + salt));
                        if (q < 1) {
                            best = Math.max(best, arr[k + 3] * Math.pow(1 - q * q, 1.2));
                        }
                        continue;
                    }
                    // Wide flared foot, tapering shaft with uneven shoulders.
                    if (Math.abs(du) > r * 2.2 || Math.abs(dv) > r * 2.2) {
                        continue;
                    }
                    double a = Math.atan2(dv, du);
                    double rim = 1 + 0.22 * warp.noise(Math.cos(a) * 1.7 + salt, Math.sin(a) * 1.7, 61.0)
                            + 0.10 * (1 - smooth(0, 0.08, Math.abs(flute.noise(Math.cos(a) * 3 + salt, Math.sin(a) * 3, 62.0))));
                    double dist = Math.hypot(du, dv) / rim;
                    double tall = arr[k + 3];
                    double shaft = dist < r ? tall * Math.pow(1 - dist / r, 0.3) : 0;
                    double foot = dist < r * 2.2 ? 0.55 * tall * Math.pow(1 - dist / (r * 2.2), 1.2) : 0;
                    // Blunt, broad crown that can carry pines and moss (author 04.10: no points).
                    shaft = Math.min(tall, shaft * 1.35);
                    double h = Math.max(shaft, foot);
                    if (h <= 0) {
                        continue;
                    }
                    // Sloping, sometimes split crown instead of a cut-off top.
                    double tilt = (du * Math.cos(salt) + dv * Math.sin(salt)) / r;
                    h -= 0.12 * tall * Math.max(0, tilt) * smooth(0.6, 0.95, h / tall);
                    if (((long) salt & 3) == 0) {
                        double split = Math.abs(du * Math.sin(salt) - dv * Math.cos(salt)) / r;
                        h -= 0.15 * tall * (1 - smooth(0.0, 0.18, split)) * smooth(0.7, 0.95, h / tall);
                    }
                    // Ledges on the way up (pines and moss sit there).
                    double band = 7 + (salt % 5);
                    double f = h / band;
                    h -= band * 0.35 * smooth(0.65, 1.0, f - Math.floor(f)) * smooth(0.25, 0.7, dist / r);
                    best = Math.max(best, h);
                }
            }
        }
        return best;
    }

    /** True only while the stair profile is computed: rock shapes as approved at hua-overview-ok. */
    private boolean legacyRock;

    public static boolean inBounds(double u, double v) {
        return u >= MIN_U && u <= MAX_U && v >= MIN_V && v <= MAX_V;
    }

    /** Elliptic distance for the sides and the south (1 = outer edge of the fang rows). */
    private double sideDistance(double u, double v) {
        double du = u / SIDE_U;
        double dv = Math.max(0, v - SOUTH_V0) / SOUTH_V;
        return Math.sqrt(du * du + dv * dv) + 0.08 * warp.fbm(u / 230.0, v / 230.0, 3);
    }

    /** Normalised distance to the outer edge of the foothill belt (1 = vanilla terrain begins). */
    public double beltDistance(double u, double v) {
        double du = u / BELT_U;
        double dv = (v - BELT_V0) / BELT_V;
        return Math.sqrt(du * du + dv * dv) + 0.06 * warp.fbm(u / 300.0 + 7.0, v / 300.0, 3);
    }

    /** 0 north of the fault scarp, 1 south of it; the step is ~10 blocks wide (a wall). */
    private double scarp(double u, double v, double width) {
        double line = MountHuaPlan.SCARP_V + 24 * warp.noise(u / 60.0, 3.3) + 8 * warp.noise(u / 17.0, 8.1);
        return smooth(line - width, line + width, v);
    }

    /** Massif strength: 1 in the core, fading towards the sides and the south, cut by the scarp. */
    public double massif(double u, double v) {
        return scarp(u, v, 5) * (1 - smooth(0.55, 1.0, sideDistance(u, v)));
    }

    /**
     * Weight of the mountain against vanilla terrain: 1 inside, 0 beyond the apron. The chunk writer
     * lerps the vanilla surface to the mountain by this weight, which makes the edge seamless and
     * lets the mountain cut vanilla hills (gorges, terraces) where the weight is 1.
     */
    public double blend(double u, double v) {
        if (!inBounds(u, v)) {
            return 0;
        }
        // The whole foothill belt follows the mountain; its outer 15% lerps into vanilla terrain.
        double w = 1 - smooth(0.85, 1.0, beltDistance(u, v));
        for (Zone z : MountHuaPlan.ZONES) {
            double dx = Math.max(0, Math.abs(u - z.u()) - z.width() / 2.0);
            double dz = Math.max(0, Math.abs(v - z.v()) - z.depth() / 2.0);
            w = Math.max(w, 1 - smooth(20, 50, Math.hypot(dx, dz)));
        }
        if (w < 1) {
            double[] t = trailAt(u, v);
            if (t != null) {
                w = Math.max(w, 1 - smooth(5, 12, t[0]));
            }
        }
        return w;
    }

    /** Nominal height above the foot (0 = foot level, {@link MountHuaPlan#SUMMIT} = South Peak). */
    public double height(double u, double v) {
        return height(u, v, climb);
    }

    private double height(double u, double v, boolean withClimb) {
        if (!inBounds(u, v)) {
            return 0;
        }
        double h = ground(u, v);
        for (Zone zone : MountHuaPlan.ZONES) {
            h = terrace(zone, u, v, h);
        }
        if (withClimb) {
            h = climb(u, v, h);
        }
        h = trail(u, v, h);
        h = paths(u, v, h);
        // Inside a terrace the level is exact (neighbouring terraces' walls never spill in).
        for (Zone zone : MountHuaPlan.ZONES) {
            if (!zone.cave() && !zone.id().equals("grove") && Math.abs(u - zone.u()) <= zone.width() / 2.0
                    && Math.abs(v - zone.v()) <= zone.depth() / 2.0) {
                h = zone.y();
            }
        }
        return Math.max(0, Math.min(MountHuaPlan.SUMMIT + 4, h));
    }

    /**
     * Natural ground: the massif, the foothill belt around it (rolling hills, spurs radiating from
     * the massif, satellite granite peaks) and the main valley that leads to the gate — the
     * approach of the real Huashan through the Qinling foothills (coordinator/author 03.10:
     * no flat plain with a mountain dropped on it).
     */
    public double ground(double u, double v) {
        double h = Math.max(natural(u, v), belt(u, v));
        if (!legacyRock) {
            h += spireLift(u, v);
        }
        // Gorges cut the foothill belt too (it could fill the slot back up otherwise).
        for (Gorge gorge : MountHuaPlan.GORGES) {
            h = gorge(gorge, u, v, h);
        }
        return clefts(u, v, saddles(u, v, valley(u, v, h)));
    }

    /** Narrow ravines: floor within the half width, then 3-per-block rock sides back up. */
    private double clefts(double u, double v, double h) {
        for (double[] c : MountHuaPlan.CLEFTS) {
            double lx = c[2] - c[0];
            double ly = c[3] - c[1];
            double t = clamp(((u - c[0]) * lx + (v - c[1]) * ly) / (lx * lx + ly * ly), 0, 1);
            double d = Math.hypot(u - (c[0] + lx * t), v - (c[1] + ly * t))
                    + 1.2 * relief.noise(u / 6.0, v / 6.0, 93.0);
            // Fades out at both ends so the ravine opens into the slopes instead of ending in a wall.
            double ends = smooth(0, 0.12, t) * smooth(1, 0.88, t);
            double floor = c[4] + 2 * relief.noise(u / 9.0, v / 9.0, 95.0);
            double cut = floor + 3.0 * Math.max(0, d - c[5]);
            if (cut < h) {
                h = h + (cut - h) * ends;
            }
        }
        return h;
    }

    /** 0..1: how much of a forested saddle this column is (the writer plants a wood there). */
    public double saddleWeight(double u, double v) {
        double best = 0;
        for (double[] sd : MountHuaPlan.SADDLES) {
            double d = Math.hypot(u - sd[0], v - sd[1]) * (1 + 0.18 * relief.noise(u / 11.0, v / 11.0, 83.0));
            best = Math.max(best, smooth(sd[2], sd[2] * 0.45, d));
        }
        return best;
    }

    /**
     * Saddles: inside each, the ground eases toward a gentle wooded bowl (0.3 rise per block
     * from its floor), so soil and trees hold there; spire walls rising above stay rock.
     */
    private double saddles(double u, double v, double h) {
        for (double[] sd : MountHuaPlan.SADDLES) {
            double d = Math.hypot(u - sd[0], v - sd[1]) * (1 + 0.18 * relief.noise(u / 11.0, v / 11.0, 83.0));
            if (d >= sd[2]) {
                continue;
            }
            double bowl = sd[3] + 0.25 * d + 1.5 * relief.noise(u / 13.0, v / 13.0, 87.0);
            double w = smooth(sd[2], sd[2] * 0.45, d) * smooth(sd[3] + 45, sd[3] + 25, h);
            h = h * (1 - w) + bowl * w;
        }
        return h;
    }

    /** Foothill belt height (nominal), 0 at the outer edge. */
    public double belt(double u, double v) {
        double e = beltDistance(u, v);
        if (e >= 1.0) {
            return 0;
        }
        double rise = Math.pow(1 - smooth(0.3, 1.0, e), 1.3);
        double du = u;
        double dv = v - 20;
        double r = Math.sqrt(du * du + dv * dv);
        double th = Math.atan2(dv, du);
        // Spurs: ridges radiating from the massif, valleys between them.
        double n = relief.noise(Math.cos(th) * 2.6 + 11.0, Math.sin(th) * 2.6, r / 260.0)
                + 0.3 * relief.noise(u / 90.0, v / 90.0, 14.0);
        double spur = Math.pow(1 - Math.min(1, Math.abs(n) / 0.55), 1.6);
        double rolling = 9 * hillsNoise(u, v);
        // Branching relief (codex r2: the belt read as a smooth green skirt): unequal spurs plus a
        // ridged drainage network with steeper valley sides.
        double drainage = this.hills.ridged(u / 170.0 + 9.0, v / 170.0, 3);
        double h = rise * (10 + 62 * spur + 26 * drainage) + rolling * (1 - smooth(0.8, 1.0, e));
        for (Ridge ridge : MountHuaPlan.BELT_RIDGES) {
            h = smax(h, ridge(ridge, u, v) * rise, 12);
        }
        for (Peak p : MountHuaPlan.SATELLITES) {
            h = smax(h, peak(p, u, v), 10);
        }
        h = smax(h, outcrops(u, v, rise), 6);
        return Math.max(0, h);
    }

    /** Small granite knobs poking out of the foothill forest (one in ~8 lattice cells). */
    private double outcrops(double u, double v, double rise) {
        int ci = (int) Math.floor(u / CELL);
        int cj = (int) Math.floor(v / CELL);
        double best = 0;
        for (int i = ci - 1; i <= ci + 1; i++) {
            for (int j = cj - 1; j <= cj + 1; j++) {
                long hsh = hash(i + 7919, j - 104729);
                if (rnd(hsh, 1) > 0.13) {
                    continue;
                }
                double cu = (i + 0.2 + 0.6 * rnd(hsh, 3)) * CELL;
                double cv = (j + 0.2 + 0.6 * rnd(hsh, 4)) * CELL;
                double e = beltDistance(cu, cv);
                if (e > 0.85 || massif(cu, cv) > 0.05) {
                    continue;
                }
                double top = (1 - smooth(0.3, 0.85, e)) * (28 + 30 * rnd(hsh, 2));
                double ra = 9 + 8 * rnd(hsh, 5);
                best = Math.max(best, bullet(u - cu, v - cv, top, ra, ra * (0.6 + 0.3 * rnd(hsh, 6)),
                        rnd(hsh, 7) * Math.PI, 2.0 + rnd(hsh, 8), hsh & 1023));
            }
        }
        return best;
    }

    private double hillsNoise(double u, double v) {
        return hills.fbm(u / 140.0 + 3.0, v / 140.0, 3);
    }

    /** The main valley from the gate outwards: flat floor, stream bed, gentle sides. */
    private double valley(double u, double v, double h) {
        double[] vu = MountHuaPlan.VALLEY_U;
        double[] vv = MountHuaPlan.VALLEY_V;
        double[] vf = MountHuaPlan.VALLEY_FLOOR;
        double best = h;
        for (int i = 0; i + 1 < vu.length; i++) {
            double lx = vu[i + 1] - vu[i];
            double ly = vv[i + 1] - vv[i];
            double t = clamp(((u - vu[i]) * lx + (v - vv[i]) * ly) / (lx * lx + ly * ly), 0, 1);
            double d = Math.hypot(u - (vu[i] + lx * t), v - (vv[i] + ly * t));
            double floor = vf[i] + (vf[i + 1] - vf[i]) * t;
            double width = 9 + 4 * hills.noise(u / 40.0, v / 40.0, 33.0);
            double cut = d <= 1.5 ? floor - 1 : floor + 0.45 * Math.max(0, d - width) + 0.02 * Math.max(0, d - width) * Math.max(0, d - width);
            best = Math.min(best, Math.max(cut, d <= 1.5 ? floor - 1 : floor));
        }
        return best;
    }

    /** Distance to the valley stream line (for the writer: water goes where it is ≤ 1.5). */
    public double streamDistance(double u, double v) {
        double[] vu = MountHuaPlan.VALLEY_U;
        double[] vv = MountHuaPlan.VALLEY_V;
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < vu.length; i++) {
            double lx = vu[i + 1] - vu[i];
            double ly = vv[i + 1] - vv[i];
            double t = clamp(((u - vu[i]) * lx + (v - vv[i]) * ly) / (lx * lx + ly * ly), 0, 1);
            best = Math.min(best, Math.hypot(u - (vu[i] + lx * t), v - (vv[i] + ly * t)));
        }
        return best;
    }

    /** Height of the rock before the trail and the terraces are cut into it. */
    public double natural(double u, double v) {
        double c = massif(u, v);
        if (c <= 0) {
            return 0;
        }
        double env = envelope(u, v) * c;
        // Near the scarp the massif dips into bays and rises in buttresses, so the north wall is
        // not one even rampart (stand 03.10).
        double nearScarp = 1 - smooth(MountHuaPlan.SCARP_V, MountHuaPlan.SCARP_V + 140, v);
        env *= 1 - 0.5 * nearScarp * (0.5 + 0.5 * hills.noise(u / 38.0, 5.5, 2.0));
        // Dissection: radial gorges (drainage runs outwards from the core) plus smaller clefts.
        // Across an interfluve the height follows |n|^0.35: vertical walls at the gorge, a rounded
        // granite dome on top — the Huashan profile (DESCRIPTIONS.md p.6, p.9).
        double calm = 1 - zoneCalm(u, v);
        double du = u;
        double dv = v - 20;
        double r = Math.sqrt(du * du + dv * dv);
        double th = Math.atan2(dv, du);
        double wx = 18 * warp.noise(u / 90.0, v / 90.0, 31.0);
        double wy = 18 * warp.noise(u / 90.0, v / 90.0, 37.0);
        double n1 = 0.75 * relief.noise(Math.cos(th) * 3.2, Math.sin(th) * 3.2, (r + wx) / 120.0)
                + 0.25 * relief.noise((u + wx) / 70.0, (v + wy) / 70.0, 9.0);
        double dome1 = Math.pow(clamp(Math.abs(n1) / 0.30, 0, 1), 0.35);
        double n2 = flute.noise((u + wy) / 38.0, (v + wx) / 38.0, 12.5);
        double dome2 = Math.pow(clamp(Math.abs(n2) / 0.28, 0, 1), 0.45);
        double depth1 = 0.72 * smooth(30, 120, r) * calm;
        double depth2 = 0.12 * calm;
        double h = env * (1 - depth1 * (1 - dome1)) * (1 - depth2 * (1 - dome2));
        h = Math.max(h, c * 12);
        h = smax(h, fangs(u, v), 8);
        // The pillar basin is sunk deep so the towers stand free in the cloud sea (codex r4).
        double basin = 1 - smooth(PILLARS_R * 0.55, PILLARS_R * 1.05, Math.hypot(u - PILLARS_U, v - PILLARS_V));
        h = h * (1 - basin) + Math.min(h, 35 + 10 * hills.noise(u / 40.0, v / 40.0, 8.0)) * basin;
        h = Math.max(h, pillars(u, v));
        for (Ridge ridge : MountHuaPlan.RIDGES) {
            h = smax(h, ridge(ridge, u, v), 6);
        }
        for (Peak peak : MountHuaPlan.PEAKS) {
            h = smax(h, peak(peak, u, v), 8);
        }
        // The scarp cuts everything: a straight wall from the plain up to the North Peak.
        h *= scarp(u, v, 4);
        // Talus apron at the foot of the scarp: forested rubble hills, not a wall straight out of a lawn.
        double apron = (6 + 34 * Math.pow(0.5 + 0.5 * hills.fbm(u / 60.0, v / 45.0, 3), 1.6))
                * smooth(MountHuaPlan.SCARP_V - 70, MountHuaPlan.SCARP_V - 4, v) * (1 - smooth(260, 340, Math.abs(u)));
        h = Math.max(h, apron);
        for (Gorge gorge : MountHuaPlan.GORGES) {
            h = gorge(gorge, u, v, h);
        }
        // Micro relief: broken granite.
        h += 0.8 * (relief.ridged(u / 23.0, v / 23.0, 2) - 0.5) * c;
        return h;
    }

    /**
     * Large-scale envelope of the massif: highest along the spine (North Peak, Canglong, the
     * horseshoe, south rows), falling ~0.45 block per block sideways.
     */
    private double envelope(double u, double v) {
        double best = 0;
        for (int i = 0; i + 1 < SPINE_U.length; i++) {
            double ax = SPINE_U[i];
            double ay = SPINE_V[i];
            double lx = SPINE_U[i + 1] - ax;
            double ly = SPINE_V[i + 1] - ay;
            double t = clamp(((u - ax) * lx + (v - ay) * ly) / (lx * lx + ly * ly), 0, 1);
            double d = Math.hypot(u - (ax + lx * t), v - (ay + ly * t));
            double hh = SPINE_H[i] + (SPINE_H[i + 1] - SPINE_H[i]) * t;
            best = Math.max(best, hh - 0.45 * d);
        }
        return Math.max(40, best);
    }

    private static final double[] SPINE_U = {-10, 10, 20, 0, 0, 0};
    private static final double[] SPINE_V = {-360, -200, -75, 40, 220, 470};
    private static final double[] SPINE_H = {128, 140, 158, 160, 140, 70};

    /** 1 on and around the terraces: no gorges are cut there (the sect needs ground). */
    private double zoneCalm(double u, double v) {
        double calm = 0;
        for (Zone z : MountHuaPlan.ZONES) {
            if (z.cave() || z.y() < 20) {
                continue;
            }
            double dx = Math.max(0, Math.abs(u - z.u()) - z.width() / 2.0);
            double dz = Math.max(0, Math.abs(v - z.v()) - z.depth() / 2.0);
            calm = Math.max(calm, 1 - smooth(4, 30, Math.hypot(dx, dz)));
        }
        return calm;
    }

    // ---------------------------------------------------------------------------------------------
    // Trail profile: terrain-following, slope-limited (computed once from the natural rock).

    private static final double TRAIL_STEP = 1.0;
    /** Max rise per block along the trail (stairs of 45°). */
    private static final double TRAIL_SLOPE = 0.9;
    private double[] trailU;
    private double[] trailV;
    private double[] trailY;

    private void buildTrail() {
        List<TrailPoint> pts = MountHuaPlan.TRAIL;
        java.util.ArrayList<double[]> samples = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> vertices = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < pts.size(); i++) {
            vertices.add(samples.size());
            TrailPoint a = pts.get(i);
            TrailPoint b = pts.get(i + 1);
            double len = Math.hypot(b.u() - a.u(), b.v() - a.v());
            int n = Math.max(1, (int) Math.ceil(len / TRAIL_STEP));
            for (int k = 0; k < n; k++) {
                double t = k / (double) n;
                samples.add(new double[] {a.u() + (b.u() - a.u()) * t, a.v() + (b.v() - a.v()) * t});
            }
        }
        TrailPoint last = pts.get(pts.size() - 1);
        samples.add(new double[] {last.u(), last.v()});
        int n = samples.size();
        trailU = new double[n];
        trailV = new double[n];
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            trailU[i] = samples.get(i)[0];
            trailV[i] = samples.get(i)[1];
            raw[i] = ground(trailU[i], trailV[i]);
        }
        // Smooth (window 7) so single towers and notches do not make the stair jump.
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            int cnt = 0;
            for (int k = Math.max(0, i - 3); k <= Math.min(n - 1, i + 3); k++) {
                sum += raw[k];
                cnt++;
            }
            y[i] = sum / cnt;
        }
        // Cut to the stair slope (inf-convolution: a cut-only Lipschitz profile), then clamp between
        // the cones of the two pinned ends (gate and main terrace). Both steps keep the slope limit.
        double s = TRAIL_SLOPE * TRAIL_STEP;
        for (int i = 1; i < n; i++) {
            y[i] = Math.min(y[i], y[i - 1] + s);
        }
        for (int i = n - 2; i >= 0; i--) {
            y[i] = Math.min(y[i], y[i + 1] + s);
        }
        double first = pts.get(0).y();
        double end = last.y();
        for (int i = 0; i < n; i++) {
            double hi = Math.min(first + s * i, end + s * (n - 1 - i));
            double lo = Math.max(first - s * i, end - s * (n - 1 - i));
            y[i] = Math.max(lo, Math.min(hi, y[i]));
        }
        // Landings (author 04.10: «лестница ... немного неровная»): a flat rest at every turn and
        // every 24 steps, then the flights between them are re-fitted to the slope limit, so a
        // turn never cuts a step sideways and the rhythm stays steady.
        boolean[] flat = new boolean[n];
        java.util.TreeSet<Integer> centres = new java.util.TreeSet<>(vertices);
        for (int c = 24; c < n - 4; c += 24) {
            centres.add(c);
        }
        for (int c : centres) {
            int r = vertices.contains(c) ? 4 : 3;
            if (c < r + 1 || c > n - r - 2) {
                continue;
            }
            double level = y[c];
            for (int j = c - r; j <= c + r; j++) {
                y[j] = level;
                // Turns are hard landings (a stair never turns mid-flight); rests are soft.
                // Soft: kept only where the slope budget allows (steep flights win).
            }
        }
        // Where the stair crosses a site (the sect gate), it runs at the site's level.
        for (int i = 0; i < n; i++) {
            for (Zone z : MountHuaPlan.ZONES) {
                if (!z.cave() && Math.abs(trailU[i] - z.u()) <= z.width() / 2.0 + 1
                        && Math.abs(trailV[i] - z.v()) <= z.depth() / 2.0 + 1) {
                    y[i] = z.y();
                    flat[i] = true;
                }
            }
        }
        for (int i = 1; i < n; i++) {
            if (!flat[i]) {
                y[i] = Math.max(y[i - 1] - s, Math.min(y[i - 1] + s, y[i]));
            }
        }
        for (int i = n - 2; i >= 0; i--) {
            if (!flat[i]) {
                y[i] = Math.max(y[i + 1] - s, Math.min(y[i + 1] + s, y[i]));
            }
        }
        for (int round = 0; round < 8; round++) {
            y[0] = first;
            y[n - 1] = end;
            for (int i = 1; i < n - 1; i++) {
                if (!flat[i]) {
                    y[i] = Math.max(y[i - 1] - s, Math.min(y[i - 1] + s, y[i]));
                }
            }
            for (int i = n - 2; i > 0; i--) {
                if (!flat[i]) {
                    y[i] = Math.max(y[i + 1] - s, Math.min(y[i + 1] + s, y[i]));
                }
            }
        }
        trailY = y;
    }

    /** Nearest trail sample: {distance, nominal y, index}; distance = MAX_VALUE if far. */
    public double[] trailAt(double u, double v) {
        double bestD = Double.MAX_VALUE;
        int best = -1;
        for (int i = 0; i < trailU.length; i++) {
            double du = u - trailU[i];
            if (du > 12 || du < -12) {
                continue;
            }
            double dv = v - trailV[i];
            if (dv > 12 || dv < -12) {
                continue;
            }
            double d = du * du + dv * dv;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best < 0) {
            return null;
        }
        return new double[] {Math.sqrt(bestD), trailY[best], best};
    }

    /** Number of trail samples (one per block of path). */
    public int trailLength() {
        return trailU.length;
    }

    /** Local (u, v) of a trail sample. */
    public double[] trailPoint(int index) {
        int i = Math.max(0, Math.min(trailU.length - 1, index));
        return new double[] {trailU[i], trailV[i]};
    }

    /** Nominal trail height at a sample index (clamped to the ends). */
    public double trailY(int index) {
        return trailY[Math.max(0, Math.min(trailY.length - 1, index))];
    }

    /** Direction of ascent at a trail sample, local (du, dv) unit vector of the path. */
    public double[] trailDir(int index) {
        int a = Math.max(0, index - 1);
        int b = Math.min(trailU.length - 1, index + 1);
        double du = trailU[b] - trailU[a];
        double dv = trailV[b] - trailV[a];
        double l = Math.hypot(du, dv);
        return new double[] {du / l, dv / l, trailY[b] - trailY[a]};
    }

    // ---------------------------------------------------------------------------------------------
    // Pieces

    /** Rows of lesser granite fangs: one jittered bullet per lattice cell, taller towards the core. */
    private double fangs(double u, double v) {
        int ci = (int) Math.floor(u / CELL);
        int cj = (int) Math.floor(v / CELL);
        double best = 0;
        for (int i = ci - 2; i <= ci + 2; i++) {
            for (int j = cj - 2; j <= cj + 2; j++) {
                long hsh = hash(i, j);
                double r1 = rnd(hsh, 1);
                double r2 = rnd(hsh, 2);
                double cu = (i + 0.2 + 0.6 * rnd(hsh, 3)) * CELL;
                double cv = (j + 0.2 + 0.6 * rnd(hsh, 4)) * CELL;
                double mc = massif(cu, cv);
                if (mc < 0.12 || rnd(hsh, 8) < 0.72 || Math.hypot(cu, cv - 40) < 260) {
                    continue;
                }
                double top = mc * envelope(cu, cv) * (0.92 + 0.22 * r2);
                double ra = 20 + 22 * r1;
                double rb = ra * (0.6 + 0.35 * rnd(hsh, 5));
                double ang = rnd(hsh, 6) * Math.PI;
                double power = 2.2 + 1.6 * rnd(hsh, 7);
                best = Math.max(best, bullet(u - cu, v - cv, top, ra, rb, ang, power, hsh & 1023));
                // Most towers lean on a lower, thicker shoulder: uneven mass, not a lone finger.
                if (rnd(hsh, 9) < 0.7) {
                    double sa = rnd(hsh, 10) * Math.PI * 2;
                    double off = ra * (0.5 + 0.4 * rnd(hsh, 11));
                    best = Math.max(best, bullet(u - cu - Math.cos(sa) * off, v - cv - Math.sin(sa) * off,
                            top * (0.55 + 0.25 * rnd(hsh, 12)), ra * 1.25, rb * 1.3, ang + 0.7, 1.8 + rnd(hsh, 13),
                            (hsh >>> 10) & 1023));
                }
            }
        }
        return best;
    }

    /** Centre and radius of the pillar forest (author ref 03: pillars rising out of a sea of clouds). */
    private static final double PILLARS_U = MountHuaPlan.PILLARS_U;
    private static final double PILLARS_V = MountHuaPlan.PILLARS_V;
    private static final double PILLARS_R = MountHuaPlan.PILLARS_R;
    private static final double PILLAR_CELL = 34;

    /**
     * Slender sheer pillars with rounded caps (vegetation "wigs" are placed by the writer), standing
     * in a low basin south-east of the South Peak so the cloud sea (nominal 100-120) wraps their
     * middles: the manhwa's signature view of Mount Hua (author refs 03, 09).
     */
    private double pillars(double u, double v) {
        double dr = Math.hypot(u - PILLARS_U, v - PILLARS_V);
        if (dr > PILLARS_R + 30) {
            return 0;
        }
        int ci = (int) Math.floor(u / PILLAR_CELL);
        int cj = (int) Math.floor(v / PILLAR_CELL);
        double best = 0;
        for (int i = ci - 1; i <= ci + 1; i++) {
            for (int j = cj - 1; j <= cj + 1; j++) {
                long hsh = hash(i + 50021, j + 70001);
                if (rnd(hsh, 1) < 0.55) {
                    continue;
                }
                double cu = (i + 0.25 + 0.5 * rnd(hsh, 3)) * PILLAR_CELL;
                double cv = (j + 0.25 + 0.5 * rnd(hsh, 4)) * PILLAR_CELL;
                double fade = 1 - smooth(PILLARS_R * 0.6, PILLARS_R, Math.hypot(cu - PILLARS_U, cv - PILLARS_V));
                if (fade <= 0 || massif(cu, cv) < 0.3) {
                    continue;
                }
                // Thick towers 20-45 wide and very different heights, not a field of equal spikes
                // (author/DESCRIPTIONS.md, Сводка: «избегая поля одинаковых тонких шпилей»).
                double top = (100 + 95 * rnd(hsh, 2)) * (0.75 + 0.25 * fade);
                double ra = 10 + 11 * rnd(hsh, 5);
                // Pillars keep their mass: only a broken, tilted crown (no needles; codex 04.10).
                best = Math.max(best, bullet(u - cu, v - cv, top, ra, ra * (0.7 + 0.25 * rnd(hsh, 6)),
                        rnd(hsh, 7) * Math.PI, 6 + 4 * rnd(hsh, 8), hsh & 1023, 0.22));
            }
        }
        return best;
    }

    private double peak(Peak p, double u, double v) {
        double du = u - p.u();
        double dv = v - p.v();
        if (Math.abs(du) > p.ra() * 1.6 + 10 || Math.abs(dv) > p.ra() * 1.6 + 10) {
            return 0;
        }
        double h = bullet(du, dv, p.top(), p.ra(), p.rb(), Math.toRadians(p.angle()), p.power(), p.u() * 0.37);
        if (p.name().equals("south") && dv < 0 && h > 0) {
            // The main peak rises right behind the sect shelf (author refs «sect high, peak
            // towering»): its north flank is steepened so the wall starts close behind the pads.
            double q = Math.min(1, Math.hypot(du / p.ra(), dv / p.rb()));
            double lift = smooth(0, 0.6, -dv / p.rb()) * smooth(1.05, 0.55, q);
            h = Math.min(p.top(), h + (p.top() - h) * 0.45 * lift);
        }
        return h;
    }

    /**
     * Granite dome-wedge: h = top·(1 − q^power) with q the elliptic distance. Rounded bald top,
     * 70–90° sides near the rim. The rim radius wanders with direction (irregular outline) and is
     * notched by narrow vertical grooves (columnar jointing of Huashan walls).
     */
    private double bullet(double du, double dv, double top, double ra, double rb, double ang, double power,
            double salt) {
        return bullet(du, dv, top, ra, rb, ang, power, salt, 0.55);
    }

    /** {@code sharp}: share of the pointed profile (0 = old dome, 0.55 = the main peaks). */
    private double bullet(double du, double dv, double top, double ra, double rb, double ang, double power,
            double salt, double sharp) {
        double ca = Math.cos(ang);
        double sa = Math.sin(ang);
        double x = du * ca + dv * sa;
        double y = -du * sa + dv * ca;
        double q = Math.sqrt((x / ra) * (x / ra) + (y / rb) * (y / rb));
        if (q >= 1.35) {
            return 0;
        }
        double a = Math.atan2(dv, du);
        double cx = Math.cos(a);
        double sy = Math.sin(a);
        double lobes = 0.16 * warp.noise(cx * 1.4 + salt, sy * 1.4, 21.7) + 0.07 * warp.noise(cx * 3.3, sy * 3.3, salt);
        // Sparse grooves: only a few deep ones per face (codex r1: fluting everywhere read as
        // bundles of thin columns; the photos show broad slabs cut by occasional grooves).
        double arc = ra / 7.0;
        double groove = 1 - smooth(0.0, 0.08, Math.abs(flute.noise(cx * arc + salt, sy * arc, 4.2 + q * 0.4)));
        // Vertical joints (author 04.10: «острее»): deeper, denser notches in the walls.
        double joint = 1 - smooth(0.0, 0.06, Math.abs(flute.noise(cx * arc * 2.3 + salt, sy * arc * 2.3, 7.7)));
        q *= legacyRock ? 1 + lobes + 0.025 * groove * smooth(0.55, 0.9, q)
                : 1 + lobes + 0.05 * groove * smooth(0.45, 0.9, q) + 0.03 * joint * smooth(0.3, 0.95, q);
        if (q >= 1) {
            return 0;
        }
        // Asymmetry: one sheer face (towards salt-direction) and a gentler back — Huashan's peaks
        // are big slabs on one side and wooded shoulders on the other (ref 04).
        double face = Math.cos(a - salt * 0.61);
        double pw = power * (1 + 0.45 * face);
        double dome = 1 - Math.pow(q, pw);
        // Angular granite instead of domes (author 04.10: «слишком круглые»): a pointed profile
        // with sheer feet mixed in, a knife-edge crest along the long axis, and the crest broken
        // into teeth and notches. The summit itself keeps its height.
        // Crest stretched along the long axis (short crooked knife-edge, not a lone tip); one
        // near-vertical face and a gentler, stepped back (codex: no symmetric witch hats).
        double qc = Math.min(1, Math.sqrt(0.35 * (x / ra) * (x / ra) + (y / rb) * (y / rb))
                * (1 + 0.12 * warp.noise((x / ra) * 2.0 + salt, (y / rb) * 2.0, 44.0)));
        // Truncated, faceted profile (author 04.10: «слишком заострённые»): a broad irregular top
        // (vegetated), then angular sides; no needle tips.
        double spike = Math.pow(Math.min(1, Math.max(0, 1 - Math.max(qc, q * 0.8)) * (1.9 + 0.4 * face)), 0.75 - 0.2 * face);
        if (face < -0.2) {
            double band = 0.09;
            double f = spike / band + salt * 0.01;
            double frac = f - Math.floor(f);
            spike -= band * 0.45 * smooth(0.55, 1.0, frac) * smooth(-0.2, -0.6, face);
        }
        double blade = Math.abs(y / rb) * smooth(0.95, 0.15, q);
        double crest = Math.abs(flute.noise((x / ra) * 2.6 + salt, salt * 0.13, 51.0));
        double teeth = (1 - smooth(0.0, 0.3, crest)) * smooth(0.85, 0.2, q) * smooth(0.05, 0.25, Math.abs(x / ra));
        double h = legacyRock ? top * dome
                : top * Math.max(0, (1 - sharp) * dome + sharp * spike - 0.10 * sharp * blade - 0.07 * sharp * teeth);
        // Ledges on about half of the rocks: a steep step, then a narrow bench (pines sit there).
        if (((long) salt & 1) == 0) {
            double band = 9 + (Math.abs((long) salt) % 9);
            double f = (h + salt * 0.37) / band;
            double frac = f - Math.floor(f);
            h -= band * 0.28 * smooth(0.6, 1.0, frac) * smooth(0.35, 0.65, q);
        }
        // Clefts: rare narrow slots that split a tower (photos 05, 10).
        double slot = Math.abs(flute.noise((x / ra) * 1.2 + salt, (y / rb) * 0.3, 33.3 + salt * 0.01));
        if (slot < 0.04) {
            h *= 0.75 + 6.0 * slot;
        }
        return h;
    }

    private long hash(int i, int j) {
        long h = seed * 0x9E3779B97F4A7C15L + i * 0xC2B2AE3D27D4EB4FL + j * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return h;
    }

    private static double rnd(long h, int k) {
        long x = h + k * 0x9E3779B97F4A7C15L;
        x ^= x >>> 31;
        x *= 0x94D049BB133111EBL;
        x ^= x >>> 29;
        return (x >>> 11) * 0x1.0p-53;
    }

    private double ridge(Ridge ridge, double u, double v) {
        double best = 0;
        double[] us = ridge.u();
        double[] vs = ridge.v();
        double[] hs = ridge.h();
        for (int i = 0; i + 1 < us.length; i++) {
            double ax = us[i];
            double ay = vs[i];
            double bx = us[i + 1];
            double by = vs[i + 1];
            double lx = bx - ax;
            double ly = by - ay;
            double len2 = lx * lx + ly * ly;
            double t = clamp(((u - ax) * lx + (v - ay) * ly) / len2, 0, 1);
            double px = ax + lx * t;
            double py = ay + ly * t;
            double d = Math.hypot(u - px, v - py);
            double crest = hs[i] + (hs[i + 1] - hs[i]) * t;
            if (d > crest / Math.max(0.6, ridge.steep() * 0.5) + 30) {
                continue;
            }
            // Crest bumps (towers and notches along the crest).
            crest += 8 * relief.noise(px / 34.0, py / 34.0, 9.9) + 2 * relief.noise(px / 9.0, py / 9.0, 4.1);
            double s = ridge.steep() * (1 + 0.35 * flute.noise(u / 13.0, v / 13.0, 4.4));
            double h = crest - s * Math.pow(d, 1.12);
            best = Math.max(best, h);
        }
        return best;
    }

    private double gorge(Gorge g, double u, double v, double h) {
        double[] us = g.u();
        double[] vs = g.v();
        double[] fl = g.floor();
        // First the rock the gorge is cut through: tall walls on both sides (a slot, not a dip).
        double best = Double.MAX_VALUE;
        double bestFloor = 0;
        for (int i = 0; i + 1 < us.length; i++) {
            double lx = us[i + 1] - us[i];
            double ly = vs[i + 1] - vs[i];
            double t = clamp(((u - us[i]) * lx + (v - vs[i]) * ly) / (lx * lx + ly * ly), 0, 1);
            double d = Math.hypot(u - (us[i] + lx * t), v - (vs[i] + ly * t));
            if (d < best) {
                best = d;
                bestFloor = fl[i] + (fl[i + 1] - fl[i]) * t;
            }
        }
        double wall = bestFloor + 45 + 20 * relief.noise(u / 23.0, v / 23.0, 12.0);
        double behindGate = smooth(vs[0] - 14, vs[0] + 2, v);
        h = Math.max(h, h + (wall - h) * (1 - smooth(10, 32, best)) * behindGate * (h < wall ? 1 : 0));
        double result = h;
        for (int i = 0; i + 1 < us.length; i++) {
            double ax = us[i];
            double ay = vs[i];
            double lx = us[i + 1] - ax;
            double ly = vs[i + 1] - ay;
            double len2 = lx * lx + ly * ly;
            double t = clamp(((u - ax) * lx + (v - ay) * ly) / len2, 0, 1);
            double d = Math.hypot(u - (ax + lx * t), v - (ay + ly * t));
            double floor = fl[i] + (fl[i + 1] - fl[i]) * t;
            double wobble = 2.5 * relief.noise(u / 15.0, v / 15.0, 6.6);
            double cut = floor + g.wallSlope() * Math.max(0, d - g.halfWidth() + wobble);
            result = Math.min(result, Math.max(cut, floor));
        }
        return result;
    }

    /** Trail bed: cut to the trail level within 1.5 blocks; a small fill under it eases to 3. */
    private double trail(double u, double v, double h) {
        double[] t = trailAt(u, v);
        if (t == null || t[0] > 3.6) {
            return h;
        }
        double y = t[1];
        if (t[0] <= 2.1) {
            return y;
        }
        if (h > y) {
            return h;
        }
        double k = 1 - smooth(2.1, 3.6, t[0]);
        return h + (y - 1 - h) * k;
    }

    private double terrace(Zone z, double u, double v, double h) {
        if (z.cave()) {
            return h;
        }
        if (z.id().equals("grove")) {
            // The plum grove is a wooded outcrop (plan), not a flat pad: the ground eases toward
            // its level but keeps a few blocks of natural roll and an irregular outline.
            double d = Math.max(0, Math.hypot(Math.max(0, Math.abs(u - z.u()) - z.width() / 2.0),
                    Math.max(0, Math.abs(v - z.v()) - z.depth() / 2.0)) - 4 * relief.noise(u / 8.0, v / 8.0, 67.0));
            double soft = z.y() + clamp(h - z.y(), -3, 3);
            double k = smooth(0, 8, d);
            return soft * (1 - k) + h * k;
        }
        double dx = Math.max(0, Math.abs(u - z.u()) - z.width() / 2.0);
        double dz = Math.max(0, Math.abs(v - z.v()) - z.depth() / 2.0);
        // The pad edge wanders 1-4 blocks past the build rectangle: no ruler-straight border.
        double d = Math.max(0, Math.hypot(dx, dz) - (3.5 + 2.5 * relief.noise(u / 6.0, v / 6.0, 61.0 + z.y())
                - 2.5 * relief.noise(u / 17.0, v / 17.0, 63.0 + z.y())));
        if (d == 0) {
            return z.y();
        }
        if (d > 12) {
            return h;
        }
        double y = z.y();
        // Lower ground: a short retaining bank (3 per block) that meets the natural slope;
        // higher ground: a cut bank (1.5 per block). Beyond that the ridge stays as it was.
        double graded = h < y ? Math.max(h, y - 3.0 * d) : Math.min(h, y + 2.5 * d);
        double k = smooth(6, 12, d);
        return graded * (1 - k) + h * k;
    }

    /** Smooth maximum (polynomial), k = blend width in blocks. */
    static double smax(double a, double b, double k) {
        double hh = Math.max(k - Math.abs(a - b), 0) / k;
        return Math.max(a, b) + hh * hh * k * 0.25;
    }

    /** Stairs between the pads: 4 wide at their stepped level; gaps are left for a bridge. */
    private double paths(double u, double v, double h) {
        for (double[] p : MountHuaPlan.PATHS) {
            double[] q = pathAt(p, u, v);
            if (q[0] <= 2.1 && h >= q[1] - 3) {
                return q[1];
            }
        }
        return h;
    }

    /** {distance to the path line, nominal level there (stepped), position 0..1} for one path. */
    public static double[] pathAt(double[] p, double u, double v) {
        double lx = p[3] - p[0];
        double ly = p[4] - p[1];
        double t = clamp(((u - p[0]) * lx + (v - p[1]) * ly) / (lx * lx + ly * ly), 0, 1);
        double d = Math.hypot(u - (p[0] + lx * t), v - (p[1] + ly * t));
        return new double[] {d, Math.round(p[2] + (p[5] - p[2]) * t), t};
    }

    /**
     * 0..1: the slopes and cliffs below the sect shelf (author 04.10: more greenery under the
     * sect — pines, shrubs, plums clinging, green hollows). {@code h} is the nominal height.
     */
    public static double belowSect(double u, double v, double h) {
        double e = Math.hypot(u / 140.0, (v - 20) / 120.0);
        return smooth(1.0, 0.7, e) * smooth(156, 146, h) * smooth(60, 80, h);
    }

    static double smooth(double e0, double e1, double x) {
        double t = clamp((x - e0) / (e1 - e0), 0, 1);
        return t * t * (3 - 2 * t);
    }

    static double clamp(double x, double lo, double hi) {
        return x < lo ? lo : (x > hi ? hi : x);
    }
}
