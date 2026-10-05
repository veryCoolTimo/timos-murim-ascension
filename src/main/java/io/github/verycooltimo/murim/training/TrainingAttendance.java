package io.github.verycooltimo.murim.training;

import io.github.verycooltimo.murim.sect.SectAttendance;
import net.minecraft.server.level.ServerPlayer;

/**
 * Training reports a finished set or run to the sect's attendance ({@link SectAttendance}): the peak climb counts as the
 * day's climb, any other exercise as a lesson.
 */
public final class TrainingAttendance {

    private TrainingAttendance() {
    }

    public static void record(ServerPlayer player, Exercise exercise) {
        SectAttendance.record(player, exercise == Exercise.PEAK_CLIMB ? "climb" : "training." + exercise.id());
    }
}
