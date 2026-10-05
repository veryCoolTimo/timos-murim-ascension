package io.github.verycooltimo.murim.training;

import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;

import java.util.List;

/**
 * Hook for the sect behaviour task: disciples do the same exercises in the morning (canon: Un Geom drives the
 * children at dawn, ch. 16, 43; push-ups with shouts, ch. 66). This class only gives poses and a fair rotation;
 * WHEN and WHERE a disciple trains is the schedule's business ({@code sect/SectSchedule}, not touched here).
 *
 * <p>Use from the schedule goal:
 * <pre>
 *   Exercise e = DiscipleTraining.morning(memberIndex, day);
 *   npc.setPose(DiscipleTraining.pose(e));        // loops by itself; route exercises return null (just walk/climb)
 * </pre>
 * The South Peak climb is a walk along {@code MountHuaSites.climbSites} ({@code climb_1..16}); the carry pose
 * ({@link SectPose#CARRY_STONE}) is upper-body, so it plays while the disciple walks up the stair.
 */
public final class DiscipleTraining {

    /** Exercises a disciple does in place, in rotation. */
    public static final List<Exercise> MORNING = List.of(Exercise.SQUAT, Exercise.PUSHUP, Exercise.HORSE_STANCE,
            Exercise.PUSHUP_WEIGHTED, Exercise.CARRY_STONE);

    private DiscipleTraining() {
    }

    /** Pose of an exercise, or null for route runs (the disciple walks; no own clip). */
    public static SectPose pose(Exercise e) {
        if (e == null) {
            return SectPose.NONE;
        }
        return switch (e) {
            case SQUAT -> SectPose.SQUAT;
            case PUSHUP -> SectPose.PUSHUP;
            case PUSHUP_WEIGHTED -> SectPose.PUSHUP_WEIGHTED;
            case HORSE_STANCE -> SectPose.HORSE_STANCE;
            case CARRY_STONE -> SectPose.CARRY_STONE;
            case PEAK_CLIMB, TRAIL_SPRINT -> null;
        };
    }

    /**
     * The exercise of a disciple on a given day: neighbours in the roster do different ones, and everyone moves to
     * the next exercise each day (a row of squats next to a row of push-ups reads as a training yard).
     */
    public static Exercise morning(int memberIndex, long day) {
        return MORNING.get(Math.floorMod(memberIndex + day, MORNING.size()));
    }

    /** Convenience: pose for this disciple today (member index from the roster, −1 → squats). */
    public static SectPose morningPose(SectDisciple npc, long day) {
        int index = npc.member().map(m -> m.index()).orElse(0);
        return pose(morning(index, day));
    }
}
