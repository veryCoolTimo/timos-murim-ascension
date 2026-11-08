package io.github.verycooltimo.murim.client.camera;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
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
    private static final ShotPose SHOT = new ShotPose(0, 7, 2.6F, .62F, 0, .8F);
    // Same static front pose as breakthrough.json bt_wide (the bt_film default for all ranks).
    private static final ShotPose BENCH_SHOT = new ShotPose(8, 12, 2.4F, .62F, 0, .7F);
    private static ShotPose shot() {
        return io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.benchActive() ? BENCH_SHOT : SHOT;
    }
    private static ArmorStand camera;

    private FlowCamera() { }

    public static boolean active() { return camera != null; }

    public static void tick(Minecraft mc, boolean wanted) {
        if (!wanted || mc.player == null || mc.level == null || !mc.player.isAlive()
                || camera != null && camera.level() != mc.level) {
            reset();
            return;
        }
        if (camera == null) {
            camera = new ArmorStand(EntityType.ARMOR_STAND, mc.level);
            camera.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, (byte) ArmorStand.CLIENT_FLAG_SMALL);
            mc.setCameraEntity(camera);
        }
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
    }

    public static void reset() {
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
        float yaw = mc.player.yBodyRot;
        Vec3 feet = mc.player.getPosition(partial);
        Vec3 focus = CameraRig.focusPoint(feet, shot), direction = CameraRig.direction(yaw, shot);
        float distance = CameraRig.allowed(focus, direction, shot.distance(), (from, to) -> {
            HitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
            return hit.getType() == HitResult.Type.MISS ? from.distanceTo(to) : from.distanceTo(hit.getLocation());
        });
        Vec3 at = focus.add(direction.scale(Math.max(CameraRig.MIN_DISTANCE, distance)));
        camera.setPos(at.x, at.y - camera.getEyeHeight(), at.z);
        camera.xo = camera.getX();
        camera.yo = camera.getY();
        camera.zo = camera.getZ();
        event.setYaw(CameraRig.lookYaw(yaw, shot));
        event.setPitch(CameraRig.lookPitch(shot));
        event.setRoll(0);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onDistance(CalculateDetachedCameraDistanceEvent event) {
        if (camera != null && event.getCamera().getEntity() == camera) event.setDistance(0);
    }

    @SubscribeEvent
    static void onFov(ViewportEvent.ComputeFov event) {
        if (camera != null && event.getCamera().getEntity() == camera) event.setFOV(event.getFOV() * shot().fov());
    }
}
