package io.github.verycooltimo.murim.client.camera;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import io.github.verycooltimo.murim.client.flow.FlowClient;
import io.github.verycooltimo.murim.client.flow.FlowLook;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** Static seated-figure shot. API: BreakthroughCamera's detached ArmorStand and CameraRig wall probes.
 * ClientMeditationState owns the saved camera mode and restores it when the scene ends.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class FlowCamera {
    // [НЕПРОВЕРЕНО: whole seated figure with breathing room; verify at the configured FOV.]
    private static final ShotPose SHOT = new ShotPose(0, 7, 2.6F, .62F, 0, .7F);
    // Same static front pose as breakthrough.json bt_wide (the bt_film default for all ranks).
    private static final ShotPose BENCH_SHOT = new ShotPose(8, 12, 2.4F, .62F, 0, .7F);
    private static ShotPose shot() {
        return io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.benchActive() ? BENCH_SHOT : SHOT;
    }
    private static ArmorStand camera;
    private static float sceneFov = -1, trackFov = -1;
    /** The vertical field of view (degrees) the last frame used, for the sideways shift of the scroll framing. */
    private static float curFovDeg = 70;
    /** The pose of the shot before any impulse (shake): the scroll hangs in the world from it, so a shake of the camera shakes the view of the scroll too. */
    private static Vec3 restPos;
    private static float restYaw, restPitch;

    /** Rest pose of the game camera: {x, y, z, yaw, pitch, vertical fov in degrees}, or null when the camera is not ours. */
    public static double[] restPose() { return camera == null || restPos == null ? null : new double[] {restPos.x, restPos.y, restPos.z, restYaw, restPitch, curFovDeg}; }
    /** The smooth return (no cut): after the effect the camera glides from the last shot to the view the player would have, then hands over. */
    private static boolean returning;
    private static int returnAge;
    private static CameraType returnType = CameraType.THIRD_PERSON_BACK;
    private static Vec3 lastPos;
    private static float lastYaw, lastPitch, lastFov = 1;
    private static final double NORMAL_THIRD_PERSON = 4.0D;
    private static boolean fovLogged;
    /** Dev (film of the side view): MURIM_FLOW_AZ degrees round the player, 0 = the front shot. */
    private static final float DEV_AZIMUTH = devAzimuth();

    private static float devAzimuth() {
        try { return Float.parseFloat(System.getenv().getOrDefault("MURIM_FLOW_AZ", "0")); } catch (NumberFormatException e) { return 0; }
    }

    private FlowCamera() { }

    public static boolean active() { return camera != null; }

    /** True during the glide back to the player's own view. */
    public static boolean returning() { return returning; }

    /**
     * @param restore the camera type the player had before the scene (the glide goes to its view)
     * @param glide   false when another director takes the camera at once (no glide)
     */
    public static void tick(Minecraft mc, boolean wanted, CameraType restore, boolean glide) {
        if (mc.player == null || mc.level == null || !mc.player.isAlive() || camera != null && camera.level() != mc.level) {
            reset();
            return;
        }
        if (!wanted) {
            if (camera != null && glide && lastPos != null && FlowClient.CAM_GLIDE) {
                if (!returning) { returning = true; returnAge = 0; returnType = restore == null ? CameraType.THIRD_PERSON_BACK : restore; }
                returnAge++;
                float limit = returnType == CameraType.FIRST_PERSON ? .8F * FlowLook.CAM_RETURN_TICKS : FlowLook.CAM_RETURN_TICKS;
                if (returnAge >= limit) reset();
                else mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            } else {
                reset();
            }
            return;
        }
        returning = false;
        if (camera == null) {
            camera = new ArmorStand(EntityType.ARMOR_STAND, mc.level);
            camera.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, (byte) ArmorStand.CLIENT_FLAG_SMALL);
            mc.setCameraEntity(camera);
        }
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
    }

    public static void reset() {
        io.github.verycooltimo.murim.client.flow.PillScene.reset();
        sceneFov = -1;
        returning = false;
        lastPos = null;
        Minecraft mc = Minecraft.getInstance();
        if (camera != null && mc.getCameraEntity() == camera) mc.setCameraEntity(mc.player);
        camera = null;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (camera == null || mc.player == null || mc.level == null || event.getCamera().getEntity() != camera) return;
        float partial = (float) event.getPartialTick();
        // Follow the actual seated torso, including a pill finale's small body lift.
        Vec3 chest = io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.position(mc.player,
                io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.CHEST);
        ShotPose base = shot();
        ShotPose shot = base == BENCH_SHOT || chest == null ? base : new ShotPose(0, SHOT.pitch(), SHOT.distance(),
                (float) (chest.y - mc.player.getY() - .26D), 0, SHOT.fov());
        float track = io.github.verycooltimo.murim.client.flow.FlowClient.cameraDistance();
        if (track > 0 && base != BENCH_SHOT) {
            shot = new ShotPose(DEV_AZIMUTH, io.github.verycooltimo.murim.client.flow.FlowClient.cameraPitch(), track,
                    shot.focus() + FlowClient.cameraFocusLift() + FlowClient.cameraNudge() - FlowLook.CAM_LIFT_SHARE * io.github.verycooltimo.murim.client.ClientPillState.lift(partial), 0, FlowLook.CAM_FOV);
            trackFov = FlowLook.CAM_FOV;
        } else {
            trackFov = -1;
        }
        // The pill absorption scene steers this same camera (one director): its focus is an offset above the chest.
        ShotPose scene = chest == null ? null : io.github.verycooltimo.murim.client.flow.PillScene.pose(partial);
        if (scene != null) {
            shot = new ShotPose(scene.azimuth(), scene.pitch(), scene.distance(),
                    (float) (chest.y - mc.player.getY()) + scene.focus(), 0, scene.fov());
            sceneFov = scene.fov();
        } else {
            sceneFov = -1;
        }
        float yaw = mc.player.yBodyRot;
        Vec3 feet = mc.player.getPosition(partial);
        Vec3 focus = CameraRig.focusPoint(feet, shot), direction = CameraRig.direction(yaw, shot);
        float distance = CameraRig.allowed(focus, direction, shot.distance(), (from, to) -> {
            HitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
            return hit.getType() == HitResult.Type.MISS ? from.distanceTo(to) : from.distanceTo(hit.getLocation());
        });
        Vec3 at = focus.add(direction.scale(Math.max(scene != null ? .3F : CameraRig.MIN_DISTANCE, distance)));
        if (scene != null) io.github.verycooltimo.murim.client.flow.PillScene.dof(focus, at, partial);
        float lookYaw = CameraRig.lookYaw(yaw, shot), lookPitch = CameraRig.lookPitch(shot);
        float fovMult = sceneFov > 0 ? sceneFov : shot.fov();
        if (returning) {
            // the glide back: from the last shot to the normal view, smoothstep over the return ticks
            float limit = returnType == CameraType.FIRST_PERSON ? .8F * FlowLook.CAM_RETURN_TICKS : FlowLook.CAM_RETURN_TICKS;
            float k = Mth.clamp((returnAge + partial) / limit, 0, 1);
            k = k * k * (3 - 2 * k);
            Vec3 eye = mc.player.getEyePosition(partial);
            float vy = mc.player.getViewYRot(partial), vp = mc.player.getViewXRot(partial);
            Vec3 viewDir = Vec3.directionFromRotation(vp, vy);
            boolean front = returnType == CameraType.THIRD_PERSON_FRONT;
            Vec3 toPos;
            if (returnType == CameraType.FIRST_PERSON) {
                toPos = eye;
            } else {
                Vec3 back = front ? viewDir : viewDir.scale(-1);
                float room = CameraRig.allowed(eye, back, (float) NORMAL_THIRD_PERSON, (from, to) -> {
                    HitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
                    return hit.getType() == HitResult.Type.MISS ? from.distanceTo(to) : from.distanceTo(hit.getLocation());
                });
                toPos = eye.add(back.scale(room));
            }
            // the glide goes straight from the last shot to the normal view (never round the player): where the straight line would pass through the
            // figure, it rises over the head on the way
            Vec3 r0 = lastPos, r1 = toPos;
            double hDist = Math.hypot(eye.x - lastPos.x, eye.z - lastPos.z);
            double ex = r1.x - r0.x, ez = r1.z - r0.z, el2 = ex * ex + ez * ez;
            double along = el2 < 1e-6 ? 0 : Mth.clamp(((eye.x - r0.x) * ex + (eye.z - r0.z) * ez) / el2, 0, 1);
            double miss = Math.hypot(eye.x - (r0.x + ex * along), eye.z - (r0.z + ez * along));
            double bump = miss < 1.3 ? (1.3 - miss) / 1.3 * 2.2 : 0;
            at = r0.lerp(r1, k).add(0, bump * Math.sin(Math.PI * k), 0);
            // it looks at the figure first and at the player's own view direction at the end
            Vec3 t0 = eye.add(0, -.25, 0), dir1 = front ? viewDir.scale(-1) : viewDir, t1 = at.add(dir1.scale(8));
            float kk = k * k;
            Vec3 tgt = t0.lerp(t1, kk);
            Vec3 d = tgt.subtract(at);
            double dl = Math.max(1e-6, d.length());
            lookYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            lookPitch = (float) Math.toDegrees(-Math.asin(d.y / dl));
            fovMult = Mth.lerp(k, lastFov, 1);
            sceneFov = fovMult;
        } else {
            // the scroll framing: the camera slides sideways (no turn, no orbit) so the figure stands in the left two thirds; the share eases with the game
            float shift = scene == null && track > 0 && base != BENCH_SHOT ? FlowClient.cameraShiftShare() : 0F;
            if (shift > 1e-4F) {
                double tanH = Math.tan(Math.toRadians(curFovDeg) / 2) * (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
                double d = shift * tanH * Math.max(.5F, Math.min(distance, shot.distance()));
                double yr = Math.toRadians(lookYaw);
                at = at.add(-Math.cos(yr) * d, 0, -Math.sin(yr) * d);
            }
            lastPos = at; lastYaw = lookYaw; lastPitch = lookPitch; lastFov = fovMult;
        }
        restPos = at; restYaw = lookYaw; restPitch = lookPitch;
        camera.setPos(at.x, at.y - camera.getEyeHeight(), at.z);
        camera.xo = camera.getX();
        camera.yo = camera.getY();
        camera.zo = camera.getZ();
        // the camera's impulses (CameraShakeHandler adds its angles before this handler, normal priority) are put back on top of the shot
        event.setYaw(lookYaw + io.github.verycooltimo.murim.client.CameraShakeHandler.addedYaw());
        event.setPitch(lookPitch + io.github.verycooltimo.murim.client.CameraShakeHandler.addedPitch());
        event.setRoll(io.github.verycooltimo.murim.client.CameraShakeHandler.addedRoll());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onDistance(CalculateDetachedCameraDistanceEvent event) {
        if (camera != null && event.getCamera().getEntity() == camera) event.setDistance(0);
    }

    @SubscribeEvent
    static void onFov(ViewportEvent.ComputeFov event) {
        if (camera != null && event.getCamera().getEntity() == camera) {
            if (!fovLogged) { fovLogged = true; MurimMod.LOGGER.info("Flow camera base fov {} multiplier {}", event.getFOV(), shot().fov()); }
            event.setFOV(event.getFOV() * (sceneFov > 0 ? sceneFov : (trackFov > 0 ? trackFov : shot().fov())));
            curFovDeg = (float) event.getFOV();
        }
    }
}
