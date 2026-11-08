package io.github.verycooltimo.murim.client.camera;

import net.minecraft.world.phys.Vec3;

/**
 * Geometry of a breakthrough shot: where a {@link ShotPose} puts the camera and how far a wall lets it go.
 * Pure maths (like {@code DialogueShot}); the world enters only through {@link Clear}.
 *
 * <p>Angles follow the vanilla yaw convention: yaw 0 looks south (+Z), yaw 90 looks west, so the player's forward
 * vector is {@code (-sin yaw, 0, cos yaw)}. The camera sits on a sphere around the focus point; its look yaw is the
 * direction towards the focus (azimuth + 180).
 */
public final class CameraRig {

    /** How many blocks of free space {@code from -> to} has; the full length when nothing is in the way. */
    @FunctionalInterface
    public interface Clear {
        double free(Vec3 from, Vec3 to);
    }

    /** Distance kept between the camera and the wall the ray hits: the near plane must not cut into a block. */
    public static final float MARGIN = 0.3F;
    /** Closer than this the camera would sit inside the body; a wall may not pull it further in. */
    public static final float MIN_DISTANCE = 0.55F;
    /** Rays start this far off the centre line (four corners), like the vanilla camera's 0.1 box. */
    private static final double SPREAD = 0.12D;

    private CameraRig() {
    }

    /**
     * Recover a rig pose from a live view ray. Its closest approach to the feet's vertical axis is the focus.
     * Views inside the eyes, parallel to the axis, pointing away, or missing the body axis use the supplied close-up.
     * API: Minecraft 1.21.1 world/phys/Vec3.java (public coordinates, distanceToSqr).
     */
    public static ShotPose poseOf(Vec3 feet, float bodyYaw, Vec3 eyes, Vec3 camera,
                                  float lookYaw, float lookPitch, ShotPose fallback) {
        if (camera.distanceToSqr(eyes) < 0.3D * 0.3D) {
            return fallback;
        }
        double yaw = Math.toRadians(lookYaw);
        double pitch = Math.toRadians(lookPitch);
        double dx = -Math.sin(yaw) * Math.cos(pitch);
        double dy = -Math.sin(pitch);
        double dz = Math.cos(yaw) * Math.cos(pitch);
        double flat = dx * dx + dz * dz;
        if (flat < 1.0E-8D) {
            return fallback;
        }
        double x = camera.x - feet.x;
        double z = camera.z - feet.z;
        double distance = -(x * dx + z * dz) / flat;
        double missX = x + distance * dx;
        double missZ = z + distance * dz;
        if (!Double.isFinite(distance) || distance <= 0.0D || missX * missX + missZ * missZ > 0.01D * 0.01D) {
            return fallback;
        }
        float azimuth = (lookYaw - bodyYaw - 180.0F) % 360.0F;
        if (azimuth < -180.0F) azimuth += 360.0F;
        if (azimuth >= 180.0F) azimuth -= 360.0F;
        return new ShotPose(azimuth, lookPitch, (float) distance,
                (float) (camera.y + distance * dy - feet.y), 0.0F, 1.0F);
    }

    public static Vec3 focusPoint(Vec3 feet, ShotPose pose) {
        return feet.add(0.0D, pose.focus(), 0.0D);
    }

    /** Unit vector from the focus point to the camera. */
    public static Vec3 direction(float playerYaw, ShotPose pose) {
        double a = Math.toRadians(playerYaw + pose.azimuth());
        double p = Math.toRadians(pose.pitch());
        double flat = Math.cos(p);
        return new Vec3(-Math.sin(a) * flat, Math.sin(p), Math.cos(a) * flat);
    }

    public static Vec3 cameraPos(Vec3 feet, float playerYaw, ShotPose pose, float distance) {
        return focusPoint(feet, pose).add(direction(playerYaw, pose).scale(distance));
    }

    /** Yaw the camera looks with: back at the focus point. */
    public static float lookYaw(float playerYaw, ShotPose pose) {
        return playerYaw + pose.azimuth() + 180.0F;
    }

    /** Pitch the camera looks with (positive = looking down), the same angle as the elevation. */
    public static float lookPitch(ShotPose pose) {
        return pose.pitch();
    }

    /**
     * The farthest the camera may sit along {@code dir} from {@code target}, up to {@code desired}: the shortest of
     * five rays (centre and four corners) minus {@link #MARGIN}. The vanilla third-person camera does the same
     * with one distance; this one is asked every frame for the shot's own direction.
     */
    public static float allowed(Vec3 target, Vec3 dir, float desired, Clear clear) {
        Vec3 right = dir.cross(new Vec3(0.0D, 1.0D, 0.0D));
        right = right.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : right.normalize();
        Vec3 up = right.cross(dir).normalize();
        // The rays reach MARGIN past the wanted spot, so a wall right behind it also keeps its distance.
        double reach = desired + MARGIN;
        double min = reach;
        for (int i = 0; i < 5; i++) {
            double dx = i == 0 ? 0.0D : ((i & 1) == 0 ? -SPREAD : SPREAD);
            double dy = i == 0 ? 0.0D : ((i & 2) == 0 ? -SPREAD : SPREAD);
            Vec3 from = target.add(right.scale(dx)).add(up.scale(dy));
            min = Math.min(min, clear.free(from, from.add(dir.scale(reach))));
        }
        double limit = Math.max(0.0D, Math.min(desired, min - MARGIN));
        float distance = (float) limit;
        // A float cast can round towards the wall (e.g. 2 - MARGIN), losing near-plane clearance.
        return distance > limit ? Math.nextDown(distance) : distance;
    }

    /** Share of the wanted distance a wall leaves (0..1): the score for choosing the free side of an orbit. */
    public static float room(Vec3 target, Vec3 dir, float desired, Clear clear) {
        return desired <= 1.0E-4F ? 1.0F : allowed(target, dir, desired, clear) / desired;
    }
}
