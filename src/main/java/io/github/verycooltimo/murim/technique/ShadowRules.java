package io.github.verycooltimo.murim.technique;

/**
 * Шаг Тени Бога Ветров по слоям семейства (docs/design/21-footwork-families.md §4: «уйти от
 * взгляда одного, с мастерством — от десятков»). Слой 0 — закрыт. Цены переведены в шкалу ци
 * проекта (техника стоит 1–3 единицы), остальные числа — из решения codex [проект].
 */
public final class ShadowRules {

    private static final int[] DURATION = {0, 40, 60, 80, 80, 100, 120, 120};
    private static final double[] SPEED = {0, 2.5, 3.0, 3.5, 3.5, 4.0, 4.5, 4.5};
    private static final double[] DETECTION = {1, 0.85, 0.70, 0.55, 0.55, 0.40, 0.30, 0.30};
    private static final int[] OBSERVERS = {0, 1, 2, 3, 3, 5, 8, 8};
    private static final int[] COOLDOWN = {0, 80, 80, 70, 70, 60, 60, 60};
    private static final double[] ENTRY_QI = {0, 0.6, 0.6, 0.5, 0.5, 0.5, 0.4, 0.4};
    private static final double[] QI_PER_SECOND = {0, 0.4, 0.35, 0.3, 0.3, 0.25, 0.2, 0.2};

    /** Ванильная скорость ходьбы игрока, блоков в секунду (присед — 0,3 от неё). */
    public static final double WALK = 4.317D;
    /** Ближе этого моб замечает всегда. */
    public static final double MIN_RADIUS = 2.0D;
    /** Касание раскрывает. */
    public static final double CONTACT = 1.5D;
    /** Радиус, в котором выбираются «обманутые» наблюдатели. */
    public static final double OBSERVER_RANGE = 32.0D;

    private static int i(int layer) {
        return Math.max(0, Math.min(7, layer));
    }

    public static int duration(int layer) {
        return DURATION[i(layer)];
    }

    /** Скорость в приседе, блоков в секунду. */
    public static double speed(int layer) {
        return SPEED[i(layer)];
    }

    /** Множитель радиуса обнаружения; на свету (≥12) — мягче: min(1, 1,25k). */
    public static double detection(int layer, boolean bright) {
        double k = DETECTION[i(layer)];
        return bright ? Math.min(1.0D, 1.25D * k) : k;
    }

    public static int observers(int layer) {
        return OBSERVERS[i(layer)];
    }

    public static int cooldown(int layer) {
        return COOLDOWN[i(layer)];
    }

    public static double entryQi(int layer) {
        return ENTRY_QI[i(layer)];
    }

    public static double qiPerSecond(int layer) {
        return QI_PER_SECOND[i(layer)];
    }

    /** Радиус, с которого моб замечает игрока в Тени. */
    public static double radius(double normal, int layer, boolean bright) {
        return Math.max(MIN_RADIUS, normal * detection(layer, bright));
    }

    /** Шаг Смерти: дальность прохода по слою семейства (L3–4 — 5, L5–6 — 6, L7 — 7 блоков). */
    public static double deathDistance(int layer) {
        return layer >= 7 ? 7.0D : layer >= 5 ? 6.0D : 5.0D;
    }

    private ShadowRules() {
    }
}
