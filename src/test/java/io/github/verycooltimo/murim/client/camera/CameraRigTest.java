package io.github.verycooltimo.murim.client.camera;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Camera geometry and the wall pull-in. */
class CameraRigTest {

    private static final Vec3 FEET = new Vec3(10.0D, 64.0D, 10.0D);
    private static final CameraRig.Clear OPEN = (from, to) -> from.distanceTo(to);

    @Test
    void livePoseRoundTrips() {
        ShotPose meditation = new ShotPose(0, 18, 1.6F, .95F, 0, 1);
        for (ShotPose original : new ShotPose[] {meditation,
                new ShotPose(30, 25, 2.2F, .7F, 0, 1), meditation.withDistance(.65F)}) {
            float bodyYaw = 47;
            Vec3 camera = CameraRig.cameraPos(FEET, bodyYaw, original, original.distance());
            float yaw = CameraRig.lookYaw(bodyYaw, original);
            float pitch = CameraRig.lookPitch(original);
            ShotPose recovered = CameraRig.poseOf(FEET, bodyYaw, FEET.add(0, .95, 0), camera,
                    yaw, pitch, meditation);
            assertTrue(CameraRig.cameraPos(FEET, bodyYaw, recovered, recovered.distance()).distanceTo(camera) < .01);
            double yawError = Math.IEEEremainder(CameraRig.lookYaw(bodyYaw, recovered) - yaw, 360);
            assertEquals(0, yawError, .5);
            assertEquals(pitch, CameraRig.lookPitch(recovered), .5);
            assertEquals(original.focus(), recovered.focus(), .01);
            assertEquals(0, recovered.roll());
            assertEquals(1, recovered.fov());
        }
    }

    @Test
    void livePoseFallbacks() {
        ShotPose fallback = new ShotPose(0, 18, 1.6F, .95F, 0, 1);
        Vec3 eyes = FEET.add(0, .95, 0);
        assertEquals(fallback, CameraRig.poseOf(FEET, 0, eyes, eyes.add(0, 0, .29), 180, 18, fallback));
        Vec3 front = eyes.add(0, 0, 2);
        assertEquals(fallback, CameraRig.poseOf(FEET, 0, eyes, front, 0, 18, fallback), "points away");
        assertEquals(fallback, CameraRig.poseOf(FEET, 0, eyes, front, 150, 18, fallback), "misses axis");
        assertEquals(fallback, CameraRig.poseOf(FEET, 0, eyes, front, 180, 90, fallback), "vertical ray");
    }

    @Test
    @DisplayName("A blocking block pulls the intro camera in front of its face with near-plane clearance")
    void blockingBlock() {
        var block = new net.minecraft.world.phys.AABB(9, 63, 12, 11, 67, 13);
        Vec3 direction = new Vec3(0, 0, 1);
        float distance = CameraRig.allowed(FEET, direction, 8, (from, to) ->
                block.clip(from, to).map(from::distanceTo).orElseGet(() -> from.distanceTo(to)));
        Vec3 camera = FEET.add(direction.scale(distance));
        assertEquals(1.7, distance, 1.0E-5);
        assertTrue(12 - camera.z >= 0.3, "camera must remain in front of the blocking block");
        assertTrue(distance >= CameraRig.MIN_DISTANCE);
    }

    @Test
    @DisplayName("Azimuth 0 is in front of the player, +90 on the player's right, 180 behind (yaw 0 faces +Z)")
    void azimuth() {
        Vec3 front = CameraRig.direction(0.0F, new ShotPose(0, 0, 1, 1, 0, 1));
        assertEquals(0.0D, front.x, 1.0E-6D);
        assertEquals(1.0D, front.z, 1.0E-6D);
        // Facing south (+Z), the right hand points west (-X).
        Vec3 right = CameraRig.direction(0.0F, new ShotPose(90, 0, 1, 1, 0, 1));
        assertEquals(-1.0D, right.x, 1.0E-6D);
        Vec3 behind = CameraRig.direction(0.0F, new ShotPose(180, 0, 1, 1, 0, 1));
        assertEquals(-1.0D, behind.z, 1.0E-6D);
        // The player's yaw rotates the whole rig: facing west (yaw 90), "front" is -X.
        Vec3 turned = CameraRig.direction(90.0F, new ShotPose(0, 0, 1, 1, 0, 1));
        assertEquals(-1.0D, turned.x, 1.0E-6D);
    }

    @Test
    @DisplayName("Pitch lifts the camera above the focus (+) or drops it below (-); the distance is kept")
    void pitch() {
        ShotPose high = new ShotPose(0, 45, 2.0F, 1.0F, 0, 1);
        Vec3 pos = CameraRig.cameraPos(FEET, 0.0F, high, 2.0F);
        assertEquals(65.0D + 2.0D * Math.sin(Math.toRadians(45)), pos.y, 1.0E-5D);
        assertEquals(2.0D, pos.distanceTo(CameraRig.focusPoint(FEET, high)), 1.0E-5D);
        ShotPose low = new ShotPose(0, -20, 2.0F, 1.0F, 0, 1);
        assertTrue(CameraRig.cameraPos(FEET, 0.0F, low, 2.0F).y < 65.0D);
        assertEquals(180.0F, CameraRig.lookYaw(0.0F, high), 1.0E-6F, "looks back at the player");
        assertEquals(45.0F, CameraRig.lookPitch(high), 1.0E-6F, "looks down when above");
    }

    @Test
    @DisplayName("An open field lets the camera sit at the wanted distance")
    void open() {
        Vec3 dir = new Vec3(0.0D, 0.0D, 1.0D);
        assertEquals(3.0F, CameraRig.allowed(FEET, dir, 3.0F, OPEN), 1.0E-5F);
        assertEquals(1.0F, CameraRig.room(FEET.add(0, 1, 0), dir, 3.0F, OPEN), 1.0E-5F);
    }

    @Test
    @DisplayName("A wall pulls the camera in and keeps the margin; a wall at the focus collapses it to zero, never negative")
    void wall() {
        Vec3 dir = new Vec3(0.0D, 0.0D, 1.0D);
        CameraRig.Clear wallAt2 = (from, to) -> Math.min(from.distanceTo(to), 2.0D);
        assertEquals(2.0F - CameraRig.MARGIN, CameraRig.allowed(FEET, dir, 4.0F, wallAt2), 1.0E-5F);
        assertEquals((2.0F - CameraRig.MARGIN) / 4.0F, CameraRig.room(FEET, dir, 4.0F, wallAt2), 1.0E-5F,
                "room is the share of the wanted distance");
        CameraRig.Clear far = (from, to) -> Math.min(from.distanceTo(to), 5.0D);
        assertEquals(1.0F, CameraRig.allowed(FEET, dir, 1.0F, far), 1.0E-5F, "a distant wall changes nothing");
        CameraRig.Clear flush = (from, to) -> 0.1D;
        assertEquals(0.0F, CameraRig.allowed(FEET, dir, 3.0F, flush), 1.0E-6F);
    }

    @Test
    @DisplayName("A wall just behind the wanted spot still keeps the margin")
    void wallBehindTheSpot() {
        Vec3 dir = new Vec3(0.0D, 0.0D, 1.0D);
        CameraRig.Clear wall = (from, to) -> Math.min(from.distanceTo(to), 3.1D);
        assertEquals(3.1F - CameraRig.MARGIN, CameraRig.allowed(FEET, dir, 3.0F, wall), 1.0E-5F);
    }

    @Test
    @DisplayName("The tightest of the five rays decides: a post beside the line pulls the camera in")
    void cornerRay() {
        Vec3 dir = new Vec3(0.0D, 0.0D, 1.0D);
        // Only rays starting right of the centre line (x > 10.05) meet the wall.
        CameraRig.Clear post = (from, to) -> from.x > 10.05D ? 1.5D : from.distanceTo(to);
        assertEquals(1.5F - CameraRig.MARGIN, CameraRig.allowed(FEET, dir, 3.0F, post), 1.0E-5F);
    }
}
