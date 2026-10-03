package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;

/**
 * «Натиск Цветущей Сливы» — дальняя форма Меча Семи Цветков Сливы
 * (docs/design/techniques/seven-plum-rush-spec.md): один большой взмах рождает горизонтальный
 * розовый ураган, он летит вперёд, заворачивает цель, затем мастер мчится к ней и бьёт прямым
 * уколом, останавливаясь чуть за ней. Тики — от выпуска (начало IMPACT, тик 40 техники).
 */
public final class RushRules {

    /** Скорость переднего края, блоков/тик, и дальность (автор 02.10: «дальняя техника»). */
    public static final double SPEED = 1.15D;
    public static final double RANGE = 16.0D;
    public static final double START = 1.0D;
    /** Полуширина коридора попадания. */
    public static final double HALF_WIDTH = 1.6D;
    public static final int WRAP = 20;
    public static final int[] PULSES = {0, 8, 16};
    /**
     * Рывок «как выстрел» (автор 03.10: «натиск выглядит слабо, надо жёстче»): старт на тике
     * {@link #DASH_AT} от выпуска (4,2 с техники — присед анимации), 5 тиков пути, укол на
     * выпрямлении руки (4,5 с). Отсчёты ниже — от обволакивания, см. {@link #dash(int)}.
     */
    public static final int DASH_AT = 44;
    public static final int DASH_TICKS = 5;
    /** Конец оглушения и эффектов после укола. */
    public static final int AFTER = 12;

    /** Отброс уколом (блоков/тик по горизонтали) и подброс. */
    public static final double KNOCK = 2.2D;
    public static final double LIFT = 0.55D;
    /** Захват ураганом встречных: радиус от оси и скорость волочения к голове. */
    public static final double CATCH_WIDTH = 2.4D;
    public static final double DMG_GRAZE = 0.25D;

    public static final double DMG_FIRST = 0.6D;
    public static final double DMG_PULSE = 0.4D;
    public static final double DMG_THRUST = 1.8D;

    /** Тик рывка от обволакивания: не раньше конца вихря и не раньше приседа анимации. */
    public static int dash(int wrapSince) {
        return Math.max(WRAP + 2, DASH_AT - Math.max(0, wrapSince));
    }

    /** Тик укола от обволакивания (рука выпрямляется, когда рывок долетел). */
    public static int thrust(int wrapSince) {
        return dash(wrapSince) + DASH_TICKS + 1;
    }

    /** Конец техники от обволакивания. */
    public static int end(int wrapSince) {
        return thrust(wrapSince) + AFTER;
    }

    /** Расстояние переднего края от исходной точки в тик {@code s}. */
    public static double head(double s) {
        return Math.min(RANGE, START + SPEED * Math.max(0.0D, s));
    }

    /** Длина рукава по слою (блоки). */
    public static double length(int layer) {
        return Math.min(6.0D, 3.5D + 0.42D * Math.max(0, layer - 1));
    }

    /** Радиус рукава по слою (блоки). */
    public static double radius(int layer) {
        return Math.min(2.65D, 1.6D + 0.15D * Math.max(0, layer));
    }

    /** Множитель урона по слою (слой 7 = 1). */
    public static double power(int layer) {
        return layer <= 0 ? 0.0D : 0.3D + 0.1D * Math.min(7, layer);
    }

    /** Ось урагана: точка на расстоянии {@code d} от старта по направлению {@code f}. */
    public static Vec3 axis(Vec3 origin, Vec3 f, double d) {
        return origin.add(f.scale(d)).add(0.0D, 1.2D, 0.0D);
    }

    private RushRules() {
    }
}
