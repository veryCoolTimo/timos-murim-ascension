package io.github.verycooltimo.murim.client.flow;

import io.github.verycooltimo.murim.client.camera.ShotPose;
import io.github.verycooltimo.murim.client.camera.ShotTrack;
import io.github.verycooltimo.murim.cultivation.PillScenePlanner;
import java.util.List;

/**
 * Shot plan of the pill absorption scene (ordinary and middle pills). Times are client ticks since the start of the sitting;
 * the full scene is {@link PillScenePlanner#FULL_TICKS} (7.2 s), the short one keeps only the melting and the core.
 *
 * <p>One camera track runs through the whole scene, so the moves between beats are eased and nothing pops. {@code focus} of a key
 * is an offset above the seated chest (the director adds the live chest height), {@code azimuth 0} is in front of the face; the
 * last key is the pose of the plain flow camera, so the scene hands the view back without a jump. Pure data (unit-tested).
 */
public final class PillScenePlan {
    public enum Beat { PILL, MELT, INSIDE, CORE, EXHALE }

    public record Segment(Beat beat, int from, int until) { }

    /** The flow camera's pose, with its focus as an offset from the chest (FlowCamera: chest - .26). */
    public static final ShotPose REST = new ShotPose(0, 7, 2.6F, -.26F, 0, .8F);

    private static final List<Segment> FULL = List.of(
            new Segment(Beat.PILL, 0, 24), new Segment(Beat.MELT, 24, 52), new Segment(Beat.INSIDE, 52, 92),
            new Segment(Beat.CORE, 92, 128), new Segment(Beat.EXHALE, 128, 144));
    private static final List<Segment> SHORT = List.of(
            new Segment(Beat.MELT, 0, 28), new Segment(Beat.CORE, 28, 62), new Segment(Beat.EXHALE, 62, 70));

    /** Ticks after the plan's end in which the camera eases from the flow pose to the ordinary meditation view. */
    public static final int SETTLE = 10;
    /** The ordinary third-person front view the game returns to (about four blocks, level, eyes in frame). */
    public static final ShotPose DEFAULT = new ShotPose(0, 2, 4.0F, .30F, 0, 1.0F);

    private PillScenePlan() { }

    /** Last tick of the scene, the settle included. */
    public static int end(int ticks) { return total(ticks) + SETTLE; }

    public static boolean isFull(int ticks) { return ticks >= PillScenePlanner.FULL_TICKS; }

    public static List<Segment> segments(int ticks) { return isFull(ticks) ? FULL : SHORT; }

    public static Segment segment(int ticks, float age) {
        List<Segment> all = segments(ticks);
        for (Segment s : all) if (age < s.until()) return s;
        return all.getLast();
    }

    public static int total(int ticks) { return segments(ticks).getLast().until(); }

    public static int start(int ticks, Beat beat) {
        for (Segment s : segments(ticks)) if (s.beat() == beat) return s.from();
        return -1;
    }

    /** First tick of the melting beat. */
    public static int melt(int ticks) { return start(ticks, Beat.MELT); }

    /** First tick of the core beat. */
    public static int core(int ticks) { return start(ticks, Beat.CORE); }

    /** Tick a skip jumps to: the camera has finished its pull-back and the core is on show. */
    public static int skipTarget(int ticks) { return isFull(ticks) ? 104 : 40; }

    private static ShotTrack.Key key(int ticks, int tick, ShotPose pose) {
        return new ShotTrack.Key(tick / (float) total(ticks), pose);
    }

    /** The camera track of the whole scene, over 0..1 of {@link #total}. One framing for the whole sitting (author 11.11: the camera does not jump,
     *  nothing leaves the frame): the seated body fills the picture from the head to the lap, and a slow push-out hands over to the flow camera. */
    public static ShotTrack track(int ticks) {
        List<ShotTrack.Key> keys = isFull(ticks) ? List.of(
                key(ticks, 0, new ShotPose(0, -3, 1.65F, .02F, 0, 1.0F)),
                key(ticks, 92, new ShotPose(0, -4, 1.75F, -.02F, 0, 1.0F)),
                key(ticks, 128, new ShotPose(0, -2, 2.2F, -.08F, 0, .95F)),
                key(ticks, 144, REST)) : List.of(
                key(ticks, 0, new ShotPose(0, -3, 1.65F, .02F, 0, 1.0F)),
                key(ticks, 38, new ShotPose(0, -4, 1.75F, -.02F, 0, 1.0F)),
                key(ticks, 56, new ShotPose(0, -2, 2.2F, -.08F, 0, .95F)),
                key(ticks, 70, REST));
        return new ShotTrack("pill_scene", 0, ShotTrack.Ease.LINEAR, 0, false, keys);
    }

    public static ShotPose pose(int ticks, float age) {
        if (age > total(ticks)) return REST.lerp(DEFAULT, smooth((age - total(ticks)) / SETTLE));
        return track(ticks).at(Math.max(0, Math.min(1, age / total(ticks))));
    }

    public static float smooth(float x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }

    private static float table(float age, float[] at, float[] value) {
        if (age <= at[0]) return value[0];
        for (int i = 1; i < at.length; i++) {
            if (age <= at[i]) return value[i - 1] + (value[i] - value[i - 1]) * smooth((age - at[i - 1]) / (at[i] - at[i - 1]));
        }
        return value[value.length - 1];
    }

    /** Blur radius in px at a 1080-line frame (CinematicDof scales it by frame height). */
    public static float blur(int ticks, float age) {
        return isFull(ticks) ? table(age, new float[] {0, 24, 52, 62, 92, 104}, new float[] {6, 6, 6, 3.5F, 3.5F, 6})
                : table(age, new float[] {0, 28, 38}, new float[] {10, 10, 6});
    }

    /** Depth in blocks around the focus point that stays sharp. */
    public static float range(int ticks, float age) {
        return isFull(ticks) ? table(age, new float[] {0, 24, 52, 62, 92, 104}, new float[] {1.2F, 1.2F, 1.2F, 1.2F, 1.2F, 1.2F})
                : table(age, new float[] {0, 28, 38}, new float[] {1.2F, 1.2F, 1.2F});
    }

    /** 0..1: DoF fades in over 6 ticks and out over the exhale. */
    public static float envelope(int ticks, float age) {
        int end = total(ticks);
        return smooth(age / 6) * (1 - smooth((age - (end - 14)) / 12));
    }

    /** 0..1 vignette, 0.3 s in and out around the inside beat (the overlay scales it to 25 %). */
    public static float vignette(int ticks, float age) {
        if (!isFull(ticks)) return 0;
        return smooth((age - 52) / 6) * (1 - smooth((age - 92) / 6));
    }


    // ---- the whole frame takes the pill's colour (refs: haze and light everywhere, a big peak flash with rays)
    /** Ticks after the core appears when it bursts (the renderer's BURST_AFTER). */
    public static final int BURST_AFTER = 10;

    public static int burstTick(int ticks) { return core(ticks) + BURST_AFTER; }

    /** 0..1 haze over the whole frame: it thickens from the melting to the core, gains at the peak and thins over the exhale. */
    public static float haze(int ticks, float age) {
        int from = melt(ticks) + 6, core = core(ticks), end = total(ticks);
        float rise = .55F * smooth((age - from) / Math.max(1, core - from)) + .4F * smooth((age - burstTick(ticks)) / 5);
        return rise * (1 - smooth((age - (end - 32)) / 32F));
    }

    /** 0..1 white flash of the peak: a one tick rise and a ten tick fall. */
    public static float flash(int ticks, float age) {
        float b = age - burstTick(ticks);
        return b < -1.5F ? 0 : smooth((b + 1.5F) / 1.5F) * (1 - smooth(b / 11F));
    }

    /** 0..1 amount of the rays that fill the frame: out with the flash, they last through the first part of the exhale. */
    public static float rays(int ticks, float age) {
        float b = age - burstTick(ticks);
        return b < 0 ? 0 : smooth(b / 3F) * (1 - smooth((b - 8) / 30F));
    }

    /** 0..1 strength of the wind bands: from the inside beat to the end. */
    public static float wind(int ticks, float age) {
        int from = melt(ticks) + 10, end = total(ticks);
        return smooth((age - from) / 20F) * (1 - smooth((age - (end - 20)) / 20F));
    }

    // ---- the inside beat (full scene only)
    public static final int ORBS = 4;
    public static final int ORB_TRAVEL = 10;

    /** Bands grow along their routes from tick 56 to 70. */
    public static float bandReach(float age) { return smooth((age - 56) / 14); }

    /** Fade of the bands: they dim over the first ticks of the core beat. */
    public static float bandAlpha(float age) { return 1 - smooth((age - 92) / 10); }

    /** Progress 0..1 of orb {@code i} along its route; 0 before it starts. */
    public static float orbRoute(int i, float age) {
        return smooth((age - (60 + 6 * i)) / ORB_TRAVEL);
    }

    /** 0..1: how far orb {@code i} has been pulled to the belly after the route. */
    public static float orbConverge(int ticks, int i, float age) {
        return smooth((age - (core(ticks) - 4 + i)) / 10);
    }

    // ---- the core
    /** Core size factor: grows to x1.5 as the orbs arrive, settles to x1.0, fades with the exhale. */
    public static float coreScale(int ticks, float age) {
        float a = age - core(ticks);
        if (a < 0) return 0;
        return smooth(a / 6) * (1.5F - .5F * smooth((a - 6) / 16));
    }

    /** Core brightness: full through the core beat, then a warm ember that is gone at the end. */
    public static float coreAlpha(int ticks, float age) {
        int e = start(ticks, Beat.EXHALE);
        return age < core(ticks) ? 0 : 1 - smooth((age - e) / (total(ticks) - e));
    }

    /** Sound of the finish plays here. */
    public static int finishSound(int ticks) { return core(ticks) + 4; }
}
