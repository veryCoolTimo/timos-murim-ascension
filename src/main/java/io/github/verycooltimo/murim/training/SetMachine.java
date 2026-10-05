package io.github.verycooltimo.murim.training;

import java.util.ArrayList;
import java.util.List;

/**
 * Sets in place — squats, horse stance, push-ups — read from EXISTING keys only (author: few keybinds, reuse by
 * context; docs/design/27-body-training.md §2). The server sees the crouch key ({@code ServerboundPlayerCommandPacket}
 * PRESS/RELEASE_SHIFT_KEY → {@code isShiftKeyDown}) and the view pitch, so no new payload is needed:
 *
 * <ul>
 *   <li><b>squats</b> — two crouch taps standing still, then one tap per rep, in rhythm;</li>
 *   <li><b>horse stance</b> — during squats, stay down (crouch held {@code HORSE_HOLD} ticks); let go — squats again;</li>
 *   <li><b>push-ups</b> — look at the ground ({@code PUSHUP_PITCH}) and tap crouch: into the plank; each tap a push-up;
 *       the weight slab in hand at that moment goes on the back (weighted); look up or move — stand up.</li>
 * </ul>
 * Hands must be empty (or hold the weight slab): a builder sneaking at an edge with blocks in hand never starts a set.
 *
 * <p>Pure: no Minecraft types, one {@link #step} per tick; {@code SetMachineTest} drives it with plain inputs.
 */
public final class SetMachine {

    /** What the hands hold. */
    public enum Hands { EMPTY, SLAB, OTHER }

    /** One tick of input. {@code blocked}: meditation, technique, riding, water, flying, dialogue. */
    public record Input(long tick, boolean crouch, float pitch, boolean still, boolean onGround, Hands hands,
                        boolean blocked, boolean exhausted) {
    }

    public enum Kind { START, REP, RUSHED, HOLD, SWITCH, END, EXHAUSTED }

    public enum EndReason { IDLE, MOVED, SPENT, STOOD_UP }

    /**
     * @param exercise the set's exercise after the event
     * @param quality  REP/HOLD quality ({@code TrainingBalance.QUALITY_*})
     * @param reason   END only
     */
    public record Event(Kind kind, Exercise exercise, double quality, EndReason reason) {
        static Event of(Kind k, Exercise e) {
            return new Event(k, e, 0.0D, null);
        }
    }

    private Exercise exercise;
    private long origin;
    private long lastRep = Long.MIN_VALUE / 2;
    private long lastIdlePress = Long.MIN_VALUE / 2;
    private long downSince = -1;
    private long holdSince;
    private long lookUpSince = -1;
    private boolean crouchPrev;
    private double stamina = 1.0D;
    private int reps;
    private int good;
    /** Seconds in the current horse stance (its own count; the squats around it keep theirs). */
    private int stanceSec;
    private long lastExhaustedNote = Long.MIN_VALUE / 2;

    public Exercise exercise() {
        return exercise;
    }

    public boolean active() {
        return exercise != null;
    }

    /** Beat origin of the set (game tick of beat 0). */
    public long origin() {
        return origin;
    }

    public double stamina() {
        return stamina;
    }

    /** Reps in this set, or seconds in the current horse stance. */
    public int reps() {
        return exercise == Exercise.HORSE_STANCE ? stanceSec : reps;
    }

    /** Reps on the beat (stance: seconds held). */
    public int good() {
        return exercise == Exercise.HORSE_STANCE ? stanceSec : good;
    }

    /** Ends the set from outside (carry stone picked up, logout). */
    public List<Event> stop(EndReason reason) {
        List<Event> out = new ArrayList<>();
        if (exercise != null) {
            out.add(new Event(Kind.END, exercise, 0.0D, reason));
            exercise = null;
        }
        return out;
    }

    public List<Event> step(Input in) {
        List<Event> out = new ArrayList<>();
        boolean press = in.crouch() && !crouchPrev;
        crouchPrev = in.crouch();
        if (in.crouch()) {
            if (downSince < 0) {
                downSince = in.tick();
            }
        } else {
            downSince = -1;
        }
        if (exercise == null) {
            idle(in, press, out);
            return out;
        }
        if (in.blocked() || !in.onGround() || !in.still()) {
            end(EndReason.MOVED, out);
            return out;
        }
        if (exercise.pushup()) {
            pushups(in, press, out);
        } else if (exercise == Exercise.HORSE_STANCE) {
            stance(in, out);
        } else {
            squats(in, press, out);
        }
        return out;
    }

    private void idle(Input in, boolean press, List<Event> out) {
        stamina = Math.min(1.0D, stamina + TrainingBalance.STAMINA_REGEN);
        if (in.blocked() || !in.onGround() || !in.still() || in.hands() == Hands.OTHER) {
            lastIdlePress = Long.MIN_VALUE / 2;
            return;
        }
        if (!press) {
            return;
        }
        if (in.exhausted()) {
            if (in.tick() - lastExhaustedNote > 100) {
                lastExhaustedNote = in.tick();
                out.add(Event.of(Kind.EXHAUSTED, null));
            }
            return;
        }
        if (in.pitch() >= TrainingBalance.PUSHUP_PITCH) {
            begin(in.hands() == Hands.SLAB ? Exercise.PUSHUP_WEIGHTED : Exercise.PUSHUP, in.tick(), out);
            return;
        }
        if (in.hands() == Hands.EMPTY && in.tick() - lastIdlePress <= TrainingBalance.START_GAP) {
            begin(Exercise.SQUAT, in.tick(), out);
            rep(in.tick(), TrainingBalance.QUALITY_GOOD, out);
            return;
        }
        lastIdlePress = in.tick();
    }

    private void begin(Exercise e, long tick, List<Event> out) {
        exercise = e;
        origin = tick;
        reps = 0;
        good = 0;
        lastRep = tick;
        lookUpSince = -1;
        out.add(Event.of(Kind.START, e));
    }

    private void squats(Input in, boolean press, List<Event> out) {
        int beat = exercise.beat();
        if (press) {
            if (in.tick() - lastRep < beat / 2) {
                // Bobbing faster than half a beat is not a squat: it only costs breath.
                stamina -= exercise.stamina();
                out.add(Event.of(Kind.RUSHED, exercise));
            } else {
                rep(in.tick(), judge(origin, beat, in.tick()), out);
            }
        } else {
            stamina = Math.min(1.0D, stamina + TrainingBalance.STAMINA_REGEN);
        }
        if (stamina <= 0.0D) {
            end(EndReason.SPENT, out);
            return;
        }
        if (in.crouch() && downSince >= 0 && in.tick() - downSince >= TrainingBalance.HORSE_HOLD) {
            exercise = Exercise.HORSE_STANCE;
            holdSince = in.tick();
            stanceSec = 0;
            out.add(Event.of(Kind.SWITCH, exercise));
            return;
        }
        if (!in.crouch() && in.tick() - lastRep > (long) TrainingBalance.IDLE_BEATS * beat) {
            end(EndReason.IDLE, out);
        }
    }

    private void stance(Input in, List<Event> out) {
        if (!in.crouch()) {
            // Up from the stance: back to squats, the beat starts here.
            exercise = Exercise.SQUAT;
            origin = in.tick();
            lastRep = in.tick();
            out.add(Event.of(Kind.SWITCH, exercise));
            return;
        }
        stamina -= Exercise.HORSE_STANCE.stamina() / 20.0D;
        long held = in.tick() - holdSince;
        if (held > 0 && held % 20 == 0) {
            stanceSec++;
            out.add(new Event(Kind.HOLD, exercise, TrainingBalance.QUALITY_GOOD, null));
        }
        if (stamina <= 0.0D) {
            end(EndReason.SPENT, out);
        }
    }

    private void pushups(Input in, boolean press, List<Event> out) {
        if (in.pitch() < TrainingBalance.PUSHUP_EXIT_PITCH) {
            if (lookUpSince < 0) {
                lookUpSince = in.tick();
            } else if (in.tick() - lookUpSince >= 10) {
                end(EndReason.STOOD_UP, out);
                return;
            }
        } else {
            lookUpSince = -1;
        }
        int beat = exercise.beat();
        if (press) {
            if (in.tick() - lastRep < beat / 2 && reps > 0) {
                stamina -= exercise.stamina();
                out.add(Event.of(Kind.RUSHED, exercise));
            } else {
                rep(in.tick(), judge(origin, beat, in.tick()), out);
            }
        } else {
            stamina = Math.min(1.0D, stamina + TrainingBalance.STAMINA_REGEN);
        }
        if (stamina <= 0.0D) {
            end(EndReason.SPENT, out);
            return;
        }
        if (in.tick() - lastRep > (long) (TrainingBalance.IDLE_BEATS + 1) * beat) {
            end(EndReason.IDLE, out);
        }
    }

    private void rep(long tick, double quality, List<Event> out) {
        reps++;
        if (quality >= TrainingBalance.QUALITY_GOOD) {
            good++;
        }
        lastRep = tick;
        stamina -= exercise.stamina() * (quality <= TrainingBalance.QUALITY_OFF ? TrainingBalance.OFF_BEAT_COST : 1.0D);
        out.add(new Event(Kind.REP, exercise, quality, null));
    }

    private void end(EndReason reason, List<Event> out) {
        out.add(new Event(Kind.END, exercise, 0.0D, reason));
        exercise = null;
        lastIdlePress = Long.MIN_VALUE / 2;
    }

    /** Rep quality by distance to the nearest beat. */
    public static double judge(long origin, int beat, long tick) {
        long off = Math.floorMod(tick - origin, (long) beat);
        long d = Math.min(off, beat - off);
        if (d <= TrainingBalance.BEAT_GOOD) {
            return TrainingBalance.QUALITY_GOOD;
        }
        return d <= TrainingBalance.BEAT_FAIR ? TrainingBalance.QUALITY_FAIR : TrainingBalance.QUALITY_OFF;
    }
}
