package io.github.verycooltimo.murim.technique;

/**
 * Шаг Тени Бога Ветров по слоям семейства (docs/design/21-footwork-families.md §4: «уйти от
 * взгляда одного, с мастерством — от десятков»). Слой 0 — закрыт. Цены переведены в шкалу ци
 * проекта (техника стоит 1–3 единицы), остальные числа — из решения codex [проект].
 */
public final class ShadowRules {

    private static final double[] DEATH_DISTANCE = {5, 5, 5, 5, 5, 6, 6, 7, 8};
    private static final int[] DURATION = {0, 40, 60, 70, 80, 100, 110, 120, 140};
    private static final double[] SPEED = {0, 2.5, 3.0, 3.5, 3.5, 4.0, 4.5, 4.5, 5.0};
    private static final double[] DETECTION = {1, .85, .70, .60, .50, .40, .35, .30, .25};
    private static final int[] OBSERVERS = {0, 1, 2, 3, 4, 5, 6, 8, 12};
    private static final int[] COOLDOWN = {0, 80, 80, 75, 70, 65, 60, 60, 60};
    private static final double[] ENTRY_QI = {0, .60, .60, .55, .50, .50, .45, .40, .40};
    private static final double[] QI_PER_SECOND = {0, .40, .35, .32, .30, .26, .23, .20, .18};

    /** Ванильная скорость ходьбы игрока, блоков в секунду (присед — 0,3 от неё). */
    public static final double WALK = 4.317D;
    /** Ближе этого моб замечает всегда. */
    public static final double MIN_RADIUS = 2.0D;
    /** Касание раскрывает. */
    public static final double CONTACT = 1.5D;
    /** Радиус, в котором выбираются «обманутые» наблюдатели. */
    public static final double OBSERVER_RANGE = 32.0D;

    private static int i(int executionTier) {
        return Math.max(0, Math.min(8, executionTier));
    }

    public static int duration(int executionTier) {
        return DURATION[i(executionTier)];
    }

    /** Скорость в приседе, блоков в секунду. */
    public static double speed(int executionTier) {
        return SPEED[i(executionTier)];
    }

    /** Множитель радиуса обнаружения; на свету (≥12) — мягче: min(1, 1,25k). */
    public static double detection(int executionTier, boolean bright) {
        double k = DETECTION[i(executionTier)];
        return bright ? Math.min(1.0D, 1.25D * k) : k;
    }

    public static int observers(int executionTier) {
        return OBSERVERS[i(executionTier)];
    }

    public static int cooldown(int executionTier) {
        return COOLDOWN[i(executionTier)];
    }

    public static double entryQi(int executionTier) {
        return ENTRY_QI[i(executionTier)];
    }

    public static double qiPerSecond(int executionTier) {
        return QI_PER_SECOND[i(executionTier)];
    }

    /** Радиус, с которого моб замечает игрока в Тени. */
    public static double radius(double normal, int executionTier, boolean bright) {
        return Math.max(MIN_RADIUS, normal * detection(executionTier, bright));
    }

    /** Шаг Смерти: дальность прохода по слою семейства (L3–4 — 5, L5–6 — 6, L7 — 7, L8 — 8 блоков). */
    public static double deathDistance(int executionTier) {
        return DEATH_DISTANCE[i(executionTier)];
    }

    private ShadowRules() {
    }
}
