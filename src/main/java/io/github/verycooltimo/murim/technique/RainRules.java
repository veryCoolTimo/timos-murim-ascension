package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;

/**
 * «Ливень Цветущей Сливы» — форма Меча 24 Движений Цветущей Сливы, секретная техника Хуашань
 * (docs/design/techniques/twenty-four-plum-rainfall-spec.md, рефы «24 plum blossom rainfall» r01–r10).
 *
 * <p>Стойка → частые уколы в шесть сторон (руки двоятся) → от рук поднимается ветер → резкий
 * замах: мастер проносится сбоку цели и встаёт у неё за спиной, за мечом — огромный розовый след →
 * урона нет, цель застывает в иллюзии: в небе раскрывается цветок-ядро из лепестков, лепестки
 * медленно идут к ней → разом обрушиваются ливнем; урон приносит только ливень.
 *
 * <p>Тики — от начала техники (T). Сервер получает {@code since} от начала IMPACT ({@link #RELEASE}),
 * клиент считает от события START. Общие для сервера (тайминги) и клиента (рисунок).
 */
public final class RainRules {

    /** Стойка: вход в позу 0–5, удержание 6–15. */
    public static final int STANCE_HOLD = 6;
    public static final int FLURRY = 16;
    /**
     * Пики уколов (тики T): ритм неровный, к концу частит — руки сливаются в остаточный образ.
     * Три цикла по шесть сторон.
     */
    public static final float[] PEAKS = {17, 19, 22, 24, 27, 30, 32, 34, 35, 37, 39, 41, 42.5F, 43.5F, 44.5F, 45.5F, 46.5F, 47.5F};
    /** Порядок сторон в каждом цикле (индексы {@link #DIRS}). */
    public static final int[] ORDER = {0, 3, 1, 5, 2, 4, 2, 0, 4, 1, 5, 3, 1, 4, 0, 5, 3, 2};
    /** Шесть сторон укола: {рысканье°, подъём°} от взгляда; несимметрично (codex 02.10). */
    public static final double[][] DIRS = {{-55, 20}, {40, -15}, {-25, -30}, {60, 25}, {0, 40}, {10, -35}};
    /** С этого тика от рук поднимается ветер (r03). */
    public static final int WIND_RISE = 32;
    public static final int HOLD_B = 48;
    public static final int LOAD = 54;
    /** Резкий замах и проход за спину (начало IMPACT). */
    public static final int RELEASE = 60;
    public static final int DASH_TICKS = 7;
    /** Мастер останавливается за спиной цели на этом расстоянии от её центра. */
    public static final double BEHIND = 4.0D;
    /** Боковой вынос пути: проход сбоку цели, а не сквозь неё. */
    public static final double SIDE = 0.7D;
    /** Метка замаха: проверка, что мастер прошёл у цели. */
    public static final int MARK = RELEASE + DASH_TICKS - 1;
    public static final int TRAIL_HOLD = 76;
    public static final int TRAIL_GONE = 87;
    /** Иллюзия раскрывается. */
    public static final int BLOOM = 84;
    public static final int BLOOM_FULL = 104;
    /** Лепестки идут к цели по трём S-образным нитям. */
    public static final int STREAM = 104;
    /** Подвешенное ожидание: нити замирают, ядро дышит. */
    public static final int STILL = 124;
    /** Надпись — в паузе перед ливнем. */
    public static final int CAPTION = 118;
    /** Три волны ливня: выпуск и касание через {@link #FALL} тика. */
    public static final int[] COHORTS = {128, 132, 136};
    public static final int FALL = 4;
    public static final int AFTER = 144;
    public static final int END = 200;

    /** Высота ядра над ступнями цели и вынос в сторону мастера (виден от первого лица). */
    public static final double CORE_HEIGHT = 7.2D;
    public static final double CORE_OFFSET = 2.0D;
    /** Радиус пятна ливня. */
    public static final double RAIN_RADIUS = 1.6D;
    public static final double RANGE = 16.0D;

    /** Урон волн ливня (× урон в руке × сила слоя): морось, морось, обрушение. */
    public static final double[] DMG = {1.0D, 1.0D, 4.0D};
    public static final double DMG_TRAINING = 1.0D;

    public static int contact(int cohort) {
        return COHORTS[cohort] + FALL;
    }

    /** Сила слоя: слой 7 = 1, слой 8 сильнее. */
    public static double power(int layer) {
        return layer <= 0 ? 0.0D : layer >= 8 ? 1.15D : 0.3D + 0.1D * layer;
    }

    /** Плотность эффектов по слою (доля от полного рисунка слоя 7). */
    public static double density(int layer) {
        return switch (Math.max(0, Math.min(8, layer))) {
            case 0 -> 0.0D;
            case 1 -> 0.10D;
            case 2 -> 0.20D;
            case 3 -> 0.30D;
            case 4 -> 0.45D;
            case 5 -> 0.60D;
            case 6 -> 0.80D;
            case 7 -> 1.0D;
            default -> 1.25D;
        };
    }

    /** Масштаб иллюзии по слою. */
    public static double scale(int layer) {
        return switch (Math.max(0, Math.min(8, layer))) {
            case 0 -> 0.0D;
            case 1 -> 0.45D;
            case 2 -> 0.55D;
            case 3 -> 0.65D;
            case 4 -> 0.75D;
            case 5 -> 0.85D;
            case 6 -> 0.93D;
            case 7 -> 1.0D;
            default -> 1.08D;
        };
    }

    /** Остаточные образы рук — со 2-го слоя; лепестки — с 3-го. */
    public static boolean afterimages(int layer) {
        return layer >= 2;
    }

    public static boolean petals(int layer) {
        return layer >= 3;
    }

    /** Оболочек цветка-ядра: 1 на 3–5, 2 на 6, 3 с 7-го. */
    public static int shells(int layer) {
        return layer >= 7 ? 3 : layer == 6 ? 2 : layer >= 3 ? 1 : 0;
    }

    /** Столб света из вертикальных лент в ливне — с 6-го слоя. */
    public static boolean column(int layer) {
        return layer >= 6;
    }

    /** Направление укола {@code k} в мире по рысканью мастера. */
    public static Vec3 thrust(float yawDeg, int k) {
        double[] d = DIRS[ORDER[k % ORDER.length]];
        return Vec3.directionFromRotation((float) -d[1], yawDeg + (float) d[0]);
    }

    /** Точка остановки за спиной цели: по линии подхода, со сдвигом в сторону прохода. */
    public static Vec3 behind(Vec3 from, Vec3 target, double targetWidth) {
        Vec3 dir = new Vec3(target.x - from.x, 0.0D, target.z - from.z);
        dir = dir.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : dir.normalize();
        Vec3 side = new Vec3(-dir.z, 0.0D, dir.x);
        return target.add(dir.scale(BEHIND + targetWidth * 0.5D)).add(side.scale(SIDE * 0.5D));
    }

    /** Ядро иллюзии над целью: вынос к мастеру-зрителю, чтобы его было видно со спины цели. */
    public static Vec3 core(Vec3 targetFeet, Vec3 approach, int layer) {
        Vec3 dir = approach.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : approach.normalize();
        double k = 0.75D + 0.25D * scale(layer);
        return targetFeet.add(dir.scale(-CORE_OFFSET * k)).add(0.0D, CORE_HEIGHT * k, 0.0D);
    }

    private RainRules() {
    }
}
