package io.github.verycooltimo.murim.technique;

/**
 * Таблица бега шагов по ступени T0–4 — общая для семейств (Шаг Молнии Бога Ветров, лёгкий бег
 * Хуашань). Аргумент методов — ступень T, не слой: слой семейства переводит в T
 * {@link FootworkFamily#tier(int)} (docs/design/21-footwork-families.md §2). Что открыто
 * (перелёт, стена, поворот) решает семейство, а не эта таблица. Числа — стартовые балансные.
 */
public final class TraverseRules {

    /** Обычный спринт игрока ≈ 5,6 блока в секунду; скорость техники считается от него. */
    public static final double VANILLA_SPRINT = 5.6D;

    /** Крейсерская скорость, блоков в секунду. */
    public static double speed(int layer) {
        return switch (clamp(layer)) {
            case 0 -> 7.0D;
            case 1 -> 8.5D;
            case 2 -> 10.0D;
            case 3 -> 11.5D;
            default -> 13.0D;
        };
    }

    /** Добавка к множителю скорости поверх спринта (ADD_MULTIPLIED_TOTAL). */
    public static double speedBonus(int layer) {
        return speed(layer) / VANILLA_SPRINT - 1.0D;
    }

    /** Наибольшая длительность одного включения, тиков. */
    public static int maxTicks(int layer) {
        return switch (clamp(layer)) {
            case 0 -> 60;
            case 1 -> 80;
            case 2 -> 100;
            case 3 -> 120;
            default -> 160;
        };
    }

    /** Ци в секунду бега — дешевле с освоением. */
    public static double qiPerSecond(int layer) {
        return 0.40D - 0.04D * clamp(layer);
    }

    /** Высота ступени во время бега: со слоя 1 — целый блок. */
    public static double stepHeight(int layer) {
        return layer >= 1 ? 1.0D : 0.6D;
    }

    /** Безопасная высота падения во время бега и до первой посадки, блоков. */
    public static double safeFall(int layer) {
        return 3.0D + clamp(layer);
    }

    /** Горизонтальная скорость длинного прыжка, блоков за тик; 0 — длинного прыжка нет. */
    public static double leapHorizontal(int layer) {
        return switch (clamp(layer)) {
            case 0 -> 0.0D;
            case 1 -> 1.0D;
            case 2 -> 1.15D;
            case 3 -> 1.3D;
            default -> 1.45D;
        };
    }

    /** Вертикальный толчок длинного прыжка, блоков за тик (ванильный прыжок — 0,42). */
    public static double leapVertical(int layer) {
        return 0.5D + 0.05D * Math.max(0, clamp(layer) - 1);
    }

    public static final double LEAP_QI = 0.5D;
    public static final int LEAP_COOLDOWN = 20;

    /** Отталкиваний от стены между посадками. */
    public static int wallKicks(int layer) {
        return layer >= 4 ? 2 : layer >= 2 ? 1 : 0;
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

    private static int clamp(int layer) {
        return Math.max(0, Math.min(4, layer));
    }

    private TraverseRules() {
    }
}
