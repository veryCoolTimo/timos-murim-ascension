package io.github.verycooltimo.murim.showcase;

import io.github.verycooltimo.murim.cultivation.BtTimeline;
import io.github.verycooltimo.murim.cultivation.Realm;
import java.util.ArrayList;
import java.util.List;

/**
 * The shot list of {@code /murim dev showcase}: pure data, no game objects. Every shot names the existing scene it plays, the moment(s)
 * of its screenshot and how long it lasts. The timeline is a function of the hold time only, so the server runs it from one tick
 * counter and the tests can read it.
 *
 * <p>Order (author 14.11): the mentor, the head of the sect, the rank breakthroughs, the technique activations.
 * Times of day are golden hour with a few dusk shots (shaders look best there).
 */
public final class ShowcaseShots {

    public enum Kind { MENTOR_WIDE, MENTOR_DEMO, MENTOR_TALK, HALL_WIDE, HEAD_INTRO, BREAKTHROUGH, TECHNIQUE }

    /** A technique shot: the art whose stars are raised, the form that is cast, the gap between caster and target, the item in hand. */
    public record Tech(String art, String form, double gap, boolean sword) { }

    /**
     * @param own      the cutscene keeps its own camera (no far camera)
     * @param minTicks the shot lasts at least this long (a scene that must play to its end)
     * @param snaps    ticks (counted from the start of the shot) at which the screenshot is taken
     * @param snapTags names of the screenshots when a shot takes several (empty = the shot name)
     * @param dayTime  time of day when the shot starts
     * @param dist     camera distance from the focus point; {@code elev} degrees above it; {@code yawOff} degrees off the axis; {@code sweep}
     *                 degrees of orbit and {@code dolly} (final distance / distance) over the shot; {@code fov} scales the lens
     */
    public record Shot(String name, Kind kind, boolean own, int minTicks, int[] snaps, String[] snapTags, int dayTime,
                       float dist, float elev, float yawOff, float sweep, float dolly, float fov, int target, Tech tech,
                       String[] zones) {

        /** Whole length: the last screenshot plus the hold, but never less than the scene itself. */
        public int duration(int hold) {
            int last = snaps.length == 0 ? 0 : snaps[snaps.length - 1];
            return Math.max(minTicks, last + hold);
        }
    }

    /** Ticks the player is placed before a breakthrough starts (he must stand still on the ground). */
    public static final int BT_PREP = 14;
    /**
     * Ages of the rank-up scene (client ticks after the blow) at which a breakthrough is photographed; the client waits for its own
     * scene clock, so the frame is the scene's visual peak whatever the server and client lag. Chosen from a probe film (6/16/28/44): the burst and flames are fullest at the start, later the rank title covers the frame.
     */
    public static final int[] BT_PEAK_AGES = {8};
    /** A technique is cast this many ticks after the shot begins (the camera has settled). */
    public static final int CAST_AT = 30;
    /** Default hold after a screenshot: 8 s. */
    public static final int DEFAULT_HOLD = 160;
    /** Longest a shot waits for its people to appear (mentor, head) before it is skipped. */
    public static final int WAIT_MAX = 900;

    private ShowcaseShots() { }

    private static Shot scene(String name, Kind kind, boolean own, int min, int[] snaps, int day, float dist, float elev,
                              float yawOff, float sweep, float dolly, float fov) {
        return new Shot(name, kind, own, min, snaps, new String[0], day, dist, elev, yawOff, sweep, dolly, fov, 0, null, new String[0]);
    }

    private static Shot breakthrough(int target, int day, float dist, float elev, float yawOff, float sweep, float fov, String... zones) {
        int sceneTicks = Realm.breakthroughTicks(target);
        int blow = BT_PREP + Realm.WARNING_TICKS + sceneTicks;
        int snap = blow;
        int min = blow + BtTimeline.of(target).releaseTicks() + 20;
        String name = target == Realm.PEAK ? "breakthrough_peak" : "breakthrough_rank" + target;
        int[] snaps = new int[BT_PEAK_AGES.length];
        String[] tags = new String[BT_PEAK_AGES.length];
        for (int i = 0; i < snaps.length; i++) {
            snaps[i] = snap;
            tags[i] = "t" + BT_PEAK_AGES[i];
        }
        if (snaps.length == 1) {
            tags = new String[0];
        }
        return new Shot(name, Kind.BREAKTHROUGH, false, min, snaps, tags, day, dist, elev, yawOff, sweep,
                0.86F, fov, target, null, zones);
    }

    private static Shot technique(String name, String art, String form, double gap, boolean sword, int castToSnap, int day,
                                  float dist, float elev, float yawOff, float sweep, String... zones) {
        int snap = CAST_AT + castToSnap;
        return new Shot(name, Kind.TECHNIQUE, false, snap + 40, new int[] {snap}, new String[0], day, dist, elev, yawOff, sweep,
                0.88F, 0.56F, 0, new Tech(art, form, gap, sword), zones);
    }

    public static List<Shot> plan() {
        List<Shot> out = new ArrayList<>();
        // 1. The mentor: his ring demonstration, his talk with the cinematic dialogue camera.
        out.add(scene("mentor_demo", Kind.MENTOR_DEMO, false, 175, new int[] {118}, 400, 7.0F, 4.0F, 58.0F, 18.0F, 0.85F, 0.75F));
        out.add(scene("mentor_talk", Kind.MENTOR_TALK, true, 150, new int[] {100}, 700, 0, 0, 0, 0, 1, 1));
        // 2. The head of the sect: the hall seen from its door, the introduction cutscene (its own camera), three frames, then the hall seen again with the head at his seat.
        out.add(new Shot("head_intro", Kind.HEAD_INTRO, true, 335, new int[] {55, 175, 255}, new String[] {"a", "b", "c"}, 9000,
                0, 0, 0, 0, 1, 1, 0, null, new String[0]));
        out.add(scene("hall_wide", Kind.HALL_WIDE, false, 120, new int[] {60}, 9000, 12.0F, 6.0F, 0.0F, -14.0F, 0.9F, 0.8F));
        // 3. Four rank breakthroughs, each with its own look and its own part of the sect; the last one at dusk.
        // The camera yaw is relative to the way the player faces: 180 = straight at his face, 180 +- 35 = front three-quarter.
        out.add(breakthrough(1, 11000, 6.0F, 4.0F, 215.0F, -14.0F, 0.55F, "training", "bell"));
        out.add(breakthrough(2, 11600, 6.5F, 3.0F, -30.0F, 14.0F, 0.55F, "bell", "treasury"));
        out.add(breakthrough(3, 12400, 7.5F, 3.0F, 145.0F, 14.0F, 0.55F, "treasury", "poles"));
        out.add(breakthrough(Realm.PEAK, 13000, 9.0F, 2.0F, 210.0F, -14.0F, 0.55F, "training", "sect_gate"));
        // 4. Technique activations at the full star tier, each cast at a training dummy, in other places of the sect.
        out.add(technique("six_chain", "six_harmonies", "six_harmonies_chain", 3.2, true, 26, 10800, 9.5F, 3.0F, 225.0F, 14.0F, "poles", "mentor"));
        out.add(technique("six_fear", "six_harmonies", "six_harmonies_fear", 4.6, true, 26, 11300, 9.5F, 3.0F, 135.0F, -14.0F, "arch", "sect_gate"));
        out.add(technique("plum7_shower", "seven_plum", "seven_plum_shower", 4.0, true, 40, 12300, 9.5F, 3.0F, 25.0F, 14.0F, "grove", "poles"));
        out.add(technique("plum7_explosion", "seven_plum", "seven_plum_explosion", 3.4, true, 44, 11000, 9.5F, 3.0F, 230.0F, -14.0F, "pav_east", "treasury"));
        out.add(technique("plum24_rainfall", "twenty_four_plum", "twenty_four_plum_rainfall", 4.2, true, 60, 12600, 9.5F, 3.0F, 130.0F, 14.0F, "grove", "bell"));
        out.add(technique("plum24_river", "twenty_four_plum", "twenty_four_plum_river", 4.0, true, 60, 11800, 9.5F, 3.0F, 90.0F, -14.0F, "sect_gate", "pav_east"));
        out.add(technique("bamboo_grove", "bamboo_leaf_hand", "bamboo_rustling_grove", 4.0, false, 40, 10600, 9.5F, 3.0F, 220.0F, 14.0F, "dining", "mentor"));
        return List.copyOf(out);
    }

    /** Total length of the reel in ticks, without the waits for people. */
    public static int totalTicks(int hold) {
        int sum = 0;
        for (Shot s : plan()) sum += s.duration(hold);
        return sum;
    }

    /** Number of screenshots the whole reel takes. */
    public static int snapCount() {
        int n = 0;
        for (Shot s : plan()) n += s.snaps().length;
        return n;
    }
}
