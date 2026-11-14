package io.github.verycooltimo.murim.client.vfx.fx;

import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Geometry of a stroke ribbon, free of the client so it is unit-tested. */
public final class FxStrokeMath {
    /** Length of the bench element «stroke» (FxStrokeBench), here so the bench's pure selection can read it. */
    public static final double BENCH_SECONDS = 13.5;

    private FxStrokeMath() {}

    /** Distance along the path at each point. */
    public static double[] lengths(List<Vec3> path) {
        double[] out = new double[path.size()];
        for (int i = 1; i < out.length; i++) out[i] = out[i - 1] + path.get(i).distanceTo(path.get(i - 1));
        return out;
    }

    /**
     * The ribbon's width direction at a point: across the path and across the line of sight, so the stroke shows its full width from
     * any side. Where the path runs straight at the camera that direction is undefined; the previous one is kept, so the ribbon
     * does not flip or collapse there.
     */
    public static Vec3 side(List<Vec3> path, int i, Vec3 camera, Vec3 previous) {
        Vec3 a = path.get(Math.max(0, i - 1)), b = path.get(Math.min(path.size() - 1, i + 1));
        Vec3 tangent = b.subtract(a), view = camera.subtract(path.get(i));
        Vec3 side = tangent.cross(view);
        if (side.lengthSqr() < 1e-10 * tangent.lengthSqr() * view.lengthSqr() || side.lengthSqr() < 1e-18) {
            return previous != null ? previous : new Vec3(0, 1, 0);
        }
        side = side.normalize();
        return previous != null && side.dot(previous) < 0 ? side.scale(-1) : side;
    }

    /**
     * An angular line: straight runs with a slight bow, joined by sharp corners (reference A6: the ink is «угловатое», not a smooth
     * curve). {@code corners} are the points of the line; each run is sampled {@code perRun} times and bowed sideways by
     * {@code bow} of its length, alternating sides, in the plane across {@code normal}.
     */
    public static List<Vec3> angular(List<Vec3> corners, int perRun, double bow, Vec3 normal) {
        List<Vec3> out = new ArrayList<>();
        for (int i = 0; i < corners.size() - 1; i++) {
            Vec3 a = corners.get(i), b = corners.get(i + 1), run = b.subtract(a);
            Vec3 across = run.cross(normal);
            across = across.lengthSqr() < 1e-12 ? Vec3.ZERO : across.normalize().scale(run.length() * bow * (i % 2 == 0 ? 1 : -1));
            for (int k = i == 0 ? 0 : 1; k <= perRun; k++) {
                double t = k / (double) perRun;
                out.add(a.add(run.scale(t)).add(across.scale(4 * t * (1 - t))));
            }
        }
        return out;
    }

    /** A Bezier curve of any degree through its control points, as {@code segments + 1} points. */
    public static List<Vec3> bezier(List<Vec3> control, int segments) {
        List<Vec3> out = new ArrayList<>(segments + 1);
        for (int k = 0; k <= segments; k++) {
            double t = k / (double) segments;
            Vec3[] p = control.toArray(Vec3[]::new);
            for (int m = p.length - 1; m > 0; m--) for (int j = 0; j < m; j++) p[j] = p[j].lerp(p[j + 1], t);
            out.add(p[0]);
        }
        return out;
    }
}
