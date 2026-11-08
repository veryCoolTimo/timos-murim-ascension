package io.github.verycooltimo.murim.client.camera;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientConfig;
import io.github.verycooltimo.murim.client.ClientMeditationState;
import io.github.verycooltimo.murim.client.sect.DialogueCamera;
import io.github.verycooltimo.murim.cultivation.Realm;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
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

import java.util.List;

/**
 * Camera director of the rank breakthrough scene (author 19.10: «можно попробовать сделать разные ракурсы камер»).
 *
 * <p>Replaces the old static front close-up with a sequence of shots per rank ({@link BreakthroughPlan}: closeup,
 * low angle, over the shoulder, orbit, crane), cut or glided together, and hands the camera back with a short glide.
 *
 * <p>How: the camera is a client-only invisible armor stand set as {@link Minecraft#setCameraEntity} (the same trick
 * as {@code DialogueCamera}), so position, yaw, pitch, roll and field of view are ours. The pose is computed in
 * {@link ViewportEvent.ComputeCameraAngles}, which vanilla fires inside {@code Camera#setup} BEFORE it reads the
 * entity position, so every frame gets the exact pose of its own sub-tick time (no 20 Hz stepping). The camera type
 * is third-person with the vanilla distance forced to 0, so the mod's {@code isFirstPerson()} checks see what they
 * saw with the old third-person-front camera. API: reference/minecraft-src/net/minecraft/client/Camera.java#setup,
 * reference/neoforge-src/net/neoforged/neoforge/client/event/ViewportEvent.java#ComputeCameraAngles.
 *
 * <p>Walls: the camera never sits closer than {@link CameraRig#MARGIN} to a block. The wall distance is measured
 * every frame along the shot's own direction (five rays, like vanilla's box) and also a few ticks ahead, so the camera
 * pulls in smoothly before a wall and eases out after it; the side an orbit turns to is the one with more room.
 *
 * <p>Accessibility: the config toggle {@code breakthroughCamera} off leaves the player's own camera; the camera
 * shake slider scales how far every shot moves, and at 0 each shot holds its rest pose (cuts stay, no glides and no
 * dutch tilt), as rule 04 asks for «полное отсутствие движения камеры».
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class BreakthroughCamera {

    private enum Phase { IDLE, RUN, RETURN }

    /** The camera for one frame. */
    private record Frame(Vec3 pos, float yaw, float pitch, float roll, float fov) {
    }

    /** How far ahead (ticks) the wall is checked, so the pull-in starts before the wall, not at it. */
    private static final float LOOK_AHEAD = 8.0F;
    /** Per second: how fast the camera pulls in when a wall closes in / eases out when it opens. */
    private static final float PULL_IN = 10.0F;
    private static final float EASE_OUT = 2.0F;
    /** Distance of the vanilla third-person camera for the normal view the return glides to. */
    private static final double NORMAL_THIRD_PERSON = 4.0D;
    /** Closest the camera may be to the focus point when a wall is right behind the player. */
    private static final float CLOSEST = 0.4F;

    private static BreakthroughPlan plan = BreakthroughPlan.fallback();
    private static Phase phase = Phase.IDLE;
    private static ArmorStand cam;
    private static int side = 1;
    private static float bodyYaw;
    private static float ratio = 1.0F;
    private static float lastTime;
    private static int lastIndex = -1;
    private static Frame lastFrame;
    private static int returnAge;
    private static CameraType returnType = CameraType.THIRD_PERSON_BACK;
    private static List<BreakthroughPlan.Step> steps = List.of();
    private static float totalTicks;
    private static boolean creation;
    private static String debug = "";

    // Dev overrides for the capture stand (DevCaptureHandler): null = not set.
    private static String debugShots;
    private static Float debugMotion;
    private static boolean debugOff;

    private BreakthroughCamera() {
    }

    static void setPlan(BreakthroughPlan loaded) {
        plan = loaded;
    }

    /** Capture stand: play exactly these shots ({@code "1,3"}, {@code "orbit:2,crane:1"}) instead of the rank's plan. */
    public static void debugShots(String spec) {
        debugShots = spec == null || spec.isBlank() ? null : spec;
    }

    /** Capture stand: pretend the shake slider is at this motion scale (0..1). */
    public static void debugMotion(Float motion) {
        debugMotion = motion;
    }

    /** Capture stand: behave as if the player turned the director off. */
    public static void debugOff(boolean off) {
        debugOff = off;
    }

    /** The last frame's numbers, for the capture stand's log (what the wall did to the shot). */
    public static String debugLine() {
        return debug;
    }

    /** The director may take the camera: the player allows it and no conversation holds it. */
    public static boolean enabled() {
        return !debugOff && ClientConfig.BREAKTHROUGH_CAMERA.get() && !DialogueCamera.active() && !io.github.verycooltimo.murim.client.sect.HeadIntroCamera.active();
    }

    /** The director owns the camera: playing a scene or gliding back. */
    public static boolean active() {
        return phase != Phase.IDLE;
    }

    /** How far shots move: the shake slider as a share; 0 = hold rest poses. */
    private static float motion() {
        return debugMotion != null ? debugMotion : (float) Math.max(0.0D, Math.min(1.0D, ClientConfig.cameraShake()));
    }

    /**
     * Once per client tick from {@code ClientMeditationState#updateCamera}.
     *
     * @param want    the scene wants a directed camera now
     * @param restore the camera type to glide back to (the one the player had before the scene)
     */
    public static void tick(Minecraft mc, boolean want, CameraType restore) {
        if (mc.player == null || mc.level == null || !mc.player.isAlive() || cam != null && cam.level() != mc.level) {
            reset();
            return;
        }
        switch (phase) {
            case IDLE -> {
                if (want && enabled()) {
                    begin(mc);
                }
            }
            case RUN -> {
                if (!want || !enabled()) {
                    startReturn(restore);
                }
            }
            case RETURN -> {
                if (want && enabled()) {
                    phase = Phase.RUN;
                } else {
                    returnAge++;
                    // First person: the last stretch of the glide would end inside the head, so hand over a little early.
                    float limit = returnType == CameraType.FIRST_PERSON ? 0.8F * plan.returnTicks() : plan.returnTicks();
                    if (returnAge >= limit) {
                        release(mc);
                    }
                }
            }
            default -> {
            }
        }
    }

    private static void begin(Minecraft mc) {
        int rank = ClientMeditationState.breakthroughTarget();
        creation = ClientMeditationState.seedSceneAge() >= 0;
        totalTicks = creation ? ClientMeditationState.SEED_SCENE_TICKS : Realm.breakthroughTicks(rank);
        bodyYaw = ClientMeditationState.sceneYaw(mc.player.getYRot());
        // API: Minecraft 1.21.1 renderer/GameRenderer.java#getMainCamera; client/Camera.java#getPosition/getYRot/getXRot;
        // world/entity/Entity.java#position/getEyePosition. Capture before replacing the camera entity/type.
        var live = mc.gameRenderer.getMainCamera();
        steps = creation ? BreakthroughPlan.creationSteps(CameraRig.poseOf(mc.player.position(), bodyYaw,
                mc.player.getEyePosition(), live.getPosition(), live.getYRot(), live.getXRot(),
                ClientMeditationState.closeUpPose())) : debugShots != null ? plan.sequence(debugShots) : plan.steps(rank);
        cam = new ArmorStand(EntityType.ARMOR_STAND, mc.level);
        // A small stand has an eye height of 0.99, close to the seated player's 0.95: the camera's own eye-height
        // smoothing then has nothing to catch up with when the entity changes.
        cam.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, (byte) ArmorStand.CLIENT_FLAG_SMALL);
        side = chooseSide(mc);
        ratio = 1.0F;
        lastIndex = -1;
        lastTime = 0.0F;
        lastFrame = null;
        phase = Phase.RUN;
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        mc.setCameraEntity(cam);
        StringBuilder names = new StringBuilder();
        for (BreakthroughPlan.Step s : steps) {
            names.append(names.length() == 0 ? "" : ">").append(s.shot().name());
        }
        MurimMod.LOGGER.info("Breakthrough camera: rank {} shots {} side {} scene {} ticks motion {}", rank, names, side,
                (int) totalTicks, motion());
    }

    private static void startReturn(CameraType restore) {
        returnType = restore == null ? CameraType.THIRD_PERSON_BACK : restore;
        if (lastFrame == null || plan.returnTicks() <= 0) {
            release(Minecraft.getInstance());
            return;
        }
        phase = Phase.RETURN;
        returnAge = 0;
    }

    private static void release(Minecraft mc) {
        if (cam != null && mc.player != null) {
            mc.setCameraEntity(mc.player);
        }
        cam = null;
        phase = Phase.IDLE;
        lastFrame = null;
    }

    /** Immediately back to the player's eyes (leaving the world, death, a conversation). */
    public static void reset() {
        Minecraft mc = Minecraft.getInstance();
        if (cam != null) {
            mc.setCameraEntity(mc.player);
        }
        cam = null;
        phase = Phase.IDLE;
        lastFrame = null;
    }

    /**
     * The turn direction with more room: samples the whole sequence for both sides, compares the tightest spot
     * (then the average), ties go to +1. A wall behind the player on one side no longer drags the orbit into it.
     */
    private static int chooseSide(Minecraft mc) {
        CameraRig.Clear clear = clear(mc);
        Vec3 feet = mc.player.position();
        float motion = motion();
        float[] worst = {Float.MAX_VALUE, Float.MAX_VALUE};
        float[] sum = new float[2];
        int samples = 24;
        for (int s = 0; s < 2; s++) {
            int sign = s == 0 ? 1 : -1;
            for (int i = 0; i < samples; i++) {
                float tick = totalTicks * (i + 0.5F) / samples;
                ShotPose pose = BreakthroughPlan.poseAt(steps, tick, totalTicks, sign, motion);
                float room = CameraRig.room(CameraRig.focusPoint(feet, pose), CameraRig.direction(bodyYaw, pose), pose.distance(), clear);
                worst[s] = Math.min(worst[s], room);
                sum[s] += room;
            }
        }
        if (worst[1] > worst[0] + 0.02F || Math.abs(worst[1] - worst[0]) <= 0.02F && sum[1] > sum[0] + 0.5F) {
            return -1;
        }
        return 1;
    }

    private static CameraRig.Clear clear(Minecraft mc) {
        // API: reference/minecraft-src/net/minecraft/world/level/BlockGetter.java#clip (as the vanilla camera does)
        return (from, to) -> {
            HitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
            return hit.getType() == HitResult.Type.MISS ? from.distanceTo(to) : from.distanceTo(hit.getLocation());
        };
    }

    /** Scene time in ticks: the scene itself, then the aura-release window (the last pose holds). */
    private static float sceneTime(float partial) {
        if(creation)return Math.max(0,ClientMeditationState.seedSceneAge())+partial;
        int age = ClientMeditationState.breakthroughAge();
        if (age >= 0) {
            return age + partial;
        }
        int rankUp = ClientMeditationState.rankUpAge();
        if (rankUp >= 0) {
            return totalTicks + rankUp + partial;
        }
        return totalTicks + ClientMeditationState.rankUpLength();
    }

    private static Frame runFrame(Minecraft mc, float partial) {
        float t = sceneTime(partial);
        float motion = motion();
        BreakthroughPlan.Cue cue = BreakthroughPlan.cue(steps, t, totalTicks);
        ShotPose pose = BreakthroughPlan.poseAt(steps, t, totalTicks, side, motion);
        Vec3 feet = mc.player.getPosition(partial);
        CameraRig.Clear clear = clear(mc);
        Vec3 target = CameraRig.focusPoint(feet, pose);
        Vec3 dir = CameraRig.direction(bodyYaw, pose);
        float want = Math.max(0.01F, pose.distance());
        float allowedNow = CameraRig.allowed(target, dir, want, clear);
        // Look a few ticks ahead inside the same shot (a cut changes the view anyway).
        float aheadTick = t + LOOK_AHEAD;
        BreakthroughPlan.Cue ahead = BreakthroughPlan.cue(steps, aheadTick, totalTicks);
        float wantRatio = allowedNow / want;
        if (ahead.index() == cue.index() && motion > 0.01F) {
            ShotPose p2 = BreakthroughPlan.poseAt(steps, aheadTick, totalTicks, side, motion);
            wantRatio = Math.min(wantRatio, CameraRig.room(CameraRig.focusPoint(feet, p2), CameraRig.direction(bodyYaw, p2),
                    Math.max(0.01F, p2.distance()), clear));
        }
        float dt = Math.max(0.0F, t - lastTime) / 20.0F;
        lastTime = t;
        boolean cut = cue.index() != lastIndex && (cue.step().blend() <= 0 || lastIndex < 0);
        lastIndex = cue.index();
        if (cut) {
            ratio = wantRatio;
        } else if (wantRatio < ratio) {
            ratio += (wantRatio - ratio) * (1.0F - (float) Math.exp(-dt * PULL_IN));
        } else {
            ratio += (wantRatio - ratio) * (1.0F - (float) Math.exp(-dt * EASE_OUT));
        }
        // Never beyond what the wall allows right now, however the smoothing lags.
        float dist = Math.min(want * ratio, allowedNow);
        dist = Math.max(dist, Math.min(CLOSEST, want));
        Vec3 pos = target.add(dir.scale(dist));
        debug = String.format(java.util.Locale.ROOT, "shot %s side %d az %.0f pitch %.0f want %.2f allowed %.2f ratio %.2f dist %.2f",
                cue.step().shot().name(), side, pose.azimuth(), pose.pitch(), want, allowedNow, ratio, dist);
        return new Frame(pos, CameraRig.lookYaw(bodyYaw, pose), CameraRig.lookPitch(pose),
                pose.roll() * Math.min(1.0F, motion), pose.fov());
    }

    /** The view the player would have without the director, for the glide back. */
    private static Frame normalView(Minecraft mc, float partial) {
        Vec3 eye = mc.player.getEyePosition(partial);
        float yaw = mc.player.getViewYRot(partial);
        float pitch = mc.player.getViewXRot(partial);
        if (returnType == CameraType.FIRST_PERSON) {
            return new Frame(eye, yaw, pitch, 0.0F, 1.0F);
        }
        boolean front = returnType == CameraType.THIRD_PERSON_FRONT;
        Vec3 look = Vec3.directionFromRotation(pitch, yaw);
        Vec3 back = front ? look : look.scale(-1.0D);
        float room = CameraRig.allowed(eye, back, (float) NORMAL_THIRD_PERSON, clear(mc));
        return new Frame(eye.add(back.scale(room)), front ? yaw + 180.0F : yaw, front ? -pitch : pitch, 0.0F, 1.0F);
    }

    private static Frame returnFrame(Minecraft mc, float partial) {
        float k = Mth.clamp((returnAge + partial) / Math.max(1, plan.returnTicks()), 0.0F, 1.0F);
        k = k * k * (3.0F - 2.0F * k);
        Frame to = normalView(mc, partial);
        Frame from = lastFrame;
        return new Frame(from.pos().lerp(to.pos(), k), from.yaw() + Mth.wrapDegrees(to.yaw() - from.yaw()) * k,
                Mth.lerp(k, from.pitch(), to.pitch()), Mth.lerp(k, from.roll(), to.roll()), Mth.lerp(k, from.fov(), to.fov()));
    }

    /**
     * Runs before the shake handler and the old camera code (HIGH): the shake still adds on top of our angles
     * (rule 04: angles are added, never overwritten, and the shake is itself scaled by the slider).
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (phase == Phase.IDLE || cam == null || event.getCamera().getEntity() != cam) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        float partial = (float) event.getPartialTick();
        Frame f = phase == Phase.RUN ? runFrame(mc, partial) : returnFrame(mc, partial);
        lastFrame = f;
        // The stand's "eye" is where the camera goes; xo = x so the vanilla lerp returns exactly this position.
        cam.setPos(f.pos().x, f.pos().y - cam.getEyeHeight(), f.pos().z);
        cam.xo = cam.getX();
        cam.yo = cam.getY();
        cam.zo = cam.getZ();
        event.setYaw(f.yaw());
        event.setPitch(f.pitch());
        event.setRoll(f.roll());
    }

    /** The vanilla third-person offset is not wanted: the stand's position already is the camera. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onDistance(CalculateDetachedCameraDistanceEvent event) {
        if (cam != null && event.getCamera().getEntity() == cam) {
            event.setDistance(0.0F);
        }
    }

    @SubscribeEvent
    static void onFov(ViewportEvent.ComputeFov event) {
        if (cam != null && lastFrame != null && event.getCamera().getEntity() == cam) {
            event.setFOV(event.getFOV() * lastFrame.fov());
        }
    }
}
