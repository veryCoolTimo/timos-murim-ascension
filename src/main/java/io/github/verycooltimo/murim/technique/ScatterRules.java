package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;

/**
 * «Рассеяние Цветущей Сливы» — форма Меча 24 Движений Цветущей Сливы (рефы «24 blossoms technique/full»
 * full-01…19 + DESCRIPTIONS.md, спецификация docs/design/techniques/twenty-four-plum-scatter-spec.md).
 *
 * <p>Автор 03.10: мастер концентрируется — вибрирует остаточным образом, из него торчат клоны, дёргается
 * и раздвигается; стоит — целый синий огонь; приходят лепестки; резко прыгает в сторону, с ним прыгают
 * клоны, выстреливая из лепестков; КАЖДЫЙ клон наносит настоящий урон (в отличие от Казни Семи Цветков,
 * где клоны — иллюзия); в конце — куча эффектов (Plum Blossom Scatter).
 *
 * <p>Клон проходит хордами сквозь сферу вокруг цели: удар j — отрезок Q_j → Q_{j+1}, между ударами он
 * висит в конечной точке и разворачивается. Шесть клонов по четыре удара — 24 удара на 7-м слое.
 * Урон каждого удара — по отрезку пути этого клона (сервер), владелец — мастер.
 *
 * <p>Тики — от начала техники (T). Сервер получает {@code since} от начала IMPACT ({@link #RELEASE}),
 * клиент считает от события START и сверяется по пакету прыжка. Общие для сервера и клиента.
 */
public final class ScatterRules {

    /** Стойка: поза за 8 тиков. */
    public static final int STANCE = 8;
    /** Вибрация: остаточный образ, из тела торчат копии (full-02, full-05). */
    public static final int VIBRATE = 8;
    /** Дёргается и раздвигается: копии рывками расходятся и возвращаются (full-06). */
    public static final int SPLIT = 28;
    public static final int SPLIT_BACK = 36;
    /** Стоит в полном синем огне (full-04, full-07, full-08). */
    public static final int FIRE = 40;
    /** Лепестки приходят издалека и стягиваются в шесть скоплений вокруг мастера (full-08, full-09). */
    public static final int PETALS = 44;
    public static final int CLUSTER = 62;
    /** Прыжок в сторону, клоны выстреливают из лепестков (full-10, full-11). Начало IMPACT. */
    public static final int RELEASE = 72;
    public static final int JUMP_TICKS = 4;
    /** Первый удар первого клона. */
    public static final int STRIKE0 = 84;
    /** Период ударов одного клона (тиков между его ударами). */
    public static final int CYCLE = 12;
    /** Проход хорды сквозь цель. */
    public static final int PASS = 4;
    /** Завершающий взмах мастера (full-18): рывок сквозь цель. */
    public static final int FINAL = 136;
    public static final int FINAL_TICKS = 6;
    /** Рассеяние (full-19): вихрь, импульс по области. */
    public static final int SCATTER = 144;
    public static final int END = 180;

    /** Радиус сферы, по которой клоны заходят на цель. */
    public static final double SPHERE = 2.6D;
    /** Полуширина клинка: отрезок пути клона, раздутый на столько, задевает хитбокс. */
    public static final double BLADE = 1.0D;
    public static final double RANGE = 16.0D;
    public static final double SCATTER_RADIUS = 5.0D;

    /** Урон (× урон в руке × сила слоя): удар клона, взмах мастера, рассеяние. */
    public static final double DMG_STROKE = 0.2D;
    public static final double DMG_FINAL = 1.0D;
    public static final double DMG_SCATTER = 1.5D;
    public static final double DMG_TRAINING = 1.0D;

    /** Клонов по слою: 1…6. */
    public static int clones(int layer) {
        return layer <= 0 ? 0 : Math.min(6, layer);
    }

    /** Ударов на клона: 2 до 3-го слоя, 3 на 4–6, 4 с 7-го (6 × 4 = 24). */
    public static int strokes(int layer) {
        return layer <= 0 ? 0 : layer <= 3 ? 2 : layer <= 6 ? 3 : 4;
    }

    public static boolean finalSwing(int layer) {
        return layer >= 4;
    }

    public static boolean scatter(int layer) {
        return layer >= 6;
    }

    public static boolean afterimages(int layer) {
        return layer >= 2;
    }

    public static boolean petals(int layer) {
        return layer >= 3;
    }

    public static double power(int layer) {
        return layer <= 0 ? 0.0D : layer >= 8 ? 1.15D : 0.3D + 0.1D * layer;
    }

    public static double density(int layer) {
        return switch (Math.max(0, Math.min(8, layer))) {
            case 0 -> 0.0D;
            case 1 -> 0.12D;
            case 2 -> 0.22D;
            case 3 -> 0.35D;
            case 4 -> 0.5D;
            case 5 -> 0.65D;
            case 6 -> 0.82D;
            case 7 -> 1.0D;
            default -> 1.2D;
        };
    }

    public static double scale(int layer) {
        return layer <= 0 ? 0.0D : layer >= 8 ? 1.08D : 0.55D + 0.064D * layer;
    }

    /** Начало удара {@code j} клона {@code i}: удары всех клонов чередуются равномерно. */
    public static double strokeStart(int layer, int i, int j) {
        int n = Math.max(1, clones(layer));
        return STRIKE0 + (j * n + i) * (double) CYCLE / n;
    }

    /** Тик, когда удар проходит центр цели: сервер проверяет весь отрезок удара. */
    public static int strokeHit(int layer, int i, int j) {
        return (int) Math.floor(strokeStart(layer, i, j) + PASS * 0.5D);
    }

    /** Направления захода: шесть клонов со своих сторон, удары чередуют высоты (full-15). */
    private static final double[] ELEV = {10.0D, 40.0D, -10.0D, 25.0D, 55.0D, 0.0D};
    private static final double[] YAW0 = {-30.0D, 90.0D, -150.0D, 30.0D, 150.0D, -90.0D};

    /**
     * Точка {@code k} сферы клона {@code i} (k = 0 — заход, k = j+1 — конец удара j). Каждая следующая —
     * напротив предыдущей, повёрнутая на 20–26° вокруг меняющейся оси: хорда проходит в ≤0,6 блока
     * от центра, а плоскость удара у каждого следующего — своя. {@code base} — рысканье «цель → мастер».
     */
    public static Vec3 sphere(Vec3 centre, double base, int i, int k, boolean grounded) {
        double yaw = Math.toRadians(YAW0[i % 6]) + base;
        double e0 = Math.toRadians(grounded ? Math.min(35.0D, ELEV[i % 6]) : ELEV[i % 6]);
        Vec3 d = new Vec3(Math.cos(yaw) * Math.cos(e0), Math.sin(e0), Math.sin(yaw) * Math.cos(e0));
        d = clampDown(d, grounded);
        for (int q = 0; q < k; q++) {
            // Ось поворота чередуется: вертикаль (смена стороны) и горизонталь (смена высоты).
            Vec3 h = new Vec3(-d.z, 0.0D, d.x);
            Vec3 w = (q + i) % 2 == 0 ? new Vec3(0.0D, 1.0D, 0.0D) : h.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : h.normalize();
            double phi = Math.toRadians(20.0D + 6.0D * ((i + q) % 2)) * ((i + q / 2) % 2 == 0 ? 1.0D : -1.0D);
            d = rotate(d, w, phi).scale(-1.0D);
            d = clampDown(d, grounded);
        }
        double r = SPHERE * (1.0D + 0.06D * ((i + k) % 3));
        return centre.add(d.scale(r));
    }

    /** На земле точки не уходят под ступни цели: не ниже −20° от центра корпуса (~1 блок). */
    private static Vec3 clampDown(Vec3 d, boolean grounded) {
        double min = Math.sin(Math.toRadians(-20.0D));
        if (!grounded || d.y >= min) {
            return d;
        }
        Vec3 h = new Vec3(d.x, 0.0D, d.z);
        h = h.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : h.normalize();
        return h.scale(Math.cos(Math.asin(min))).add(0.0D, min, 0.0D);
    }

    /** Поворот вектора вокруг единичной оси (формула Родрига). */
    private static Vec3 rotate(Vec3 v, Vec3 axis, double a) {
        double c = Math.cos(a);
        double s = Math.sin(a);
        return v.scale(c).add(axis.cross(v).scale(s)).add(axis.scale(axis.dot(v) * (1.0D - c)));
    }

    /**
     * Положение (центр корпуса) клона {@code i} в тик {@code t}: выход из скопления лепестков и прыжок
     * вместе с мастером → полёт к точке захода → удары-хорды → зависание между ними → после последнего
     * удара висит в конце хорды до рассеяния.
     *
     * @param cluster скопление лепестков, из которого клон выходит (центр корпуса)
     * @param jump    смещение прыжка мастера
     */
    public static Vec3 clone(Vec3 cluster, Vec3 jump, Vec3 centre, double base, int layer, int i, double t, boolean grounded) {
        double out = exit(i);
        if (t <= out) {
            return cluster;
        }
        double jEnd = out + JUMP_TICKS;
        Vec3 landed = cluster.add(jump.scale(1.15D + 0.1D * i));
        if (t <= jEnd) {
            double k = (t - out) / JUMP_TICKS;
            double e = 1.0D - (1.0D - k) * (1.0D - k);
            return cluster.lerp(landed, e).add(0.0D, 1.1D * Math.sin(Math.PI * k), 0.0D);
        }
        int m = strokes(layer);
        double s0 = strokeStart(layer, i, 0);
        Vec3 q0 = sphere(centre, base, i, 0, grounded);
        if (t <= s0) {
            double k = (t - jEnd) / Math.max(1.0D, s0 - jEnd);
            double e = k * k * (3.0D - 2.0D * k);
            return landed.lerp(q0, e).add(0.0D, 1.4D * Math.sin(Math.PI * k), 0.0D);
        }
        for (int j = 0; j < m; j++) {
            double s = strokeStart(layer, i, j);
            double next = j + 1 < m ? strokeStart(layer, i, j + 1) : Double.MAX_VALUE;
            if (t < s || t >= next) {
                continue;
            }
            Vec3 a = sphere(centre, base, i, j, grounded);
            Vec3 b = sphere(centre, base, i, j + 1, grounded);
            if (t <= s + PASS) {
                // Рывок сквозь цель: резкий срыв, торможение у конца хорды.
                double k = (t - s) / PASS;
                double e = 1.0D - Math.pow(1.0D - k, 2.2D);
                return a.lerp(b, e);
            }
            // Зависание с разворотом: лёгкое всплытие.
            double k = Math.min(1.0D, (t - s - PASS) / 4.0D);
            return b.add(0.0D, 0.25D * Math.sin(Math.PI * k), 0.0D);
        }
        return sphere(centre, base, i, m, grounded);
    }

    /** Выход клона {@code i} из своего узла: по одному через 2 тика (codex 03.10 — шесть отдельных выходов). */
    public static int exit(int i) {
        return RELEASE + 2 * i;
    }

    /** Отрезок удара {@code j} клона {@code i}: от точки захода до конца хорды. */
    public static Vec3[] stroke(Vec3 centre, double base, int i, int j, boolean grounded) {
        return new Vec3[] {sphere(centre, base, i, j, grounded), sphere(centre, base, i, j + 1, grounded)};
    }

    /** Шесть скоплений лепестков вокруг мастера (full-09): 0° — к цели, по 60°, на высоте груди. */
    public static Vec3 cluster(Vec3 feet, double towardYaw, int i) {
        double a = towardYaw + Math.toRadians(60.0D * i + 30.0D);
        return feet.add(Math.cos(a) * 1.6D, 1.05D + 0.25D * ((i * 7) % 3) / 2.0D, Math.sin(a) * 1.6D);
    }

    /**
     * Прыжок в сторону (full-10): 3 блока вбок от линии на цель и вперёд, но не ближе 4 блоков к ней.
     * {@code side} — +1 / −1.
     */
    public static Vec3 jump(Vec3 feet, Vec3 target, int side) {
        Vec3 to = new Vec3(target.x - feet.x, 0.0D, target.z - feet.z);
        double dist = to.length();
        Vec3 f = dist < 1.0E-3D ? new Vec3(0.0D, 0.0D, 1.0D) : to.scale(1.0D / dist);
        Vec3 s = new Vec3(-f.z, 0.0D, f.x).scale(side);
        double fwd = Math.max(-1.0D, Math.min(3.0D, dist - 4.5D));
        return s.scale(3.0D).add(f.scale(fwd));
    }

    private ScatterRules() {
    }
}
