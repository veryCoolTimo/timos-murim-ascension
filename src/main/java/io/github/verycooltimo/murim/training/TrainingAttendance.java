package io.github.verycooltimo.murim.training;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;

/**
 * Adapter to the sect members' attendance ({@code sect/SectAttendance.record(player, activity)}, owned by the sect
 * behaviour task, not merged yet on 05.10). Training reports a finished set or run at the sect here; when the class
 * appears, the call goes through by itself; until then it is a log line.
 *
 * <p>Activity ids: {@code training.<exercise id>} (e.g. {@code training.squat}, {@code training.peak_climb}). If the
 * second parameter of {@code record} turns out to be an enum, the adapter passes {@code TRAINING} when it exists.
 * TODO(merge with sect members): replace this reflection with the direct call once {@code SectAttendance} is on the branch.
 */
public final class TrainingAttendance {

    private static final String CLASS = "io.github.verycooltimo.murim.sect.SectAttendance";
    /** Resolved once per JVM: a code lookup, not game state (rule 03 forbids static GAME state). */
    private static volatile Method record;
    private static volatile boolean looked;

    private TrainingAttendance() {
    }

    public static void record(ServerPlayer player, Exercise exercise) {
        Method m = method();
        String activity = "training." + exercise.id();
        if (m == null) {
            MurimMod.LOGGER.debug("Training attendance (no SectAttendance yet): {} {}", player.getName().getString(), activity);
            return;
        }
        try {
            Class<?> type = m.getParameterTypes()[1];
            Object arg = activity;
            if (type.isEnum()) {
                arg = null;
                for (Object c : type.getEnumConstants()) {
                    if (((Enum<?>) c).name().equals("TRAINING")) {
                        arg = c;
                    }
                }
                if (arg == null) {
                    return;
                }
            }
            m.invoke(null, player, arg);
        } catch (ReflectiveOperationException | RuntimeException e) {
            MurimMod.LOGGER.warn("SectAttendance.record failed for {}", activity, e);
        }
    }

    private static Method method() {
        if (!looked) {
            looked = true;
            try {
                for (Method m : Class.forName(CLASS).getMethods()) {
                    if (m.getName().equals("record") && m.getParameterCount() == 2
                            && m.getParameterTypes()[0].isAssignableFrom(ServerPlayer.class)
                            && (m.getParameterTypes()[1] == String.class || m.getParameterTypes()[1].isEnum())) {
                        record = m;
                    }
                }
            } catch (ClassNotFoundException e) {
                record = null;
            }
        }
        return record;
    }
}
