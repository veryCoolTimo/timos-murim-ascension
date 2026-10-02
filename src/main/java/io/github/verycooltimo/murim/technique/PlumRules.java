package io.github.verycooltimo.murim.technique;

/**
 * Меч Семи Цветков Сливы, форма 1 «Разрез» по слоям (docs/design/techniques/seven-plum-blossoms-spec.md
 * §2.2, §2.4). Слой 0 — учебный удар в ближнем секторе; дальше — восходящий разрез-столп по
 * коридору вперёд. Урон = базовый урон меча × коэффициент слоя, без дополнительного множителя
 * освоения. Числа — стартовые для плейтеста [проект codex].
 */
public final class PlumRules {

    /** Длина коридора вперёд, блоков (от 0,4 перед стопами). 0 — учебный удар без коридора. */
    public static double length(int layer) {
        return layer <= 0 ? 0.0D : layer == 1 ? 3.0D : layer == 2 ? 4.0D : 4.8D;
    }

    /** Ширина коридора, блоков. */
    public static double width(int layer) {
        return layer <= 1 ? 1.1D : layer == 2 ? 1.2D : 1.4D;
    }

    /** Высота столпа, блоков. */
    public static double height(int layer) {
        return layer <= 0 ? 2.2D : layer == 1 ? 3.0D : layer == 2 ? 4.0D : 4.8D;
    }

    /** Коэффициент к базовому урону меча. */
    public static double coefficient(int layer) {
        return layer <= 0 ? 1.0D : layer == 1 ? 1.15D : layer == 2 ? 1.30D : 1.45D;
    }

    /** Учебный удар слоя 0: радиус и угол сектора. */
    public static final double TRAINING_REACH = 2.4D;
    public static final double TRAINING_ARC = 55.0D;

    /**
     * Шкала «Разреза» (автор 02.10: «всё быстровато, нужно держать»; ритм — codex по ref1–9),
     * в тиках после первого удара вверх (он сам — на 16-м тике техники, 0,8 с):
     * пауза до {@link #SWING_START}, шесть методичных взмахов через {@link #SWING_GAP},
     * толчок ладонью на {@link #PUSH}, изгиб и падение до {@link #LAND}.
     */
    public static final int SWING_START = 18;
    public static final int SWING_GAP = 6;
    public static final int PUSH = 66;
    public static final int FALL_START = 67;
    public static final int LAND = 79;
    /** Старое имя момента удара дерева — для сервера. */
    public static final int FALL_TICK = LAND;

    /** Высота дерева по слоям: на 4-м — 12 блоков (ref7–9: многократно выше человека). */
    public static double treeHeight(int layer) {
        return switch (Math.max(0, Math.min(7, layer))) {
            case 0 -> 0.0D;
            case 1 -> 5.0D;
            case 2 -> 7.0D;
            case 3 -> 9.5D;
            case 4 -> 12.0D;
            case 5 -> 13.0D;
            case 6 -> 14.0D;
            default -> 15.0D;
        };
    }

    /** Оглушение после первого удара: до падения дерева (обычные мобы). */
    public static final int STAGGER_TICKS = LAND + 4;
    public static final int STAGGER_PVP_TICKS = 12;
    public static final int STAGGER_BOSS_TICKS = 10;

    /** Коэффициент удара падающего дерева: слой 1 — один ствол, дальше крона тяжелее. */
    public static double fallCoefficient(int layer) {
        return layer <= 0 ? 0.0D : layer == 1 ? 0.8D : layer <= 3 ? 1.6D : 2.0D;
    }

    /** Основание ствола: от стоп вперёд, не дальше ~2,4 блока (где бьёт и клиентский ствол). */
    public static double trunkOffset(double length) {
        return length <= 0.0D ? 0.4D : 3.0D;
    }

    /** Розовое цветение начинается с третьего слоя (до этого разрез холодный). */
    public static boolean blossoms(int layer) {
        return layer >= 3;
    }

    private PlumRules() {
    }
}
