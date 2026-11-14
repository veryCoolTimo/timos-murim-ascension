package io.github.verycooltimo.murim.client.dev;

import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Camera-relative world coordinates through the exact world-render matrices. */
public final class CaptureProjection {
    public record Point(double x, double y, double depth, boolean visible) {
        public Object[] json() { return new Object[]{x, y, depth, visible}; }
    }

    public static Point project(double x, double y, double z, Matrix4f view, Matrix4f projection,
                                int width, int height) {
        Vector4f camera = view.transform(new Vector4f((float) x, (float) y, (float) z, 1));
        double depth = -camera.z;
        Vector4f clip = projection.transform(new Vector4f(camera));
        // Camera-plane points have no finite perspective division. Keep valid JSON numbers.
        if (Math.abs(clip.w) < 1e-8) return new Point(0, 0, depth, false);
        double nx = clip.x / clip.w, ny = clip.y / clip.w;
        return new Point((nx + 1) * width / 2.0, (1 - ny) * height / 2.0, depth,
                depth > 0 && clip.w > 0 && nx >= -1 && nx <= 1 && ny >= -1 && ny <= 1);
    }

    private CaptureProjection() { }
}
