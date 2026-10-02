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

    /** Через сколько тиков после первого удара дерево падает: рука уходит вперёд к 1,45 с, жест держится 3 тика. */
    public static final int FALL_TICK = 20;

    /** Коэффициент удара падающего дерева: слой 1 — один ствол, дальше крона тяжелее. */
    public static double fallCoefficient(int layer) {
        return layer <= 0 ? 0.0D : layer == 1 ? 0.4D : layer <= 3 ? 0.6D : 0.8D;
    }

    /** Основание ствола: от стоп вперёд, не дальше ~2,4 блока (где бьёт и клиентский ствол). */
    public static double trunkOffset(double length) {
        return 0.4D + Math.min(2.4D, 0.5D * length);
    }

    /** Розовое цветение начинается с третьего слоя (до этого разрез холодный). */
    public static boolean blossoms(int layer) {
        return layer >= 3;
    }

    private PlumRules() {
    }
}
