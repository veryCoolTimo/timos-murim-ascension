package io.github.verycooltimo.murim.training;

/**
 * Pure rules of body tempering: levels, gains with daily diminishing returns and fatigue, recovery, effects.
 * No server here, so unit tests check the numbers ({@code BodyRulesTest}). Numbers live in {@link TrainingBalance}.
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

    /** Daily diminishing returns: 1 for the first point of the day, ½ after {@code DAILY_SOFT}. */
    public static double dailyFactor(double today) {
        return 1.0D / (1.0D + Math.max(0.0D, today) / TrainingBalance.DAILY_SOFT);
    }

    /** Fatigue factor: 1 while fresh, down to {@code FATIGUE_FLOOR} at full fatigue. */
    public static double fatigueFactor(double fatigue) {
        if (fatigue <= TrainingBalance.FATIGUE_SOFT) {
            return 1.0D;
        }
        double k = Math.min(1.0D, (fatigue - TrainingBalance.FATIGUE_SOFT) / (1.0D - TrainingBalance.FATIGUE_SOFT));
        return 1.0D - k * (1.0D - TrainingBalance.FATIGUE_FLOOR);
    }

    /**
     * Gain of one unit of {@code e}.
     *
     * @param quality rep quality ({@code QUALITY_*}) or a run's share
     * @param atSect  at the sect: {@code SECT_BONUS}
     */
    public static double gain(Exercise e, double quality, boolean atSect, BodyState s) {
        double raw = e.points() * quality * (atSect ? TrainingBalance.SECT_BONUS : 1.0D);
        return raw * dailyFactor(s.today()) * fatigueFactor(s.fatigue());
    }

    /** Applies one unit: points and fatigue. */
    public static BodyState apply(Exercise e, double quality, boolean atSect, BodyState s) {
        double g = gain(e, quality, atSect, s);
        double weight = e == Exercise.PEAK_CLIMB || e == Exercise.TRAIL_SPRINT ? 1.0D : Math.max(0.35D, quality);
        return s.gained(g).withFatigue(s.fatigue() + e.fatigue() * weight);
    }

    // ------------------------------------------------------------------ recovery

    public static double recoveryPerTick(boolean atSect) {
        return TrainingBalance.RECOVERY_PER_TICK * (atSect ? TrainingBalance.SECT_RECOVERY : 1.0D);
    }

    public static double afterMeal(double fatigue, int nutrition, boolean atSect) {
        double cut = nutrition * TrainingBalance.MEAL_PER_NUTRITION * (atSect ? TrainingBalance.SECT_MEAL : 1.0D);
        return Math.max(0.0D, fatigue - cut);
    }

    public static double afterSleep(double fatigue, boolean atSect) {
        return atSect ? 0.0D : fatigue * TrainingBalance.SLEEP_ELSEWHERE;
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

    public static boolean breakthroughReady(int targetRank, int bodyLevel) {
        return bodyLevel >= breakthroughNeed(targetRank);
    }

    private static int clampLevel(int level) {
        return Math.max(0, Math.min(TrainingBalance.MAX_LEVEL, level));
    }
}
