package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.client.camera.ShotPose;
import io.github.verycooltimo.murim.client.camera.ShotTrack;
import java.util.List;

/** Eased shot list, using the breakthrough rig's pose data. Times are server ticks. */
public final class HeadIntroPlan {
    public record Shot(int from, int until, boolean player, ShotTrack track) { }
    private static ShotTrack track(String name, ShotPose from, ShotPose to) {
        return new ShotTrack(name, 0, ShotTrack.Ease.IN_OUT, 0, false,
                List.of(new ShotTrack.Key(0, from), new ShotTrack.Key(1, to)));
    }
    private static ShotPose pose(float azimuth, float distance, float focus, float fov) {
        return new ShotPose(azimuth, 0, distance, focus, 0, fov);
    }
    // Hall-facing rigs; the release shares the reaction's first pose exactly.
    // Tuned by hand 7.11 after four codex rounds: the newcomer stands 5 blocks in front of the seated head
    // (SectHeadIntro), so a camera 6.3 blocks from the head sits a little behind his shoulder; +9 degrees is the side away from the mentor (he stands 1.5 blocks to the other side of the newcomer, and -9 put his back in the foreground).
    // Reaction is a two-shot (9.11): the mentor stands 1.5 blocks beside the newcomer; at 2.3 blocks and lens .8 he spanned 30-53 degrees
    // off the axis against a half-width of 43 and was cut by the frame edge (real-path film 680-706); at 3.4 and 1.05 his far corner is 34 of 53 degrees off the axis (19 margin; 12 with the lens pulled 15% in by a wall).
    // The reaction also holds him in focus (focusRange 1.8, blur 8) and SectHeadIntro turns him to the newcomer at tick 230 (film 9.11 leg23: profile, away, blurred).
    // Establishing lens stays inside the hall: the grand doors are 11.5 blocks from the seat, and a lens at 13 sat in their swing
    // (door leaf in front of it, or the camera held for the whole scene, in some runs).
    public static final List<Shot> SHOTS = List.of(
            new Shot(0, 70, false, track("establish", new ShotPose(-6, 2, 10, 1.55F, 0, 1), new ShotPose(-6, 2, 8.5F, 1.55F, 0, 1))),
            new Shot(70, 130, false, track("shoulder", pose(9, 6.3F, 1.15F, .8F), pose(9, 5.7F, 1.15F, .8F))),
            new Shot(130, 230, false, track("head", pose(-28, 2.5F, 1.15F, .75F), pose(-28, 2.2F, 1.15F, .75F))),
            new Shot(230, 290, true, track("reaction", new ShotPose(28, -3, 3.4F, 1.55F, 0, 1.05F), new ShotPose(20, -1, 3.3F, 1.55F, 0, 1.05F))),
            new Shot(290, 320, true, releaseTrack()));
    // Release glide leaves through the open side (azimuth 20 -> -90 -> -180), not past the right-hand pillar row.
    private static ShotTrack releaseTrack() {
        return new ShotTrack("release", 0, ShotTrack.Ease.IN_OUT, 0, false, List.of(
                new ShotTrack.Key(0, new ShotPose(20, -1, 3.3F, 1.55F, 0, 1.05F)),
                new ShotTrack.Key(.5F, new ShotPose(-90, -1, 3.5F, 1.58F, 0, .97F)),
                new ShotTrack.Key(1, new ShotPose(-180, 0, 4, 1.62F, 0, 1))));
    }
    /** Degrees the camera aims below the focus point in head shots; puts the seated eyes on the upper-third line. */
    public static float framingPitch(float age) { return age < 70 ? -3 : age < 130 ? 9 : age < 230 ? 8.4F : -3; }
    /** Blur radius in px at a 1080-line frame; the renderer scales it by frame height. */
    public static final float REFERENCE_HEIGHT = 1080;
    public static float focusRange(float age) { return age < 70 ? 1.5F : age < 130 ? .5F : age < 230 ? .4F : age < 290 ? 1.8F : .4F; }
    public static float smooth(float x) { x = Math.max(0, Math.min(1, x)); return x*x*(3-2*x); }
    public static float bars(float age) { return smooth(age / 8) * (1 - smooth((age - 310) / 8)); }
    public static float blur(float age) { return age < 70 ? 5 : age < 130 ? 14 : age < 230 ? 14 : age < 290 ? 8 : 5; }
    public static float envelope(float age) { return smooth(age / 10) * (1 - smooth((age - 308) / 10)); }
    public static int line(float age) { return age < 70 ? 1 : age < 130 ? 2 : age < 230 ? 3 : 4; }
    private HeadIntroPlan() { }
    public static Shot shot(float age) {
        return SHOTS.stream().filter(s -> age < s.until()).findFirst().orElse(SHOTS.getLast());
    }
}
