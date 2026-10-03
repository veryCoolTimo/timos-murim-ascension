package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;

/**
 * «Купол Цветущей Сливы» — защитная форма Меча 24 Движений Цветущей Сливы
 * (docs/design/techniques/twenty-four-plum-dome-spec.md, рефы «7 plum blossoms sword/dome» 01–11).
 *
 * <p>Слова автора: «из базовой стойки вибрирует, затем начинает делать удары, и перед ним как
 * стволы деревьев — не один, несколько — выстраиваются; как щит — не вокруг него, а спереди».
 * Канон (гл. 196): «Непроницаемая Стена Цветка Сливы» останавливает летящие кинжалы.
 *
 * <p>Механика (решение codex 03.10): барьер посажен в МИР в начале каста — центр у ступней,
 * направление по захваченной цели или взгляду; против цели в воздухе вся форма наклоняется.
 * Стволы стоят на дуге {@link #RADIUS} в секторе ±{@link #SECTOR}°. Готовый участок дуги гасит
 * снаряды и урон, пришедший из-за дуги, пока не исчерпан пул прочности; мобы отталкиваются
 * наружу. Мастер бить не может (меч занят подпиткой), выход из-за щита — распад.
 *
 * <p>Тики — от начала техники; общие для сервера (защита) и клиента (рисунок).
 */
public final class DomeRules {

    /** Стойка: вынос меча и нарастание вибрации. */
    public static final int STANCE = 0;
    /** Взмахи, каждый сажает ствол (codex: неровный ритм). */
    public static final int[] SWINGS = {12, 15, 19, 22, 26};
    /** Ствол вырастает за столько тиков после своего взмаха. */
    public static final int GROW = 4;
    /** Фиксация позы: последние ветви смыкаются. */
    public static final int LOCK = 27;
    /** Сеть сомкнута (начало IMPACT, windup = 30). */
    public static final int RAISE = 30;
    /** Конец подпитки (IMPACT 1 + recovery 60): дальше защиты нет, распад. */
    public static final int HOLD_END = 91;
    /** Конец техники (dissipation 10). */
    public static final int END = 101;
    /** Надпись — на смыкании. */
    public static final int CAPTION = RAISE;
    /** Перелом пула: сеть рвётся и осыпается за столько тиков. */
    public static final int SHATTER = 6;

    /** Радиус дуги стволов от центра, блоков. */
    public static final double RADIUS = 2.6D;
    /** Полуугол защищаемого сектора, градусов. */
    public static final double SECTOR = 72.0D;
    /** Полуугол, в котором стоят стволы. */
    public static final double TRUNK_SPREAD = 62.0D;
    /** Мастер дальше этого от центра — вышел из-за щита. */
    public static final double LEAVE = 1.6D;
    /** Центр поворота наклонённой формы — грудь мастера. */
    public static final double PIVOT_Y = 1.2D;
    /** Высота центрального ствола на 7-м слое. */
    public static final double HEIGHT = 4.4D;

    /** Слой 0: учебный блок — урон спереди × это значение, без стены и без эффектов. */
    public static final double TRAINING_FACTOR = 0.8D;
    public static final int TRAINING_FROM = 12;
    public static final int TRAINING_TO = 52;

    /** Стволов по слою (codex): 3/3/4/5/5/6/7/7. */
    public static int trunks(int layer) {
        return switch (Math.max(0, Math.min(8, layer))) {
            case 0 -> 0;
            case 1, 2 -> 3;
            case 3 -> 4;
            case 4, 5 -> 5;
            case 6 -> 6;
            default -> 7;
        };
    }

    /** Масштаб высоты и кроны по слою. */
    public static double scale(int layer) {
        return switch (Math.max(0, Math.min(8, layer))) {
            case 0 -> 0.0D;
            case 1 -> 0.62D;
            case 2 -> 0.68D;
            case 3 -> 0.76D;
            case 4 -> 0.84D;
            case 5 -> 0.9D;
            case 6 -> 0.95D;
            case 7 -> 1.0D;
            default -> 1.06D;
        };
    }

    /** Плотность эффектов (доля полного рисунка 7-го слоя). */
    public static double density(int layer) {
        return switch (Math.max(0, Math.min(8, layer))) {
            case 0 -> 0.0D;
            case 1 -> 0.15D;
            case 2 -> 0.25D;
            case 3 -> 0.4D;
            case 4 -> 0.55D;
            case 5 -> 0.7D;
            case 6 -> 0.85D;
            case 7 -> 1.0D;
            default -> 1.2D;
        };
    }

    public static boolean petals(int layer) {
        return layer >= 3;
    }

    /** Остаточные контуры вибрирующего клинка — со 2-го слоя. */
    public static boolean afterimages(int layer) {
        return layer >= 2;
    }

    /** Пул прочности: максимум здоровья × (0,4 + 0,2 × слой) — урон, который сеть выдержит. */
    public static double pool(int layer, double maxHealth) {
        return layer <= 0 ? 0.0D : maxHealth * (0.4D + 0.2D * Math.min(8, layer));
    }

    /** Угол ствола {@code k} от оси (градусы, + — вправо): равномерно по дуге, с лёгким разбросом. */
    public static double trunkAngle(int layer, int k) {
        int n = trunks(layer);
        if (n <= 1) {
            return 0.0D;
        }
        double jitter = 4.0D * Math.sin(k * 2.3D + n);
        return -TRUNK_SPREAD + 2.0D * TRUNK_SPREAD * k / (n - 1) + (k == 0 || k == n - 1 ? 0.0D : jitter);
    }

    /** Высота ствола: купол — центр выше краёв. */
    public static double trunkHeight(int layer, int k) {
        double a = trunkAngle(layer, k) / TRUNK_SPREAD;
        return HEIGHT * scale(layer) * (1.0D - 0.24D * a * a) * (1.0D + 0.05D * Math.sin(k * 1.7D)) * (bearing(layer, k) ? 1.0D : 0.8D);
    }

    /**
     * Несущий ствол (codex 03.10: в d04 доминируют 3–4 крупных ствола, не 7 равных): чётные от
     * края — несущие, между ними — тоньше и ниже.
     */
    public static boolean bearing(int layer, int k) {
        int n = trunks(layer);
        return n <= 3 || k % 2 == 0;
    }

    /** Порядок посадки: центр, потом вразнобой к краям. Возвращает индекс ствола для {@code order}-го. */
    public static int plantOrder(int layer, int order) {
        int n = trunks(layer);
        // 0 → центр, дальше чередуем стороны: +1, −1, +2, −2… (правая сторона первой — неровно).
        int c = n / 2;
        int seen = 0;
        for (int step = 0; step <= n; step++) {
            for (int sign : step == 0 ? new int[] {0} : new int[] {1, -1}) {
                int k = c + sign * step;
                if (k < 0 || k >= n) {
                    continue;
                }
                if (seen++ == order) {
                    return k;
                }
            }
        }
        return Math.max(0, Math.min(n - 1, order));
    }

    /** Тик, на котором сажается ствол {@code k}: последние взмахи сажают по два. */
    public static int plantTick(int layer, int k) {
        int n = trunks(layer);
        for (int order = 0; order < n; order++) {
            if (plantOrder(layer, order) == k) {
                int swing = Math.min(SWINGS.length - 1, (int) Math.floor(order * (double) SWINGS.length / n));
                // Второй ствол того же взмаха — на тик позже.
                int same = 0;
                for (int o = 0; o < order; o++) {
                    if (Math.min(SWINGS.length - 1, (int) Math.floor(o * (double) SWINGS.length / n)) == swing) {
                        same++;
                    }
                }
                return SWINGS[swing] + same;
            }
        }
        return SWINGS[SWINGS.length - 1];
    }

    /** Ствол стоит (готов защищать) к тику {@code tick}. */
    public static boolean ready(int layer, int k, int tick) {
        return tick >= plantTick(layer, k) + GROW;
    }

    /**
     * Защищён ли направление {@code angle} (градусы от оси) к тику {@code tick}: ближайший к нему
     * ствол стоит. Края сектора закрывают крайние стволы.
     */
    public static boolean covered(int layer, double angle, int tick) {
        int n = trunks(layer);
        if (n == 0 || Math.abs(angle) > SECTOR || tick >= HOLD_END) {
            return false;
        }
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            double d = Math.abs(angle - trunkAngle(layer, k));
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        return ready(layer, best, tick);
    }

    /**
     * Локальная система барьера: центр — ступни мастера на старте, {@code forward} — горизонталь
     * направления, {@code tilt} — наклон всей формы к цели в небе (0 — стволы из земли).
     * Локальные оси: x — вправо, y — вверх по стволу, z — вперёд.
     */
    public record Frame(Vec3 pivot, Vec3 forward, double tilt) {
        public Frame {
            Vec3 f = new Vec3(forward.x, 0.0D, forward.z);
            forward = f.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : f.normalize();
            tilt = Math.max(0.0D, Math.min(60.0D, tilt));
        }

        /** Вправо: при взгляде (−sin, cos) правая рука смотрит в (−cos, −sin). */
        public Vec3 side() {
            return new Vec3(-forward.z, 0.0D, forward.x);
        }

        public Vec3 up() {
            double a = Math.toRadians(tilt);
            return new Vec3(0.0D, Math.cos(a), 0.0D).subtract(forward.scale(Math.sin(a)));
        }

        public Vec3 ahead() {
            double a = Math.toRadians(tilt);
            return forward.scale(Math.cos(a)).add(0.0D, Math.sin(a), 0.0D);
        }

        Vec3 centre() {
            return pivot.add(0.0D, PIVOT_Y, 0.0D);
        }

        /** Мировая точка по локальным (x вправо, y вверх, z вперёд от центра у ступней). */
        public Vec3 world(double x, double y, double z) {
            return centre().add(side().scale(x)).add(up().scale(y - PIVOT_Y)).add(ahead().scale(z));
        }

        /** Локальные координаты мировой точки: {x, y, z}. */
        public double[] local(Vec3 p) {
            Vec3 d = p.subtract(centre());
            return new double[] {d.dot(side()), d.dot(up()) + PIVOT_Y, d.dot(ahead())};
        }

        /** Основание ствола по углу (градусы) на дуге. */
        public Vec3 base(double angleDeg) {
            double a = Math.toRadians(angleDeg);
            return world(Math.sin(a) * RADIUS, 0.0D, Math.cos(a) * RADIUS);
        }
    }

    /** Угол точки в плоскости барьера (градусы от оси, + — вправо) и её удаление от центра. */
    public static double angle(double[] l) {
        return Math.toDegrees(Math.atan2(l[0], l[2]));
    }

    public static double rho(double[] l) {
        return Math.sqrt(l[0] * l[0] + l[2] * l[2]);
    }

    /** Точка снаружи дуги и в полосе высоты барьера: отсюда пришедшее гасится. */
    public static boolean outside(int layer, double[] l) {
        double h = HEIGHT * scale(layer) + 1.2D;
        return rho(l) > RADIUS - 0.4D && Math.abs(angle(l)) <= SECTOR && l[1] > -1.0D && l[1] < h + 1.5D;
    }

    /**
     * Пересекает ли отрезок {@code a → b} дугу барьера снаружи внутрь; возвращает долю пути до
     * пересечения или −1. Высота — в полосе стволов (крона загибается внутрь — запас сверху).
     */
    public static double crossing(int layer, Frame f, Vec3 a, Vec3 b, int tick) {
        double[] la = f.local(a);
        double[] lb = f.local(b);
        double ra = rho(la);
        double rb = rho(lb);
        if (ra < RADIUS || rb >= RADIUS) {
            return -1.0D;
        }
        // Плоская интерполяция по радиусу хватает: шаг снаряда много меньше радиуса дуги.
        double u = (ra - RADIUS) / Math.max(1.0E-6D, ra - rb);
        double[] l = {la[0] + (lb[0] - la[0]) * u, la[1] + (lb[1] - la[1]) * u, la[2] + (lb[2] - la[2]) * u};
        double h = HEIGHT * scale(layer) + 1.0D;
        if (l[1] < -0.6D || l[1] > h || !covered(layer, angle(l), tick)) {
            return -1.0D;
        }
        return u;
    }

    /** Точка контакта на дуге для удара из {@code source}: туда приходит вспышка. */
    public static Vec3 contact(int layer, Frame f, Vec3 source) {
        double[] l = f.local(source);
        double a = Math.toRadians(Math.max(-SECTOR, Math.min(SECTOR, angle(l))));
        double y = Math.max(0.3D, Math.min(HEIGHT * scale(layer), l[1]));
        return f.world(Math.sin(a) * RADIUS, y, Math.cos(a) * RADIUS);
    }

    private DomeRules() {
    }
}
