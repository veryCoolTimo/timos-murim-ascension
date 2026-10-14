package io.github.verycooltimo.murim.technique;

/**
 * Huashan retains the original travel-tier table T0..4. God Wind overloads use every
 * mastery level L0..8 from docs/design/research/ar-technique-stars.md §4, with the task's
 * stronger L8 speed jump. Jump, wall and air-turn unlocks remain in FootworkFamily.
 */
public final class TraverseRules {

    /** God Wind L0..8; L8 is the requested qualitative jump (30 blocks/s after acceleration). */
    private static final double[] WIND_SPEED = {7, 7.5, 8.5, 9, 10, 11.5, 12, 13, 18.75};
    private static final int[] WIND_TICKS = {60, 70, 80, 90, 100, 120, 140, 160, 180};
    private static final double[] WIND_QI = {0.40, 0.38, 0.36, 0.34, 0.32, 0.30, 0.28, 0.26, 0.24};
    private static final int[] WIND_PAUSE = {20, 20, 20, 20, 20, 18, 18, 16, 16};
    public static final int WIND_RAMP_TICKS = 20;
    public static final double WIND_TOP = 1.6D;
    public static final double HUASHAN_FAST = 1.4D;

    public static double speed(FootworkFamily family, int executionTier) {
        return family == FootworkFamily.WIND_GOD ? WIND_SPEED[FootworkFamily.windTier(executionTier)] : speed(family.tier(executionTier));
    }

    public static double acceleratedSpeed(FootworkFamily family, int executionTier, int paidTicks) {
        double factor = family == FootworkFamily.WIND_GOD
                ? 1.0D + (WIND_TOP - 1.0D) * Math.max(0, Math.min(WIND_RAMP_TICKS, paidTicks)) / WIND_RAMP_TICKS
                : HUASHAN_FAST;
        return speed(family, executionTier) * factor;
    }

    public static int maxTicks(FootworkFamily family, int executionTier) {
        return family == FootworkFamily.WIND_GOD ? WIND_TICKS[FootworkFamily.windTier(executionTier)] : maxTicks(family.tier(executionTier));
    }

    public static double qiPerSecond(FootworkFamily family, int executionTier) {
        return family == FootworkFamily.WIND_GOD ? WIND_QI[FootworkFamily.windTier(executionTier)] : qiPerSecond(family.tier(executionTier));
    }

    public static int cooldown(FootworkFamily family, int executionTier) {
        return family == FootworkFamily.WIND_GOD ? WIND_PAUSE[FootworkFamily.windTier(executionTier)] : COOLDOWN;
    }

    /** Обычный спринт игрока ≈ 5,6 блока в секунду; скорость техники считается от него. */
    public static final double VANILLA_SPRINT = 5.6D;

    /** Крейсерская скорость, блоков в секунду. */
    public static double speed(int executionTier) {
        return switch (clamp(executionTier)) {
            case 0 -> 7.0D;
            case 1 -> 8.5D;
            case 2 -> 10.0D;
            case 3 -> 11.5D;
            default -> 13.0D;
        };
    }

    /** Добавка к множителю скорости поверх спринта (ADD_MULTIPLIED_TOTAL). */
    public static double speedBonus(int executionTier) {
        return speed(executionTier) / VANILLA_SPRINT - 1.0D;
    }

    /** Наибольшая длительность одного включения, тиков. */
    public static int maxTicks(int executionTier) {
        return switch (clamp(executionTier)) {
            case 0 -> 60;
            case 1 -> 80;
            case 2 -> 100;
            case 3 -> 120;
            default -> 160;
        };
    }

    /** Ци в секунду бега — дешевле с освоением. */
    public static double qiPerSecond(int executionTier) {
        return 0.40D - 0.04D * clamp(executionTier);
    }

    /** Высота ступени во время бега: со слоя 1 — целый блок. */
    public static double stepHeight(int executionTier) {
        return executionTier >= 1 ? 1.0D : 0.6D;
    }

    /** Безопасная высота падения во время бега и до первой посадки, блоков. */
    public static double safeFall(int executionTier) {
        return 3.0D + clamp(executionTier);
    }

    /** Горизонтальная скорость длинного прыжка, блоков за тик; 0 — длинного прыжка нет. */
    public static double leapHorizontal(int executionTier) {
        return switch (clamp(executionTier)) {
            case 0 -> 0.0D;
            case 1 -> 1.0D;
            case 2 -> 1.15D;
            case 3 -> 1.3D;
            default -> 1.45D;
        };
    }

    /** Вертикальный толчок длинного прыжка, блоков за тик (ванильный прыжок — 0,42). */
    public static double leapVertical(int executionTier) {
        return 0.5D + 0.05D * Math.max(0, clamp(executionTier) - 1);
    }

    public static final double LEAP_QI = 0.5D;
    public static final int LEAP_COOLDOWN = 20;

    /** Отталкиваний от стены между посадками. */
    public static int wallKicks(int executionTier) {
        return executionTier >= 4 ? 2 : executionTier >= 2 ? 1 : 0;
    }

    public static final double WALL_KICK_QI = 0.4D;
    /** Толчок от стены: наружу и вверх, блоков за тик. */
    public static final double WALL_KICK_OUT = 0.35D;
    public static final double WALL_KICK_UP = 0.5D;

    public static final double AIR_CORRECTION_QI = 0.3D;
    /** Скорость после коррекции, блоков за тик (8 блоков в секунду). */
    public static final double AIR_CORRECTION_SPEED = 0.4D;

    /** Кулдаун после конца бега, тиков. */
    public static final int COOLDOWN = 20;

    private static int clamp(int executionTier) {
        return Math.max(0, Math.min(4, executionTier));
    }

    private TraverseRules() {
    }
}
