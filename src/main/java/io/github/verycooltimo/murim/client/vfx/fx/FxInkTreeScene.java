package io.github.verycooltimo.murim.client.vfx.fx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The ink tree in time (client-free, so the lab runs the same code). Cultivation energy flows out of the root the whole time:
 * every trunk grows a tree, and the ink of it is a wave that runs outward along every branch at one speed. A point is lit from the
 * moment the wave reaches it for {@code life} seconds, then the tail of the wave slides off along the same path. So a branch is drawn
 * from its base to its tip, slides off along itself from its base to its tip, and the next growth of the trunk (a new variant: the
 * same heading, other forks) starts before the old one has gone. Nothing pops, nothing is rubbed out in place. A slow wave of
 * bending runs outward along the branches with the same phase, so the whole tree is turbulent but never sways as one piece.
 */
public final class FxInkTreeScene {
    /** The bench element is this long; its first second is empty (the body is still sitting down). */
    public static final double BENCH_SECONDS = 13;
    public static final double START = 1.0;

    /** Looks by name, so the bench and the lab both read the same table. */
    public static final String INK = "INK_TREE", MASS = "INK_TREE_MASS", HALO = "INK_TREE_HALO";

    /** One stroke to draw: points (x, y, z) in the tree frame, the mesh half-width, and the stroke state. */
    public record Piece(String look, double[][] points, double half, double seed, double from, double drawn, double alpha) {}

    private static final double SPEED = 2.5, LIFE = 4.6, LAG = .12, FLOOR_SPEED = 1.1;
    private static final Map<Long, List<FxInkTree.Branch>> CACHE = new HashMap<>();

    private FxInkTreeScene() {}

    private static List<FxInkTree.Branch> group(int trunk, int variant) {
        return CACHE.computeIfAbsent(trunk * 100L + variant, k -> FxInkTree.group(trunk, variant));
    }

    /** Every stroke of the tree at {@code t} bench seconds. {@code halo} adds the violet light under the thick branches. */
    public static List<Piece> pieces(double t, boolean halo) {
        List<Piece> out = new ArrayList<>();
        for (int trunk = 0; trunk < FxInkTree.TRUNKS.length; trunk++) {
            double delay = START + .35 + 1.1 * FxInkTree.hash(trunk * 7L + 1), period = 3.3 + .9 * FxInkTree.hash(trunk * 7L + 2);
            double local = t - delay;
            if (local < 0) continue;
            int newest = (int) Math.floor(local / period);
            for (int gen = Math.max(0, newest - 2); gen <= newest; gen++) {
                double a = local - gen * period;
                if (a <= 0) continue;
                emit(out, group(trunk, gen % 3), trunk, a, t, SPEED, LIFE, halo);
            }
        }
        // the floor mass is grown once and stays; only its clots run and the turbulence moves it
        for (int i = 0; i < FxInkTree.FLOOR.size(); i++) {
            var f = FxInkTree.FLOOR.get(i);
            double delay = START + .25 + .9 * Math.abs(f.x[0]) + .2 * FxInkTree.hash(i * 7L + 1), a = t - delay;
            if (a <= 0) continue;
            emit(out, List.of(f), 100 + i, a, t, FLOOR_SPEED, 1e9, halo);
        }
        return out;
    }

    /** The whole tree at rest: every trunk of the first growth fully drawn, plus the floor (lab pictures of the geometry; no sliding, no second growth). */
    public static List<Piece> rest(double t, boolean halo) {
        List<Piece> out = new ArrayList<>();
        for (int trunk = 0; trunk < FxInkTree.TRUNKS.length; trunk++) emit(out, group(trunk, 0), trunk, 1e6, t, SPEED, 1e12, halo);
        for (int i = 0; i < FxInkTree.FLOOR.size(); i++) emit(out, List.of(FxInkTree.FLOOR.get(i)), 100 + i, 1e6, t, FLOOR_SPEED, 1e12, halo);
        return out;
    }

    private static void emit(List<Piece> out, List<FxInkTree.Branch> branches, int trunk, double age, double t, double speed, double life, boolean halo) {
        // the root stroke of a growth lives its own time; every other stroke starts to slide off exactly when the tail of its parent
        // reaches the joint, so the erasing front runs down the same paths the ink grew along (into the children too) and a child is
        // never left floating with a blunt base
        double[] slideAt = new double[branches.stream().mapToInt(b -> b.id).max().orElse(0) + 1];
        for (FxInkTree.Branch b : branches) {
            double h = FxInkTree.hash(trunk * 977L + b.id * 31L + (long) (b.start * 10) + 5);
            double own = b.start / speed + b.depth * LAG + life * (.7 + .8 * h);
            if (b.parent >= 0) {
                var p = branches.stream().filter(q -> q.id == b.parent).findFirst().orElseThrow();
                double pSpeed = speed * (.8 + .5 * FxInkTree.hash(trunk * 131L + p.id * 7L));
                own = slideAt[p.id] + b.parentU * p.length / pSpeed;
            }
            slideAt[b.id] = own;
        }
        for (FxInkTree.Branch b : branches) {
            double local = age - b.start / speed - b.depth * LAG;
            double mySpeed = speed * (.8 + .5 * FxInkTree.hash(trunk * 131L + b.id * 7L));
            double drawn = Math.clamp(local * speed / b.length, 0, 1), from = Math.clamp((age - slideAt[b.id]) * mySpeed / b.length, 0, 1);
            if (drawn <= 0 || from >= drawn) continue;
            double[][] pts = new double[b.size()][];
            for (int i = 0; i < pts.length; i++) pts[i] = bend(b.x[i], b.y[i], b.z[i], b.start + b.along[i], trunk, t);
            double seed = FxInkTree.hash(trunk * 977L + b.id * 31L + (long) (b.start * 10));
            double half = b.vis / FxInkTree.MAIN_SHARE;
            out.add(new Piece(b.kind == 1 ? MASS : INK, pts, half, seed, from, drawn, 1));
            // the light sits behind the thick branches of the trunks only, soft, and fades with the distance from the root
            if (halo && b.kind == 0 && b.depth <= 1 && b.vis > .03) {
                out.add(new Piece(HALO, pts, half * 4.2, seed, from, drawn, Math.exp(-b.start / 3.4) * (b.depth == 0 ? .6 : .4)));
            }
        }
    }

    /**
     * Slow turbulence: a wave of bending that runs outward with the ink. The phase depends on the distance from the root along the
     * tree and on the place, never on the branch, so a child leaves its parent exactly where the parent is. The root stays put.
     */
    static double[] bend(double x, double y, double z, double distance, int trunk, double t) {
        double amp = .15 * FxInkTree.smoothstep(.2, 3.6, distance);
        double w = distance * .85 - t * 1.6 + trunk * 2.3 + x * .6;
        double dx = amp * (Math.sin(w) + .45 * Math.sin(w * 2.3 + 1.7));
        double dy = amp * .55 * Math.cos(w * 1.3 + .6);
        return new double[]{x + dx, y + dy, z};
    }
}
