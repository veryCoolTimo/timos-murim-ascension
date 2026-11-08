package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Dev-only observer camera: one rendered frame per client tick, with bounded tolerance for isolated missed ticks.
 * API verified in /tmp/mr-ref-1121/minecraft-src: Minecraft#get/setCameraEntity/getMainRenderTarget,
 * Screenshot#grab, Options#setCameraType/fov, ClientLevel#entitiesForRendering,
 * ArmorStand(Level,double,double,double), Entity#moveTo/getEyeHeight and ServerboundMovePlayerPacket.PosRot.
 * NeoForge: ClientTickEvent.Post and RenderLevelStageEvent#getStage/Stage.AFTER_LEVEL.
 */
public final class BladeExamFilmCapture {
    private ArmorStand camera;
    private Vec3 origin;
    private Vec3 frontEye;
    private Vec3 frontTarget;
    private long joined = -1;
    private long lastTick = -1;
    private int frames;
    private int gaps;
    private long clientTick;
    private long lastSignal = -1;
    private boolean rejected;
    private float wantedYaw;
    private final boolean front = "front".equals(System.getProperty("murim.capture.camera"));
    private final int limit = Integer.parseInt(System.getenv().getOrDefault("MURIM_CAPTURE_FRAMES", "1200"));

    /** Fixed stand axes; deliberately independent of the pupil's client look direction. */
    static final class Placement {
        private Placement() {}

        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/phys/Vec3.java#add
        static Vec3 eye(Vec3 ring, double pupilEyeHeight) {
            return ring.add(-1.8, pupilEyeHeight, 2.6);
        }

        static Vec3 target(Vec3 ring, double pupilEyeHeight) {
            // DiscipleRenderer#bladeExamPose: forehead is 0.13 above the eyes.
            return ring.add(-0.5, pupilEyeHeight + 0.13, 0);
        }

        static boolean faceHidden(Vec3 eye, Vec3 pupil) {
            return eye.x >= pupil.x;
        }
    }

    @SubscribeEvent
    public void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        // API: /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/client/event/ClientTickEvent.java#Post
        // World time can move backward on a server time update; this clock counts actual client ticks.
        clientTick++;
        if (joined < 0) joined = clientTick;
        if (clientTick - joined < 100) return;
        SectDisciple mentor = null;
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof SectDisciple d && d.memberKey().equals("gyeong_pil")
                    && d.distanceToSqr(mc.player) < 100) mentor = d;
        }
        if (mentor == null) return;
        if (camera == null) {
            origin = mc.player.position().add(-1.5, 0, 0); // pupil is initially 1.5 blocks outside the ring
            // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/Entity.java#getEyeHeight
            // Capture height once so movement, pose and look updates cannot move the composition.
            frontEye = Placement.eye(origin, mc.player.getEyeHeight());
            frontTarget = Placement.target(origin, mc.player.getEyeHeight());
            camera = new ArmorStand(mc.level, origin.x, origin.y, origin.z);
            mc.setCameraEntity(camera);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.hideGui = true;
            mc.options.fov().set(front ? 75 : 55);
        }
        // Preserve the accepted side composition exactly.
        Vec3 target = origin.add(-1.0, 1.85, 0);
        Vec3 wanted = origin.add(-1.0, 1.85, 5.0);
        if (front) {
            target = frontTarget;
            wanted = frontEye;
        }
        Vec3 eye = DevCaptureHandler.clearCameraEye(mc, target, wanted);
        if (eye.distanceToSqr(wanted) > 0.01 && !rejected) {
            rejected = true;
            MurimMod.LOGGER.info("CAPTURE-BLADE-CAMERA rejected reason=occluded");
        }
        Vec3 direction = target.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
        camera.moveTo(eye.x, eye.y - camera.getEyeHeight(), eye.z, yaw, pitch);
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/LivingEntity.java#getViewYRot/setYHeadRot/yHeadRotO
        // The render camera interpolates head yaw, independently of moveTo's body rotation.
        camera.setYHeadRot(yaw);
        camera.yHeadRotO = yaw;
        wantedYaw = yaw;
        // Vanilla stops sending player positions when another entity is the camera. Keep real ground/movement input alive.
        if (mc.getConnection() != null) {
            var p = mc.player;
            mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                    p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.onGround()));
        }
    }

    @SubscribeEvent
    public void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || camera == null || frames >= limit || rejected) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (frames == 0) {
            // API: /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/client/event/RenderLevelStageEvent.java#getPartialTick/getProjectionMatrix
            // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/DeltaTracker.java#getGameTimeDeltaPartialTick
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/Entity.java#getViewVector/getEyePosition
            // getViewVector uses getViewXRot/getViewYRot, including LivingEntity's head interpolation.
            Vec3 view = camera.getViewVector(partialTick);
            Vec3 toPupil = mc.player.getEyePosition(partialTick).subtract(camera.getEyePosition(partialTick)).normalize();
            // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/phys/Vec3.java#dot/normalize
            if (front && Placement.faceHidden(camera.getEyePosition(partialTick),
                    mc.player.getEyePosition(partialTick))) {
                rejected = true;
                MurimMod.LOGGER.info("CAPTURE-BLADE-CAMERA rejected reason=pupil_face_hidden");
                return;
            }
            double angle = Math.acos(Math.clamp(view.dot(toPupil), -1.0, 1.0));
            // GameRenderer#getProjectionMatrix uses a vertical FOV and the viewport aspect ratio.
            // Its perspective matrix's X scale is cot(horizontal FOV / 2), including actual FOV effects.
            double halfHorizontalFov = Math.atan(1.0 / event.getProjectionMatrix().m00());
            MurimMod.LOGGER.info("CAPTURE-BLADE-CAMERA view_yaw={} wanted_yaw={} angle={} half_horizontal_fov={}",
                    camera.getViewYRot(partialTick), wantedYaw, Math.toDegrees(angle), Math.toDegrees(halfHorizontalFov));
            if (!(angle < halfHorizontalFov)) {
                rejected = true;
                MurimMod.LOGGER.info("CAPTURE-BLADE-CAMERA rejected reason=subjects_out_of_view");
                return;
            }
        }
        long tick = clientTick;
        if (tick == lastTick) return;
        if (lastTick >= 0 && tick != lastTick + 1) {
            long missing = tick - lastTick - 1;
            gaps++;
            MurimMod.LOGGER.info("CAPTURE-BLADE-GAP previous={} tick={} missing={} count={}", lastTick, tick, missing, gaps);
            if (missing >= 3 || gaps > 5) {
                MurimMod.LOGGER.info("CAPTURE-BLADE-CAMERA rejected reason=frame_gap previous={} tick={}", lastTick, tick);
            }
        }
        lastTick = tick;
        MurimMod.LOGGER.info("CAPTURE-BLADE-FRAME frame={} tick={}", frames, tick);
        Screenshot.grab(mc.gameDirectory, String.format("murim_blade_%04d.png", frames++), mc.getMainRenderTarget(), message -> {});
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/multiplayer/ClientPacketListener.java#sendCommand
        // Send only after requesting this frame: the server must never call the exam before frame zero.
        // Space commands by 20 client ticks to stay within vanilla's chat-command spam budget.
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/server/network/ServerGamePacketListenerImpl.java#detectRateSpam
        if (mc.getConnection() != null && (lastSignal < 0 || tick - lastSignal >= 20)) {
            mc.getConnection().sendCommand("murim dev blade_exam frame " + tick);
            lastSignal = tick;
        }
    }
}
