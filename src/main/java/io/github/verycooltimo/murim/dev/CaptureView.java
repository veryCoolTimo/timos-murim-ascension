package io.github.verycooltimo.murim.dev;

/** Pure camera geometry; angles use Minecraft's yaw and downward-positive pitch convention. */
public final class CaptureView {
    public record Point(double x, double y, double z) {}
    public record Pose(Point position, float yaw, float pitch) {}

    public static Pose victim(Point targetEye, Point casterHead, Double aboveHorizon) {
        double dx = casterHead.x - targetEye.x;
        double dy = casterHead.y - targetEye.y;
        double dz = casterHead.z - targetEye.z;
        return new Pose(targetEye, (float) Math.toDegrees(Math.atan2(-dx, dz)),
                (float) (aboveHorizon == null
                        ? -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))) : -aboveHorizon));
    }

    /** Desired unobstructed vanilla third-person pose; vanilla performs the wall probes. */
    public static Pose third(Point casterEye, Point targetEye, double distance) {
        float yaw = victim(casterEye, targetEye, null).yaw;
        double radians = Math.toRadians(yaw);
        return new Pose(new Point(casterEye.x + Math.sin(radians) * distance,
                casterEye.y, casterEye.z - Math.cos(radians) * distance), yaw, 0);
    }

    private CaptureView() {}
}
