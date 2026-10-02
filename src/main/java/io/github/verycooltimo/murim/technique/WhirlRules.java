package io.github.verycooltimo.murim.technique;

/**
 * «Вихрь Цветущей Сливы» — третья форма Меча Семи Цветков Сливы. Шкала и числа —
 * docs/design/techniques/seven-plum-whirlwind-spec.md (сжатая до 9,8 с версия разбора codex).
 * Все тики — от первого Разреза вверх (тик 28 техники, начало фазы IMPACT).
 */
public final class WhirlRules {

    /** Тик техники, на котором идёт Разрез вверх (конец windup). */
    public static final int SLASH = 28;
    /** Три взмаха — столпы и стены, тики после Разреза. */
    public static final int[] WALL_STROKES = {16, 24, 32};
    public static final int TENSION = 32;
    public static final int CONVERGE = 44;
    public static final int SHATTER = 58;
    public static final int WHIRL = 68;
    public static final int WHIRL_END = 122;
    public static final int ARMS = 112;
    public static final int QUIET = 132;
    public static final int PASS = 144;
    public static final int PASS_TICKS = 12;
    public static final int EXIT = 156;
    public static final int END = 168;
    /** Импульсы урона вихря. */
    public static final int[] PULSES = {70, 77, 84, 91, 98, 105, 112, 119};

    public static final double DMG_SLASH = 0.8D;
    public static final double DMG_WALL = 0.25D;
    public static final double DMG_CONVERGE = 0.9D;
    public static final double DMG_PULSE = 0.2D;
    public static final double DMG_PASS = 2.2D;

    public static final double MAX_PULL = 0.16D;

    /** Радиус зоны по слою (блоки). */
    public static double radius(int layer) {
        return switch (Math.max(0, Math.min(7, layer))) {
            case 0, 1 -> 2.0D;
            case 2 -> 2.5D;
            case 3 -> 3.0D;
            case 4 -> 3.5D;
            case 5 -> 4.0D;
            case 6 -> 4.5D;
            default -> 5.0D;
        };
    }

    public static double height(int layer) {
        return Math.min(6.0D, 2.5D + 0.5D * Math.max(0, layer));
    }

    /** Число столпов: 0 на слоях 0–1, 4 на 2-м, 6 на 3-м, 8 на 4–6-м, 12 на 7-м. */
    public static int pillars(int layer) {
        return layer <= 1 ? 0 : layer == 2 ? 4 : layer == 3 ? 6 : layer <= 6 ? 8 : 12;
    }

    public static boolean walls(int layer) {
        return layer >= 4;
    }

    public static boolean whirl(int layer) {
        return layer >= 5;
    }

    public static boolean finale(int layer) {
        return layer >= 6;
    }

    private WhirlRules() {
    }
}
