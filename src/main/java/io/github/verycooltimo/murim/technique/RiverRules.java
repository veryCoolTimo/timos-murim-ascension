package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;

/**
 * «Опадающие Лепестки, Перекрывающие Реку» (FALLING PLUM BLOSSOMS RIVER SPLIT) — вершина Меча
 * 24 Движений Цветущей Сливы (docs/design/techniques/twenty-four-plum-river-spec.md, рефы
 * «24 blossoms technique/river split» river-01…16, роман гл. 230).
 *
 * <p>Без рывка: голубой выброс → красная концентрация, жест у подбородка → меч дрожит (резонанс) →
 * в каждом месте, где прошла вибрация, рождается лепесток (сначала одиночные, потом сотни) →
 * множество стягивается вихрем к кисти → один широкий изогнутый взмах: жгут лепестков идёт к цели →
 * на контакте поток останавливает цель («перекрывает реку»), лепестки сжимаются в точку →
 * звёздный взрыв по оси атаки. Шкала и числа — codex 03.10 по рефам и чек-листу.
 *
 * <p>Тики — от начала техники (T). Сервер получает {@code since} от начала IMPACT ({@link #AIM}).
 */
public final class RiverRules {

    /** Голубой предвыброс (r01) до этого тика. */
    public static final int BLUE_END = 12;
    /** Красная концентрация (r02–r06): жест собран к 20, удержание до 30. */
    public static final int CONCENTRATE = 12;
    public static final int GESTURE_HELD = 20;
    /** Резонанс меча (r07): малые быстрые смещения, копии руки, поперечные штрихи. */
    public static final int RESONANCE = 30;
    /** Три короткие проводки (r08–r09): {начало, конец движения, конец удержания}. */
    public static final int[][] STROKES = {{46, 50, 54}, {54, 62, 68}, {68, 76, 80}};
    /** Сбор множества к кисти (r10). */
    public static final int GATHER = 80;
    /** Прицел зафиксирован (начало IMPACT): дальше поток не доворачивает за целью. */
    public static final int AIM = 94;
    /** Натяжение 94–100, выпуск — один широкий изогнутый взмах, пик скорости руки 102–103. */
    public static final int RELEASE = 100;
    /** Самый дальний контакт. */
    public static final int CONTACT_MAX = 108;
    /** Звёздный взрыв (r16 + роман). */
    public static final int BURST = 120;
    /** Мастер держит окончание взмаха до этого тика. */
    public static final int HOLD_END = 136;
    public static final int END = 170;

    /** Дальность выбора цели и выпуска. */
    public static final double RANGE = 24.0D;
    /** Конус выбора цели без захвата (полуугол), 3D — цель может быть в небе. */
    public static final double CONE = 32.0D;
    /** Радиус проверки потока (не зависит от слоя). */
    public static final double STREAM_RADIUS = 0.45D;
    /** Радиус взрыва — одинаков на всех слоях. */
    public static final double BURST_RADIUS = 3.0D;

    /** Взрыв: основная цель 8H, остальные 3H → 1,5H к краю (× сила слоя). */
    public static final double DMG_PRIMARY = 8.0D;
    public static final double DMG_AREA = 3.0D;
    public static final double DMG_TRAINING = 1.0D;
    /** Отброс, блоков/тик: основная цель по оси выпуска, остальные — от узла; подъём; предел. */
    public static final double PUSH_PRIMARY = 1.0D;
    public static final double PUSH_AREA = 0.65D;
    public static final double PUSH_LIFT = 0.15D;
    public static final double PUSH_MAX = 1.2D;
    /** Босс удерживается не дольше этого. */
    public static final int BOSS_HOLD = 10;

    /** Поток идёт до цели: близко — быстро, к 24 блокам — 8 тиков. */
    public static int flight(double distance) {
        return (int) Math.max(3, Math.min(CONTACT_MAX - RELEASE, Math.round(3.0D + distance / 4.0D)));
    }

    public static int contact(double distance) {
        return RELEASE + flight(distance);
    }

    /** Урон по области: 3H в центре → 1,5H на границе. */
    public static double areaShare(double distance) {
        double d = Math.max(0.0D, Math.min(BURST_RADIUS, distance));
        return DMG_AREA * (1.0D - 0.5D * d / BURST_RADIUS);
    }

    /** Сила слоя: как у Ливня — слой 7 = 1, слой 8 сильнее. */
    public static double power(int layer) {
        return layer <= 0 ? 0.0D : layer >= 8 ? 1.15D : 0.3D + 0.1D * layer;
    }

    /** Лепестков к концу рождения (codex 03.10): 0 до 3-го слоя. */
    public static int petals(int layer) {
        return switch (Math.max(0, Math.min(8, layer))) {
            case 3 -> 160;
            case 4 -> 300;
            case 5 -> 480;
            case 6 -> 680;
            case 7 -> 900;
            case 8 -> 1200;
            default -> 0;
        };
    }

    /** Доля лепестков, рождённых к тику {@code t}: одиночные → пауза → множество. */
    public static double born(double t) {
        if (t < STROKES[0][0]) {
            return 0.0D;
        }
        if (t < STROKES[1][0]) {
            return 0.0D;
        }
        if (t < STROKES[1][1]) {
            return 0.32D * (t - STROKES[1][0]) / (STROKES[1][1] - STROKES[1][0]);
        }
        if (t < STROKES[2][0]) {
            return 0.32D + 0.1D * (t - STROKES[1][1]) / (STROKES[2][0] - STROKES[1][1]);
        }
        if (t < STROKES[2][1]) {
            return 0.42D + 0.48D * (t - STROKES[2][0]) / (STROKES[2][1] - STROKES[2][0]);
        }
        if (t < GATHER) {
            return 0.9D + 0.1D * (t - STROKES[2][1]) / (GATHER - STROKES[2][1]);
        }
        return 1.0D;
    }

    /** Первые одиночные лепестки первой проводки: «по одному он бесконечно хрупок». */
    public static final int LONE = 4;

    /** Струй выпуска / спиралей сбора / звёздных лучей по слою. */
    public static int strands(int layer) {
        return layer >= 7 ? 7 : layer >= 5 ? 5 : layer >= 4 ? 3 : layer >= 1 ? 1 : 0;
    }

    public static int spirals(int layer) {
        return layer >= 5 ? 3 : layer >= 4 ? 2 : layer >= 3 ? 1 : 0;
    }

    public static int rays(int layer) {
        return layer >= 7 ? 8 : layer >= 6 ? 6 : layer >= 5 ? 4 : layer >= 2 ? 2 : 0;
    }

    /** Копии руки и штрихи резонанса — со 2-го слоя; секретная надпись — с 3-го. */
    public static boolean afterimages(int layer) {
        return layer >= 2;
    }

    public static boolean caption(int layer) {
        return layer >= 3;
    }

    /** Визуальный масштаб: слой 8 крупнее на 15 %. */
    public static double scale(int layer) {
        return switch (Math.max(0, Math.min(8, layer))) {
            case 0 -> 0.0D;
            case 1 -> 0.5D;
            case 2 -> 0.6D;
            case 3 -> 0.7D;
            case 4 -> 0.78D;
            case 5 -> 0.86D;
            case 6 -> 0.93D;
            case 7 -> 1.0D;
            default -> 1.15D;
        };
    }

    /** Отброс: направление (ось выпуска для основной цели, от узла для остальных) и подъём. */
    public static Vec3 push(Vec3 dir, double speed, double resistance) {
        Vec3 d = dir.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : dir.normalize();
        Vec3 v = d.scale(speed);
        // Почти горизонтальная атака подбрасывает: цель отлетает дугой, а не скользит.
        if (Math.abs(d.y) < 0.5D) {
            v = v.add(0.0D, PUSH_LIFT, 0.0D);
        }
        double len = v.length();
        if (len > PUSH_MAX) {
            v = v.scale(PUSH_MAX / len);
        }
        return v.scale(Math.max(0.0D, 1.0D - resistance));
    }

    private RiverRules() {
    }
}
