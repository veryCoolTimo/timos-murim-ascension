package io.github.verycooltimo.murim.training;

/**
 * Pure rules of the tempered body: levels, effects, breakthrough gates and the one-off initial grant. No server here,
 * so unit tests check the numbers ({@code BodyRulesTest}). Numbers live in {@link TrainingBalance}; how work becomes
 * points is {@link BodyActivity}.
 */
public final class BodyRules {

    private BodyRules() {
    }

    /** Points needed for {@code level}. */
    public static double threshold(int level) {
        return level <= 0 ? 0.0D : TrainingBalance.LEVEL_BASE * Math.pow(level, TrainingBalance.LEVEL_EXP);
    }

    public static int level(double points) {
        int l = 0;
        while (l < TrainingBalance.MAX_LEVEL && points >= threshold(l + 1)) {
            l++;
        }
        return l;
    }

    /** 0..1 progress from the current level to the next (1 at the top). */
    public static double progress(double points) {
        int l = level(points);
        if (l >= TrainingBalance.MAX_LEVEL) {
            return 1.0D;
        }
        double lo = threshold(l);
        return (points - lo) / (threshold(l + 1) - lo);
    }

    /**
     * The initial grant for an existing player: what the statistics he already has would have paid at the plain rates
     * ({@code lifetimeRaw}), at {@link TrainingBalance#INITIAL_SHARE}, never above the threshold of
     * {@link TrainingBalance#INITIAL_CAP_LEVEL}.
     */
    public static double initialGrant(double lifetimeRaw) {
        return Math.max(0.0D, Math.min(lifetimeRaw * TrainingBalance.INITIAL_SHARE, threshold(TrainingBalance.INITIAL_CAP_LEVEL)));
    }

    // ------------------------------------------------------------------ effects

    public static double health(int level) {
        return clampLevel(level) * TrainingBalance.HEALTH_PER_LEVEL;
    }

    public static double knockback(int level) {
        return clampLevel(level) * TrainingBalance.KNOCKBACK_PER_LEVEL;
    }

    /** Multiplier of the footwork run length. */
    public static double footworkStamina(int level) {
        return 1.0D + clampLevel(level) * TrainingBalance.FOOTWORK_PER_LEVEL;
    }

    /** Movement speed share lost while carrying the training stone (negative modifier value). */
    public static double carrySlow(int level) {
        return -Math.max(0.05D, TrainingBalance.CARRY_SLOW - clampLevel(level) * TrainingBalance.CARRY_SLOW_PER_LEVEL);
    }

    /** Body level a breakthrough into {@code targetRank} needs (0 — none; {@code BREAKTHROUGH_BODY_LEVEL}). */
    public static int breakthroughNeed(int targetRank) {
        int[] need = TrainingBalance.BREAKTHROUGH_BODY_LEVEL;
        return targetRank >= 0 && targetRank < need.length ? need[targetRank] : 0;
    }

    /** Body level the Peak sub-stage {@code targetStage} (1 = II, 2 = III) needs; 0 outside that range. */
    public static int stageNeed(int targetStage) {
        int[] need = TrainingBalance.PEAK_STAGE_BODY_LEVEL;
        return targetStage >= 1 && targetStage <= need.length ? need[targetStage - 1] : 0;
    }

    public static boolean stageReady(int targetStage, int bodyLevel) {
        return bodyLevel >= stageNeed(targetStage);
    }

    public static boolean breakthroughReady(int targetRank, int bodyLevel) {
        return bodyLevel >= breakthroughNeed(targetRank);
    }

    private static int clampLevel(int level) {
        return Math.max(0, Math.min(TrainingBalance.MAX_LEVEL, level));
    }
}
