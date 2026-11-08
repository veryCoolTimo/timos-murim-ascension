package io.github.verycooltimo.murim.training;

import java.util.Locale;

/**
 * A KIND of work that tempers the body (docs/design/27-body-training.md §2). Each kind has its own daily limit: it pays at
 * full rate up to {@code full} points a Minecraft day, and beyond that less and less, never more than {@code full + extra}
 * in all. So a day of only jumping, only mob farming or only chopping cannot carry the ladder; a varied day can.
 */
public enum BodySource {
    /** Walking, sprinting, crouching, swimming, climbing. */
    LEGS(TrainingBalance.LEGS_FULL, TrainingBalance.LEGS_EXTRA),
    /** Jumps (tiny: a held key is the cheapest AFK trick). */
    JUMP(TrainingBalance.JUMP_FULL, TrainingBalance.JUMP_EXTRA),
    /** Mining, felling trees, digging. */
    LABOUR(TrainingBalance.LABOUR_FULL, TrainingBalance.LABOUR_EXTRA),
    /** Mobs killed, melee damage, spars. */
    FIGHT(TrainingBalance.FIGHT_FULL, TrainingBalance.FIGHT_EXTRA),
    /** The explicit sect drills: stone uphill, South Peak climb, trail sprint. */
    DRILL(TrainingBalance.DRILL_FULL, TrainingBalance.DRILL_EXTRA);

    private final double full;
    private final double extra;

    BodySource(double full, double extra) {
        this.full = full;
        this.extra = extra;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public double full() {
        return full;
    }

    public double extra() {
        return extra;
    }

    /** Most points this kind of work can give in one day. */
    public double dayMax() {
        return full + extra;
    }

    /**
     * Points a day's raw work of this kind is worth: the raw value up to {@code full}, then an exponential approach to
     * {@code full + extra}. Continuous, increasing, slope 1 at {@code full} and falling from there.
     */
    public double dayTotal(double raw) {
        if (raw <= full) {
            return Math.max(0.0D, raw);
        }
        return full + extra * (1.0D - Math.exp(-(raw - full) / extra));
    }

    public static BodySource byId(String id) {
        for (BodySource s : values()) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }
}
