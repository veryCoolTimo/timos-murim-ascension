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
    // Hall-facing rigs; the release shares the reaction's first pose exactly.
    // Tuned by hand 7.11 after four codex rounds: the newcomer stands 5 blocks in front of the seated head
    // (SectHeadIntro), so a camera 6.3 blocks from the head at -9 degrees sits a little behind his shoulder.
    public static final List<Shot> SHOTS = List.of(
            new Shot(0, 70, false, track("establish", new ShotPose(-6, 2, 13, 1.55F, 0, 1), new ShotPose(-6, 2, 10, 1.55F, 0, 1))),
            new Shot(70, 130, false, track("shoulder", pose(-9, 6.3F, 1.15F, .8F), pose(-9, 5.7F, 1.15F, .8F))),
            new Shot(130, 230, false, track("head", pose(-28, 2.5F, 1.05F, .75F), pose(-28, 2.2F, 1.05F, .75F))),
            new Shot(230, 290, true, track("reaction", new ShotPose(28, -3, 2.3F, 1.55F, 0, .8F), new ShotPose(26, -3, 2.2F, 1.55F, 0, .8F))),
            new Shot(290, 320, true, track("release", new ShotPose(26, -3, 2.2F, 1.55F, 0, .8F), new ShotPose(180, 0, 4, 1.62F, 0, 1))));
    public static float smooth(float x) { x = Math.max(0, Math.min(1, x)); return x*x*(3-2*x); }
    public static float bars(float age) { return smooth(age / 8) * (1 - smooth((age - 310) / 8)); }
    public static float blur(float age) { return age < 70 ? 3 : age < 130 ? 5 : age < 230 ? 7 : age < 290 ? 6 : 3; }
    public static float envelope(float age) { return smooth(age / 10) * (1 - smooth((age - 308) / 10)); }
    public static int line(float age) { return age < 70 ? 1 : age < 130 ? 2 : age < 230 ? 3 : 4; }
    private HeadIntroPlan() { }
    public static Shot shot(float age) {
        return SHOTS.stream().filter(s -> age < s.until()).findFirst().orElse(SHOTS.getLast());
    }
}
