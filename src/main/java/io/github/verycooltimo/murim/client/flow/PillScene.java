package io.github.verycooltimo.murim.client.flow;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientConfig;
import io.github.verycooltimo.murim.client.ClientMeditationState;
import io.github.verycooltimo.murim.client.ClientProfileState;
import io.github.verycooltimo.murim.client.camera.BreakthroughCamera;
import io.github.verycooltimo.murim.client.camera.CinematicDof;
import io.github.verycooltimo.murim.client.camera.ShotPose;
import io.github.verycooltimo.murim.client.sect.HeadIntroCamera;
import io.github.verycooltimo.murim.cultivation.PillScenePlanner;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.network.PillPayloads;
import io.github.verycooltimo.murim.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client director of the pill absorption scene (ordinary and middle pills). It owns no camera of its own: the flow camera
 * ({@code FlowCamera}) asks {@link #pose} each frame, so there is never a second director; the scene never starts under a
 * breakthrough or head-intro camera, nor for a manual (high) pill or a torn body.
 *
 * <p>Skip: Space or the attack key held 0.4 s, or the stand keys, jumps to the core beat; a stand is then sent when the scene
 * ends. An interruption (the server cut the session, the player is no longer seated) hands the camera back in 0.3 s.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class PillScene {
    private static final int HOLD_SKIP = 8;
    private static final int BLEND = 8;
    private static final int RETURN = 6;

    private static boolean active;
    private static int ticks;
    private static long started;
    private static float skipRaw = -1, skipWarp;
    private static ShotPose skipFrom;
    private static float abortRaw = -1;
    private static ShotPose abortFrom;
    private static ShotPose last = PillScenePlan.REST;
    private static int hold;
    private static boolean soundPlayed, standAfter;
    private static int sentFull = -1;
    private static int colour = 0xE8F4EC;
    private static boolean plum;

    private PillScene() { }

    public static boolean active() { return active; }

    public static int ticks() { return ticks; }

    public static int colour() { return colour; }

    /** True for plum pills: only they throw plum petals in the burst. */
    public static boolean plum() { return plum; }

    static void setPlum(boolean value) { plum = value; }

    /** The scene's hue: the pill's colour; a near-white pill (Snow Plum) gets pale plum pink so nothing can wash out to white. */
    static int hue(int rgb) {
        int min = Math.min(rgb >> 16 & 255, Math.min(rgb >> 8 & 255, rgb & 255));
        return min >= 0xC8 ? 0xE8A0B8 : rgb;
    }

    /** Lens multiplier of the last frame (for pixel sizes). */
    public static float lastFov() { return last.fov(); }

    /** Starts the scene for an automatic ordinary or middle pill; false when another director owns the camera. */
    public static boolean begin(PillPayloads.FlowStart start, int pillColour) {
        reset();
        Minecraft mc = Minecraft.getInstance();
        if (start.scene() <= 0 || !start.automatic() || start.torn() || mc.level == null || mc.player == null
                || BreakthroughCamera.active() || HeadIntroCamera.active()) {
            return false;
        }
        ticks = start.scene();
        started = mc.level.getGameTime();
        colour = hue(pillColour);
        active = true;
        PillSceneRenderer.clear();
        return true;
    }

    public static void reset() {
        if (active) CinematicDof.off();
        active = false;
        skipRaw = abortRaw = -1;
        skipFrom = abortFrom = null;
        last = PillScenePlan.REST;
        hold = 0;
        soundPlayed = false;
        PillSceneRenderer.clear();
    }

    private static float raw(float partial) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0 : mc.level.getGameTime() - started + partial;
    }

    /** Plan time in ticks (a skip shifts it). */
    public static float age(float partial) {
        float raw = raw(partial) / PillScenePlanner.SLOWDOWN;
        return skipRaw < 0 ? raw : raw + skipWarp;
    }

    /** 1 while the scene runs normally, fading to 0 over the return after an interruption. */
    public static float strength(float partial) {
        return abortRaw < 0 ? 1 : 1 - PillScenePlan.smooth((raw(partial) - abortRaw) / RETURN);
    }

    /** The camera pose (focus is an offset above the chest) for this frame, or null when the scene is off. */
    public static ShotPose pose(float partial) {
        if (!active) return null;
        float raw = raw(partial);
        float age = age(partial);
        ShotPose pose = PillScenePlan.pose(ticks, age);
        if (skipRaw >= 0 && skipFrom != null) {
            pose = skipFrom.lerp(pose, PillScenePlan.smooth((raw - skipRaw) / BLEND));
        }
        if (abortRaw >= 0 && abortFrom != null) {
            pose = abortFrom.lerp(PillScenePlan.REST, PillScenePlan.smooth((raw - abortRaw) / RETURN));
        }
        last = pose;
        return pose;
    }

    /** Depth of field for this frame; the focus is the shot's own focus point. */
    public static void dof(net.minecraft.world.phys.Vec3 focus, net.minecraft.world.phys.Vec3 camera, float partial) {
        if (!active) return;
        float age = age(partial);
        float env = PillScenePlan.envelope(ticks, age) * strength(partial);
        // The depth-of-field pass is a full-screen blur that cost about 2 ms per frame at 1080p on the Mac (14.11 measurement), and with the whole body
        // in the sharp range it only softened the far background. It is off by default; MURIM_PILL_DOF=1 turns it on (dev), and even then the
        // quality governor of the scene's renderer drops it when frames get slow.
        boolean cheap = PillSceneRenderer.qualityTier() >= 2 || !"1".equals(System.getenv("MURIM_PILL_DOF"));
        CinematicDof.focus(focus, camera, cheap ? 0 : PillScenePlan.blur(ticks, age), PillScenePlan.range(ticks, age), env);
    }

    /** Overlay strength of the 25 % vignette. */
    public static float vignette(float partial) {
        return active ? PillScenePlan.vignette(ticks, age(partial)) * strength(partial) * (.7F / .45F) : 0;
    }

    /** Share of the stored qi in the wall (the ratio QiHud draws), 0..1. */
    public static float qiShare() {
        var p = ClientProfileState.profile();
        return (float) Mth.clamp(p.pool() / Math.max(1.0D, Realm.wall(p)), 0.0D, 1.0D);
    }

    /** Skip to the core beat (once, before it). */
    public static void skip() { skip("hold"); }

    private static void skip(String why) {
        if (!active || skipRaw >= 0 || abortRaw >= 0) return;
        float a = age(0);
        if (a >= PillScenePlan.skipTarget(ticks)) return;
        MurimMod.LOGGER.info("PILLSCENE skip ({}) at age {}", why, a);
        float r = raw(0);
        skipFrom = last;
        skipRaw = r;
        skipWarp = PillScenePlan.skipTarget(ticks) - r / PillScenePlanner.SLOWDOWN;
        PillSceneRenderer.clear();
    }

    /** A stand key was pressed: true when the scene takes it (it skips ahead and the stand is sent at its end). */
    public static boolean deferStand() {
        if (!active || abortRaw >= 0) return false;
        if (age(0) >= PillScenePlan.core(ticks) + 12) return false;
        skip("stand key");
        standAfter = true;
        return true;
    }

    /** True once, when a deferred stand is due (the scene has ended). */
    public static boolean consumeStandAfter() {
        if (active || !standAfter) return false;
        standAfter = false;
        return true;
    }

    /** The server cut the session (a blow, a stand): the camera goes back in 0.3 s. */
    public static void interrupted() {
        if (!active || abortRaw >= 0) return;
        MurimMod.LOGGER.info("PILLSCENE interrupted at age {}", age(0));
        abortRaw = raw(0);
        abortFrom = last;
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) syncSetting();
        if (!active) return;
        if (mc.level == null || mc.player == null || !mc.player.isAlive()) {
            reset();
            return;
        }
        float raw = raw(0);
        if (abortRaw < 0 && raw > 4 && !ClientMeditationState.state().active()) interrupted();
        if (abortRaw >= 0 && raw - abortRaw >= RETURN) { end(); return; }
        if (abortRaw < 0) {
            boolean down = mc.options.keyJump.isDown() || mc.options.keyAttack.isDown();
            hold = down && !"1".equals(System.getenv("MURIM_CAPTURE_NOSKIP")) ? hold + 1 : 0;
            if (hold >= HOLD_SKIP) skip();
            float age = age(0);
            if (!soundPlayed && age >= PillScenePlan.finishSound(ticks)) {
                soundPlayed = true;
                mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), ModSounds.ABSORB_SUCCESS.get(),
                        SoundSource.PLAYERS, .8F, 1.0F, false);
            }
            if (age >= PillScenePlan.end(ticks)) end();
        }
    }

    private static void end() {
        reset();
    }

    /** Keeps the server informed of the «full pill scene always» setting (it decides how long the player sits). */
    private static void syncSetting() {
        int now = ClientConfig.PILL_SCENE_ALWAYS_FULL.get() ? 1 : 0;
        if (now != sentFull && Minecraft.getInstance().getConnection() != null) {
            sentFull = now;
            PacketDistributor.sendToServer(new PillPayloads.SceneFull(now == 1));
        }
    }

    @SubscribeEvent
    static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) { sentFull = -1; }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { reset(); standAfter = false; sentFull = -1; }
}
