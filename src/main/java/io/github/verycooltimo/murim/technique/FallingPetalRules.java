package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;

/**
 * «Меч Падающего Цветка» (낙화검, docs/design/techniques/falling-petal-sword-spec.md, рефы
 * reference/techniques/falling petal sword/): низкая стойка → вход-рывок к цели → пять ударов,
 * мастер обходит цель слева за спину, «меч возвращается быстрее, чем бьёт» → удар в горло →
 * остановка сразу за целью. Общие числа для сервера (урон, рывки) и клиента (следы, позы).
 *
 * <p>Тики {@code since} — от начала IMPACT (тик 10 техники: 10 тиков стойки).
 */
public final class FallingPetalRules {

    /** Стойка (WINDUP в json): поза, удержание, подпись. */
    public static final int STANCE = 10;
    /** Последний тик шкалы от IMPACT (всего техника 84 тика). */
    public static final int END = 73;

    /** Тики контакта пяти ударов: вход, повтор, плечо, низкий проход, горло. */
    public static final int[] STRIKES = {14, 22, 34, 46, 62};
    /** Длительность режущего движения до контакта (тики). */
    public static final int CUT = 4;
    /** Быстрый возврат после удара (тики) — вдвое короче реза. */
    public static final int RETURN = 2;
    /** Удары, после которых клинок возвращается (вход, плечо, низкий проход). */
    public static final boolean[] RETURNS = {true, false, true, true, false};

    /** Доли урона в руке по ударам (сумма 3,0). */
    public static final double[] DAMAGE = {0.40D, 0.45D, 0.65D, 0.45D, 1.05D};

    /** Дальность наводки и сопровождения живой цели. */
    public static final double RANGE = 16.0D;
    /** Дальность контакта клинка от центра мастера до края хитбокса цели. */
    public static final double REACH = 2.6D;
    /** Удар 5 фиксирует направление за 4 тика до контакта. */
    public static final int FINALE_LOCK = 58;

    /** Рывки: тик начала, длительность. Точки — {@link #point}. */
    public static final int[][] DASHES = {
            {0, 12},   // вход к передней левой четверти
            {16, 5},   // вдоль левого края цели
            {23, 3},   // шаг-разворот (ref4), затем удержание 26–29
            {30, 4},   // к задней левой четверти — плечо
            {41, 5},   // низкий проход за спиной в заднюю правую четверть (ref5)
            {52, 3},   // вплотную для финала (ref7)
            {63, 5}};  // выход сразу за спину и остановка

    /** Шаг-разворот с кольцом у ног — индекс рывка. */
    public static final int PIVOT = 2;
    /** Колени цели после удара 4 (ref6). */
    public static final int KNEEL = 49;

    /** Нет цели: короткий натиск вперёд на 3 блока и один взмах. */
    public static final double LUNGE = 3.0D;
    public static final int LUNGE_TICKS = 8;
    public static final int LUNGE_CUT = 8;

    /**
     * Точка рывка {@code k} в системе цели: {@code t} — ноги цели, {@code f} — исходная ось входа
     * (от мастера к цели), {@code half} — половина ширины хитбокса цели. Левая сторона —
     * поворот {@code f} на 90° против часовой (вид сверху).
     */
    public static Vec3 point(int k, Vec3 t, Vec3 f, double half) {
        Vec3 s = left(f);
        return switch (k) {
            case 0 -> t.add(f.scale(-(1.5D + half))).add(s.scale(0.45D));
            case 1 -> t.add(f.scale(-0.55D)).add(s.scale(1.25D + half));
            case 2 -> t.add(f.scale(0.15D)).add(s.scale(1.45D + half));
            case 3 -> t.add(f.scale(0.95D + half)).add(s.scale(0.95D));
            case 4 -> t.add(f.scale(1.15D + half)).add(s.scale(-(0.85D + half)));
            case 5 -> t.add(f.scale(0.8D + half)).add(s.scale(-0.45D));
            default -> t.add(f.scale(1.0D + half)).add(s.scale(-0.1D));
        };
    }

    public static Vec3 left(Vec3 f) {
        return new Vec3(f.z, 0.0D, -f.x);
    }

    /** Множитель урона по слою: слой 0 — ученический удар, слой 7 — полный. */
    public static double power(int layer) {
        return 0.55D + 0.45D * Math.min(7, Math.max(0, layer)) / 7.0D;
    }

    /** Номер удара, чей контакт в тик {@code since}, или −1. */
    public static int strikeAt(int since) {
        for (int i = 0; i < STRIKES.length; i++) {
            if (STRIKES[i] == since) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Траектория острия удара {@code k} в системе мастера: {θ0, θ1 (градусы, + — влево),
     * h0, h1 (высота над поясом), радиус}. Асимметрично: вход сверху справа вниз влево,
     * повтор горизонтально, плечо обратным ходом слева направо, низкий проход широко у земли,
     * горло — короткий рез на уровне шеи.
     */
    public static final double[][] ARCS = {
            {-75.0D, 65.0D, 1.15D, -0.55D, 1.45D},
            {-60.0D, 75.0D, 0.35D, 0.2D, 1.5D},
            {42.0D, -32.0D, 0.95D, 0.15D, 1.2D},
            {-85.0D, 85.0D, -0.6D, -0.85D, 1.7D},
            {-35.0D, 40.0D, 0.6D, 0.75D, 1.15D}};

    /** Возврат: короче и ближе к телу, обратным ходом к правому боку. */
    public static double[] returnArc(int k) {
        double[] a = ARCS[k];
        double from = a[1] * 0.7D;
        return new double[] {from, from - Math.signum(a[1] - a[0]) * 55.0D, a[3] * 0.6D, 0.3D, a[4] * 0.6D};
    }

    /**
     * Острие клинка: {@code base} — пояс мастера, {@code f} — направление на цель, {@code arc} —
     * {@link #ARCS}, {@code p} — доля движения 0..1 (за пределами — продолжение по инерции).
     */
    public static Vec3 tip(Vec3 base, Vec3 f, double[] arc, double p) {
        double e = p <= 0.0D ? 0.0D : p >= 1.0D ? 1.0D + (p - 1.0D) * 0.6D : 1.0D - Math.pow(1.0D - p, 2.2D);
        double th = Math.toRadians(arc[0] + (arc[1] - arc[0]) * e);
        Vec3 s = left(f);
        double h = arc[2] + (arc[3] - arc[2]) * Math.min(1.2D, e);
        double r = arc[4] * (0.82D + 0.18D * Math.sin(Math.PI * Math.min(1.0D, e)));
        return base.add(f.scale(Math.cos(th) * r)).add(s.scale(Math.sin(th) * r)).add(0.0D, h, 0.0D);
    }

    private FallingPetalRules() {
    }
}
