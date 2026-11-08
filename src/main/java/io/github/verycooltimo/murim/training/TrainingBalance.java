package io.github.verycooltimo.murim.training;

/**
 * Every tunable of the body in one place (docs/design/27-body-training.md §2-§4). The balance pass changes numbers
 * here and nowhere else; {@link BodyRules}, {@link BodySource}, {@link BodyProbe} and the services only read them.
 *
 * <p>Scale: body level 0..{@link #MAX_LEVEL}. The whole ladder gives less than one cultivation rank
 * (a rank gives +4 health, {@code Realm.HEALTH_PER_RANK}): the body is the slow physical road, qi the fast one.
 *
 * <p>The body grows from ORDINARY play (author 24.10: «тело можно тренировать обычными майнкрафт-активностями»):
 * the server reads the vanilla statistics every {@link #SCAN_INTERVAL} ticks and turns the change into points
 * ({@link BodyProbe} rates), each KIND of work ({@link BodySource}) paying at full rate up to a daily limit and less
 * beyond it. Shift never starts anything.
 */
public final class TrainingBalance {

    // ------------------------------------------------------------------ levels

    /** Highest body level. */
    public static final int MAX_LEVEL = 10;
    /** Points for level L: {@code LEVEL_BASE × L^LEVEL_EXP} — L1 40, L2 130, L4 422, L6 841, L9 1676, L10 2004. */
    public static final double LEVEL_BASE = 40.0D;
    public static final double LEVEL_EXP = 1.7D;

    // ------------------------------------------------------------------ reading the vanilla statistics

    /** Ticks between two looks at a player's statistics (5 s); each player is offset by the entity id. */
    public static final int SCAN_INTERVAL = 100;

    // Legs: points per kilometre (the statistics count centimetres). Elytra, boats, minecarts and horses have their
    // own statistics and pay nothing: the legs have to do the work.
    public static final double WALK_PER_KM = 8.0D;
    public static final double SPRINT_PER_KM = 12.0D;
    public static final double CROUCH_PER_KM = 4.0D;
    /** Swimming and walking in water. */
    public static final double SWIM_PER_KM = 15.0D;
    /** Climbing a ladder or a vine counts only upward metres. */
    public static final double CLIMB_PER_KM = 25.0D;
    /** Per jump. A held jump key is the cheapest AFK trick there is, so the daily limit of this source is tiny. */
    public static final double JUMP_EACH = 0.015D;

    // Labour: blocks MINED (harvested with the right tool), by what they are.
    /** Anything tagged {@code c:ores}. */
    public static final double MINE_ORE = 0.25D;
    /** Anything tagged {@code minecraft:logs}: felling trees. */
    public static final double MINE_LOG = 0.4D;
    /** Everything else that needs a pickaxe: stone, deepslate, bricks. */
    public static final double MINE_STONE = 0.05D;
    /** Everything else that needs a shovel: dirt, sand, gravel, clay. */
    public static final double MINE_DIG = 0.015D;

    // Fight.
    /** Per mob killed (the credit is the player's, so a trap that kills for him pays too: the limit below). */
    public static final double KILL_EACH = 0.8D;
    /** Per 100 health of melee damage dealt (the statistic counts tenths of a health point). */
    public static final double DAMAGE_PER_100 = 1.5D;
    /** A finished spar with a disciple. */
    public static final double SPAR = 3.0D;

    // ------------------------------------------------------------------ daily limits (per Minecraft day = 20 minutes)

    // For each kind of work: points a day at full rate, and the most it can add beyond that. Between the two the
    // rate falls off exponentially, so a day of any one kind never gives more than FULL + EXTRA, however it is farmed.
    public static final double LEGS_FULL = 20.0D;
    public static final double LEGS_EXTRA = 10.0D;
    public static final double JUMP_FULL = 2.5D;
    public static final double JUMP_EXTRA = 1.2D;
    public static final double LABOUR_FULL = 26.0D;
    public static final double LABOUR_EXTRA = 13.0D;
    public static final double FIGHT_FULL = 24.0D;
    public static final double FIGHT_EXTRA = 12.0D;
    public static final double DRILL_FULL = 30.0D;
    public static final double DRILL_EXTRA = 15.0D;

    // ------------------------------------------------------------------ the first look at an existing player

    /**
     * Existing worlds: the first time the system sees a player it credits the statistics he has already piled up, at
     * this share of the rates above (old work counts for half), never above {@link #INITIAL_CAP_LEVEL}'s threshold
     * (a player in diamond armour lands in the middle of the ladder, not at the top). Done once ({@code BodyState#seeded}).
     */
    public static final double INITIAL_SHARE = 0.5D;
    public static final int INITIAL_CAP_LEVEL = 5;

    // ------------------------------------------------------------------ sect drills (explicit, never by accident)

    /** At the sect (sect land or the South Peak climb face) a drill pays this much more: with the others, under the mentor's eye. */
    public static final double SECT_BONUS = 1.25D;
    /** Raw points of one block of height with the training stone. */
    public static final double CARRY_EACH = 1.0D;
    /** Raw points of a finished South Peak climb and of a finished trail sprint. */
    public static final double CLIMB_RUN = 25.0D;
    public static final double TRAIL_RUN = 18.0D;
    /** Hunger: food exhaustion per raw point of a drill. */
    public static final float EXHAUSTION_PER_POINT = 0.08F;
    /** A new best time adds this share of the run's points. */
    public static final double BEST_BONUS = 0.5D;
    /** Trail sprint: below this share of sprinting ticks the run counts at its share. */
    public static final double TRAIL_SPRINT_SHARE = 0.6D;

    /** Carry stone: movement speed −{@code CARRY_SLOW}, minus {@link #CARRY_SLOW_PER_LEVEL} per body level. */
    public static final double CARRY_SLOW = 0.35D;
    public static final double CARRY_SLOW_PER_LEVEL = 0.025D;
    /**
     * Carry gains start once the stone is this many blocks above where it was lifted (then the first blocks count
     * too): putting it down and lifting it again on one step is not a climb (codex 05.10).
     */
    public static final int CARRY_MIN_RISE = 3;
    /** Climb run: falling this far below the last ledge sends you back to the last rest ledge. */
    public static final double CLIMB_FALL = 3.5D;
    /** Runs end by themselves after this long, ticks (10 and 15 minutes). */
    public static final int CLIMB_TIMEOUT = 12000;
    public static final int TRAIL_TIMEOUT = 18000;
    /** Leaving the route this far (blocks) abandons the run. */
    public static final double CLIMB_ABANDON = 40.0D;
    public static final double TRAIL_ABANDON = 60.0D;

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
     * Hard gate (author 21.10): a full qi wall with a body below these stages does not start a breakthrough. Stages
     * 1 / 2 / 4 / 6 = 40 / 130 / 422 / 841 body points. Read by {@link BodyRules#breakthroughReady}.
     */
    public static final int[] BREAKTHROUGH_BODY_LEVEL = {0, 1, 2, 4, 6};
    /** Body stage the Peak sub-stages II and III need (index = stage - 1): 1093 and 1676 body points. */
    public static final int[] PEAK_STAGE_BODY_LEVEL = {7, 9};

    private TrainingBalance() {
    }
}
