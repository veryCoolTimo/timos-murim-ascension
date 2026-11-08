package io.github.verycooltimo.murim.training;

import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;

import java.util.List;

/**
 * Hook for the sect behaviour task: disciples drill in the morning (canon: Gyeong Pil drives the children at dawn,
 * ch. 16, 43; push-ups with shouts, ch. 66). The player does NOT do these on a Shift tap any more (25.10): his body grows
 * from ordinary play and from the explicit drills ({@link Exercise}); the squats and push-ups stay as the disciples' own
 * scenery. This class only gives poses and a fair rotation; WHEN and WHERE a disciple trains is the schedule's business
 * ({@code sect/SectSchedule}, not touched here).
 *
 * <p>Use from the schedule goal:
 * <pre>
 *   SectPose p = DiscipleTraining.morning(memberIndex, day);
 *   npc.setPose(p);        // loops by itself
 * </pre>
 * The carry pose ({@link SectPose#CARRY_STONE}) is upper-body, so it plays while the disciple walks up the stair.
 */
public final class DiscipleTraining {

    /** Poses a disciple holds in place, in rotation. */
    public static final List<SectPose> MORNING = List.of(SectPose.SQUAT, SectPose.PUSHUP, SectPose.HORSE_STANCE,
            SectPose.PUSHUP_WEIGHTED, SectPose.CARRY_STONE);

    private DiscipleTraining() {
    }

    /**
     * The pose of a disciple on a given day: neighbours in the roster do different ones, and everyone moves to the next
     * one each day (a row of squats next to a row of push-ups reads as a training yard).
     */
    public static SectPose morning(int memberIndex, long day) {
        return MORNING.get(Math.floorMod(memberIndex + day, MORNING.size()));
    }

    /** Convenience: pose for this disciple today (member index from the roster, −1 → squats). */
    public static SectPose morningPose(SectDisciple npc, long day) {
        int index = npc.member().map(m -> m.index()).orElse(0);
        return morning(index, day);
    }
}
