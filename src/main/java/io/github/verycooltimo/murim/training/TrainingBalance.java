package io.github.verycooltimo.murim.training;

/**
 * Every tunable of body training in one place (docs/design/27-body-training.md §5). The balance pass changes
 * numbers here and nowhere else; {@link BodyRules} and the services only read them.
 *
 * <p>Scale: body level 0..{@link #MAX_LEVEL}. The whole ladder gives less than one cultivation rank
 * (a rank gives +4 health, {@code Realm.HEALTH_PER_RANK}): training is the slow physical road, qi the fast one.
 */
public final class TrainingBalance {

    // ------------------------------------------------------------------ levels

    /** Highest body level. */
    public static final int MAX_LEVEL = 10;
    /** Points for level L: {@code LEVEL_BASE × L^LEVEL_EXP} — L1 40, L2 130, L5 616, L10 2004. */
    public static final double LEVEL_BASE = 40.0D;
    public static final double LEVEL_EXP = 1.7D;

    // ------------------------------------------------------------------ gains

    /**
     * Daily diminishing returns: a gain is multiplied by {@code 1 / (1 + today / DAILY_SOFT)}, where {@code today}
     * is what was already gained this Minecraft day. A long session (≈ 200 squats) gives ≈ 130 points; the next
     * day starts fresh. Canon: a month of daily drills makes legs strong (ch. 45, 66), not one marathon.
     */
    public static final double DAILY_SOFT = 60.0D;
    /** At the sect (sect land or the South Peak climb) — with the others, under the mentor's eye. */
    public static final double SECT_BONUS = 1.25D;
    /** Rep quality: on the beat, near it, off it. */
    public static final double QUALITY_GOOD = 1.0D;
    public static final double QUALITY_FAIR = 0.7D;
    public static final double QUALITY_OFF = 0.35D;
    /** Beat window, ticks from the beat: good / fair. */
    public static final int BEAT_GOOD = 3;
    public static final int BEAT_FAIR = 6;

    // ------------------------------------------------------------------ fatigue (long, per day) and stamina (short, per set)

    /** Fatigue (0..1) slows gains from this point on, down to {@link #FATIGUE_FLOOR} of a fresh gain at 1. */
    public static final double FATIGUE_SOFT = 0.6D;
    public static final double FATIGUE_FLOOR = 0.15D;
    /** Above this a new set does not start: «the body is at its limit». */
    public static final double FATIGUE_STOP = 0.95D;
    /** Natural recovery per tick (≈ 0.036 per minute, a full bar in ≈ 28 minutes); at the sect × {@link #SECT_RECOVERY}. */
    public static final double RECOVERY_PER_TICK = 0.00003D;
    public static final double SECT_RECOVERY = 1.5D;
    /** A meal: fatigue − nutrition × this (a bread, 5 → 0.2); at the sect dining hall twice as much. */
    public static final double MEAL_PER_NUTRITION = 0.04D;
    public static final double SECT_MEAL = 2.0D;
    /** A night's sleep: at the sect fatigue goes to zero, elsewhere it is multiplied by this. */
    public static final double SLEEP_ELSEWHERE = 0.4D;
    /** Set stamina regen per tick between reps (a full bar in 12.5 s). */
    public static final double STAMINA_REGEN = 0.004D;
    /** An off-beat rep costs this much more stamina. */
    public static final double OFF_BEAT_COST = 1.6D;
    /** Hunger: food exhaustion per unit of training (a rep, a stance second, a block carried). */
    public static final float EXHAUSTION_PER_UNIT = 0.08F;

    // ------------------------------------------------------------------ input (existing keys only)

    /** Looking down at least this far (degrees) and tapping crouch — push-ups. */
    public static final float PUSHUP_PITCH = 50.0F;
    /** Looking up past this ends the push-up set. */
    public static final float PUSHUP_EXIT_PITCH = 15.0F;
    /** Two crouch taps within this many ticks start squats. */
    public static final int START_GAP = 30;
    /** Crouch held this long at the bottom of a squat — horse stance. */
    public static final int HORSE_HOLD = 20;
    /** A set ends after this many beats without a rep. */
    public static final int IDLE_BEATS = 3;

    // ------------------------------------------------------------------ carrying and routes

    /** Carry stone: movement speed −{@code CARRY_SLOW}, minus {@link #CARRY_SLOW_PER_LEVEL} per body level. */
    public static final double CARRY_SLOW = 0.35D;
    public static final double CARRY_SLOW_PER_LEVEL = 0.025D;
    /** Climb run: falling this far below the last ledge sends you back to the last rest ledge. */
    public static final double CLIMB_FALL = 3.5D;
    /** Runs end by themselves after this long, ticks (10 and 15 minutes). */
    public static final int CLIMB_TIMEOUT = 12000;
    public static final int TRAIL_TIMEOUT = 18000;
    /** Leaving the route this far (blocks) abandons the run. */
    public static final double CLIMB_ABANDON = 40.0D;
    public static final double TRAIL_ABANDON = 60.0D;
    /** A new best time adds this share of the run's points. */
    public static final double BEST_BONUS = 0.5D;
    /** Trail sprint: below this share of sprinting ticks the run counts at its share. */
    public static final double TRAIL_SPRINT_SHARE = 0.6D;

    // ------------------------------------------------------------------ effects per body level

    /** Max health +0.4 per level (+4 = two hearts at level 10, one rank's worth). */
    public static final double HEALTH_PER_LEVEL = 0.4D;
    /** Knockback resistance +0.02 per level (0.2 at level 10). */
    public static final double KNOCKBACK_PER_LEVEL = 0.02D;
    /** Footwork run length +3 % per level. */
    public static final double FOOTWORK_PER_LEVEL = 0.03D;

    // ------------------------------------------------------------------ breakthrough

    /**
     * Body level a breakthrough INTO rank r needs (index = target rank 0..4: none, third, second, first, peak).
     * All zero: body training does not gate breakthroughs until the balance doc says so (task 05.10: «only if
     * the balance doc says so»). Read by {@link BodyRules#breakthroughReady}.
     */
    public static final int[] BREAKTHROUGH_BODY_LEVEL = {0, 0, 0, 0, 0};

    private TrainingBalance() {
    }
}
