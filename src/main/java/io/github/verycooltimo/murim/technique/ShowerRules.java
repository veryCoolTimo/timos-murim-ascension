package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;

/**
 * «Ливень Цветов» (Plum Blossom Shower) — 6-я форма Меча Семи Цветков Сливы
 * (docs/design/techniques/seven-plum-shower-spec.md, рефы «plum blossom shower» shower-1…6, shower2-01…03).
 *
 * <p>Автор 03.10: «дальний, просто огромный тычок: отталкиваешься из далёкого расстояния и колешь».
 * Сжатие (тело сворачивается, у руки собирается бело-розовый узел, вокруг корпуса голубые дуги) →
 * толчок и взлёт к точке над целью → короткое зависание → косое пикирование СКВОЗЬ цель, за мастером
 * — пучок параллельных бело-розовых разрезов, расширяющийся к концу → белая вспышка внизу, мастер
 * приземляется за целью. Мастер сам и есть снаряд (у Натиска летит ураган, потом рывок).
 *
 * <p>Тики — от начала техники (T). Сервер получает {@code since} от начала IMPACT ({@link #RELEASE}).
 */
public final class ShowerRules {

    /** Вход в стойку 0–6, сжатие (ДЕРЖАТЬ) 6–22, предельное сжатие 22–28. */
    public static final int COIL = 6;
    public static final int LOAD = 22;
    /** Толчок и взлёт — начало IMPACT. */
    public static final int RELEASE = 28;
    public static final int RISE_TICKS = 6;
    /** Пикирование: от выпуска, тиков. Между взлётом и пикированием — зависание. */
    public static final int DIVE = 9;
    public static final int DIVE_TICKS = 5;
    /**
     * Тело проходит сквозь центр стоящей цели почти на высоте пояса — прямая линия упирается в землю
     * у её ступней. Поэтому после пикирования — скольжение по земле за цель (автор: «приземление за целью»).
     */
    public static final int SLIDE = DIVE + DIVE_TICKS + 1;
    public static final int SLIDE_TICKS = 5;
    public static final double SLIDE_REACH = 1.6D;
    /** Окно проверки прохода сквозь цель (сервер отстаёт от плавного рывка на пару тиков). */
    public static final int HIT_WINDOW = DIVE_TICKS + 4;
    /** Ливень разрезов по цели после прохода: через 2, 4, 6 тиков. */
    public static final int[] CUTS = {2, 4, 6};
    /** Конец техники от выпуска (= recovery JSON); анимация 3,5 с = 70 тиков. */
    public static final int END = 38;

    /** Наводка без захвата — конус взгляда до этой дальности; захват держится дальше. */
    public static final double RANGE = 14.0D;
    public static final double LOCK_RANGE = 18.0D;
    /** Высота зависания над ступнями цели и отступ от цели по линии подхода. */
    public static final double BACK = 6.0D;
    /** Выход за цель по линии пикирования, блоков от её центра (плюс полширины). */
    public static final double EXIT = 2.6D;
    /** Радиус прохода тела мастера сквозь цель. */
    public static final double PIERCE_RADIUS = 1.3D;
    /** Удар о землю (только после попадания): радиус. */
    public static final double LAND_RADIUS = 2.8D;

    /** Урон (× урон в руке × сила слоя): прокол, три разреза, удар о землю. Полный — 3,6. */
    public static final double DMG_PIERCE = 2.2D;
    public static final double DMG_CUT = 0.3D;
    public static final double DMG_LAND = 0.5D;

    /** Высота зависания над ступнями цели по слою: слой 1 — 4,4, слой 7 — 5,6 (пикирование — длинный тычок, codex 03.10). */
    public static double height(int layer) {
        return 4.2D + 0.2D * Math.min(7, Math.max(1, layer));
    }

    /** Сила слоя: слой 7 = 1, слой 8 сильнее. */
    public static double power(int layer) {
        return layer <= 0 ? 0.0D : layer >= 8 ? 1.15D : 0.3D + 0.1D * layer;
    }

    /** Число параллельных разрезов пучка по слою: 3 на первом, 12 на седьмом, 15 на восьмом (codex 03.10: не лазер). */
    public static int streaks(int layer) {
        return layer <= 0 ? 0 : layer >= 8 ? 15 : 2 + Math.round(layer * 10.0F / 7.0F);
    }

    /** Ширина пучка у конца (у начала — узкий), блоков. */
    public static double spread(int layer) {
        return layer <= 0 ? 0.0D : 0.45D + 0.11D * Math.min(8, layer);
    }

    /** Лепестки — с 3-го слоя, остаточные образы — со 2-го, голубые дуги поперёк пучка — с 5-го. */
    public static boolean petals(int layer) {
        return layer >= 3;
    }

    public static boolean afterimages(int layer) {
        return layer >= 2;
    }

    public static boolean crossArcs(int layer) {
        return layer >= 5;
    }

    /**
     * Точка зависания: над целью на {@link #height}, отступив {@link #BACK} к мастеру по горизонтали.
     * Цель ближе отступа — мастер взлетает почти вертикально. Не ниже, чем на 1,5 над стартом.
     */
    public static Vec3 apex(Vec3 origin, Vec3 targetFeet, int layer) {
        Vec3 flat = new Vec3(targetFeet.x - origin.x, 0.0D, targetFeet.z - origin.z);
        double d = flat.length();
        double y = Math.max(origin.y + 1.5D, targetFeet.y + height(layer));
        if (d < BACK + 0.5D) {
            Vec3 back = d < 1.0E-3D ? Vec3.ZERO : flat.normalize().scale(Math.min(0.0D, d - BACK));
            return new Vec3(origin.x + back.x, y, origin.z + back.z);
        }
        Vec3 dir = flat.normalize();
        return new Vec3(targetFeet.x - dir.x * BACK, y, targetFeet.z - dir.z * BACK);
    }

    /**
     * Точка выхода (ступни мастера): от точки {@code from} через центр цели, дальше на
     * {@link #EXIT} + полширины. Тело мастера (центр на 0,9 над ступнями) проходит сквозь центр цели.
     */
    public static Vec3 exit(Vec3 from, Vec3 targetCentre, double targetWidth) {
        Vec3 aim = targetCentre.subtract(0.0D, 0.9D, 0.0D);
        Vec3 dir = aim.subtract(from);
        dir = dir.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, -1.0D, 0.0D) : dir.normalize();
        return aim.add(dir.scale(EXIT + targetWidth * 0.5D));
    }

    /** Расстояние от точки {@code p} до отрезка {@code a–b}. */
    public static double segmentDistance(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double len = ab.lengthSqr();
        if (len < 1.0E-9D) {
            return p.distanceTo(a);
        }
        double t = Math.max(0.0D, Math.min(1.0D, p.subtract(a).dot(ab) / len));
        return p.distanceTo(a.add(ab.scale(t)));
    }

    private ShowerRules() {
    }
}
