package io.github.verycooltimo.murim.client.vfx.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;

/**
 * The light along the lines: every bold branch of the 3D tree gets a wide soft halo under it that follows its own space curve (as SLASH_HALO
 * did for the slash), in its family's colour, drawn ADDITIVELY. It is a ribbon of vertex colours: the alpha is {@code a} on the centre line and 0
 * on both edges, so the fall-off is the interpolation: no per-pixel work at all. Where a branch is still growing or sliding off, the halo
 * fades with the ink's own taper. Uses the vanilla position-colour shader (MurimRenderTypes#presetLight: SRC_ALPHA, ONE).
 */
public final class FxHalo {
    /** The halo colour of each family (ink, violet ribbons, blue ribbons), 0xRRGGBB. */
    public static final int[] COLORS = {0x6A3CD8, 0x8A50E0, 0x4048D0};
    /** Halos only for the bold branches (traced half-width in blocks), their width as a multiple of the ink's, their peak alpha. */
    public static final double MIN_HALF = .045, WIDTH = 3.0, ALPHA = .42, MAX_WIDTH = .36, MIN_WIDTH = .1;

    private FxHalo() {}

    private static float[] sx = new float[256], sy = new float[256], sz = new float[256], hw = new float[256], fade = new float[256];

    public static void draw(FxTree3D tree, double t, Vec3 camera, Vec3 feet, Vec3 right, Vec3 near, PoseStack.Pose pose, MultiBufferSource.BufferSource buffers, double glowGain) {
        long t0 = System.nanoTime();
        var type = MurimRenderTypes.presetLight();
        VertexConsumer out = buffers.getBuffer(type);
        Vec3 d = camera.subtract(feet);
        double cx = d.dot(right), cy = d.y, cz = d.dot(near);
        int[] count = {0};
        tree.visit(t, (b, from, drawn, head, tail, gen) -> {
            if (b.meanHalf < MIN_HALF) return;
            int n = b.size();
            if (sx.length < n) { sx = new float[n]; sy = new float[n]; sz = new float[n]; hw = new float[n]; fade = new float[n]; }
            float[] along = b.along;
            double total = b.length;
            int last = 0;
            while (last < n - 1 && along[last + 1] / total <= drawn) last++;
            last = Math.min(n - 1, last + 1);
            float[] p = tree.flow(b, b.smooth, last, gen);
            double px = 0, py = 1, pz = 0;
            boolean has = false;
            for (int i = 0; i <= last; i++) {
                int a = Math.max(0, i - 1), c = Math.min(n - 1, i + 1);
                double tx = p[3 * c] - p[3 * a], ty = p[3 * c + 1] - p[3 * a + 1], tz = p[3 * c + 2] - p[3 * a + 2];
                double vx = cx - p[3 * i], vy = cy - p[3 * i + 1], vz = cz - p[3 * i + 2];
                double ex = ty * vz - tz * vy, ey = tz * vx - tx * vz, ez = tx * vy - ty * vx, el = ex * ex + ey * ey + ez * ez;
                if (el < 1e-10 * (tx * tx + ty * ty + tz * tz) * (vx * vx + vy * vy + vz * vz) || el < 1e-18) { ex = px; ey = py; ez = pz; }
                else { double k = 1 / Math.sqrt(el); ex *= k; ey *= k; ez *= k; if (has && ex * px + ey * py + ez * pz < 0) { ex = -ex; ey = -ey; ez = -ez; } }
                px = ex; py = ey; pz = ez; has = true;
                sx[i] = (float) ex; sy[i] = (float) ey; sz[i] = (float) ez;                  // unit side vector for now
            }
            for (int i = 0; i <= last; i++) {
                double ink = FxTree3D.meshHalf(b, i, head, tail) * FxStroke.floorFade(p[3 * i + 1]), full = Math.max(b.h[i] * FxTree3D.WIDTH[b.family], .0015) / FxTree3D.MAIN_SHARE;
                double w = Math.min(Math.max(full * WIDTH, MIN_WIDTH), Math.min(MAX_WIDTH, turnLimit(p, n, i)));
                // where the side vector swings fast (the path runs along the line of sight) the ribbon would twist into a spike: narrow it there
                double twist = 1;
                for (int j = Math.max(0, i - 2); j <= Math.min(last, i + 2); j++) twist = Math.min(twist, sx[i] * sx[j] + sy[i] * sy[j] + sz[i] * sz[j]);
                w *= Math.clamp(twist, .12, 1);
                hw[i] = (float) w;
                fade[i] = (float) Math.clamp(ink / full, 0, 1);
            }
            for (int i = 0; i <= last; i++) { sx[i] *= hw[i]; sy[i] *= hw[i]; sz[i] *= hw[i]; }
            int rgb = COLORS[b.family], r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, bl = rgb & 255;
            for (int i = 0; i < n - 1; i++) {
                if (along[i] / total > drawn) break;
                if (along[i + 1] / total < from) continue;
                int a0 = (int) Math.round(Math.clamp(ALPHA * glowGain * fade[i], 0, 1) * 255), a1 = (int) Math.round(Math.clamp(ALPHA * glowGain * fade[i + 1], 0, 1) * 255);
                // two quads: left edge -> centre line, centre line -> right edge
                quad(out, pose, feet, right, near, p, i, r, g, bl, a0, a1, -1);
                quad(out, pose, feet, right, near, p, i, r, g, bl, a0, a1, 1);
                count[0] += 8;
            }
        });
        buffers.endBatch(type);
        FxPerf.vertices(count[0] - 0);
        FxPerf.draw();
        FxPerf.build(System.nanoTime() - t0);
    }

    /**
     * The widest half-width a ribbon may have at point i without folding over itself: 0.8 of the path's turning radius there (segment length over
     * the angle between the two segments at the point), the smallest over the point and its two neighbours on each side so the width does not jump. Mirrored in tools/vfx-lab/cult_lab.py.
     */
    public static double turnLimit(float[] p, int n, int i) {
        double lim = Double.MAX_VALUE;
        for (int j = Math.max(1, i - 2); j <= Math.min(n - 2, i + 2); j++) {
            double ax = p[3 * j] - p[3 * j - 3], ay = p[3 * j + 1] - p[3 * j - 2], az = p[3 * j + 2] - p[3 * j - 1];
            double bx = p[3 * j + 3] - p[3 * j], by = p[3 * j + 4] - p[3 * j + 1], bz = p[3 * j + 5] - p[3 * j + 2];
            double la = Math.sqrt(ax * ax + ay * ay + az * az), lb = Math.sqrt(bx * bx + by * by + bz * bz);
            if (la < 1e-9 || lb < 1e-9) continue;
            double cos = Math.clamp((ax * bx + ay * by + az * bz) / (la * lb), -1, 1), ang = Math.acos(cos);
            if (ang < 1e-3) continue;
            lim = Math.min(lim, .8 * .5 * (la + lb) / ang);
        }
        return lim;
    }

    /** One half of the ribbon's segment i: the edge (alpha 0) and the centre line (alpha a) at both ends. */
    private static void quad(VertexConsumer out, PoseStack.Pose pose, Vec3 feet, Vec3 right, Vec3 near, float[] p, int i, int r, int g, int b, int a0, int a1, int side) {
        vertex(out, pose, feet, right, near, p[3 * i] + side * sx[i], p[3 * i + 1] + side * sy[i], p[3 * i + 2] + side * sz[i], r, g, b, 0);
        vertex(out, pose, feet, right, near, p[3 * i], p[3 * i + 1], p[3 * i + 2], r, g, b, a0);
        vertex(out, pose, feet, right, near, p[3 * i + 3], p[3 * i + 4], p[3 * i + 5], r, g, b, a1);
        vertex(out, pose, feet, right, near, p[3 * i + 3] + side * sx[i + 1], p[3 * i + 4] + side * sy[i + 1], p[3 * i + 5] + side * sz[i + 1], r, g, b, 0);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, Vec3 feet, Vec3 right, Vec3 near, double x, double y, double z, int r, int g, int b, int a) {
        out.addVertex(pose, (float) (feet.x + right.x * x + near.x * z), (float) (feet.y + y), (float) (feet.z + right.z * x + near.z * z))
                .setColor(r, g, b, a).setUv(0, 0).setUv1(0, 0).setUv2(0, 0).setNormal(0, 0, 1);
    }
}
