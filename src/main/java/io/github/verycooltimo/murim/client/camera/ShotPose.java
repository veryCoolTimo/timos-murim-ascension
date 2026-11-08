package io.github.verycooltimo.murim.client.camera;

import net.minecraft.util.Mth;

/**
 * One camera pose of a breakthrough shot, in the shot's own terms (not world coordinates), so the same shot
 * works wherever the player sits. Pure data: no Minecraft client classes (unit-tested in {@code ShotTrackTest}).
 *
 * <p>The camera always looks at one focus point on the seated body and sits on a sphere around it:
 *
 * @param azimuth  degrees around the player: 0 = camera in front of the face, +90 = to the player's right,
 *                 180 = behind the back
 * @param pitch    degrees of elevation of the camera above the focus point: + = camera above, looking down;
 *                 - = camera below, looking up (the ground shot)
 * @param distance blocks from the focus point to the camera (before the wall check pulls it in)
 * @param focus    height of the focus point above the player's feet, blocks (seated eyes are ~0.95, the lap ~0.45)
 * @param roll     degrees of dutch tilt
 * @param fov      multiplier of the player's configured field of view (below 1 = a longer lens, for faces)
 */
public record ShotPose(float azimuth, float pitch, float distance, float focus, float roll, float fov) {

    /** The old static shot: front, 1.6 blocks, 18 degrees down (the plan's fallback). */
    public static final ShotPose FRONT = new ShotPose(0.0F, 18.0F, 1.6F, 0.95F, 0.0F, 1.0F);

    public ShotPose lerp(ShotPose to, float k) {
        return new ShotPose(Mth.lerp(k, azimuth, to.azimuth), Mth.lerp(k, pitch, to.pitch),
                Mth.lerp(k, distance, to.distance), Mth.lerp(k, focus, to.focus),
                Mth.lerp(k, roll, to.roll), Mth.lerp(k, fov, to.fov));
    }

    /** The mirror image: side -1 swaps left and right (azimuth and roll change sign). */
    public ShotPose mirrored(int side) {
        return side >= 0 ? this : new ShotPose(-azimuth, pitch, distance, focus, -roll, fov);
    }

    public ShotPose withDistance(float d) {
        return new ShotPose(azimuth, pitch, d, focus, roll, fov);
    }

    float[] channels() {
        return new float[] {azimuth, pitch, distance, focus, roll, fov};
    }

    static ShotPose of(float[] c) {
        return new ShotPose(c[0], c[1], c[2], c[3], c[4], c[5]);
    }
}
