package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;

/**
 * «Море Цветущей Сливы» — защитная реакция Меча 24 Движений Цветущей Сливы
 * (docs/design/techniques/twenty-four-plum-sea-spec.md; первая техника без кадров-рефов,
 * метод docs/design/research/technique-design-grammar.md §5–6.1).
 *
 * <p>Канон (гл. 195): «Десятки цветков сливы расцвели в одно мгновение и обернулись вокруг летящих
 * лезвий… бескрайнее море… лезвия зарывались в такое море», оружие падает на землю; цена — «кровь
 * с губ». Стойка → бутон на острие → море растёт кольцами от острия → игрок ведёт сектор взглядом,
 * каждый снаряд в секторе обвивает свой цветок и стаскивает к земле (до 4 разом) → R удержан —
 * море держится дальше за сердце в секунду → «тихий мираж»: цветы тают, как снег.
 *
 * <p>Тики — от начала техники; общие для сервера (сектор, захват) и клиента (рисунок).
 */
public final class SeaRules {

    /** Стойка: вход в позу 0–8. */
    public static final int STANCE = 8;
    /** Бутон на острие раскрывается за 4 тика. */
    public static final int BUD = 8;
    public static final int BUD_OPEN = 4;
    /** Раскрытие (начало IMPACT, windup = 14): медленный проход клинка по сектору. */
    public static final int RELEASE = 14;
    /** Прилив: море растёт от острия до полного радиуса. */
    public static final int TIDE_END = 30;
    /** Надпись — когда море выросло. */
    public static final int CAPTION = TIDE_END;
    /** Конец основного удержания: дальше — только продление за сердце (с 6-го слоя). */
    public static final int HOLD_END = 90;
    /** Предел продления. */
    public static final int EXTEND_MAX = 60;
    /** Шаг оплаты продления: каждые 20 тиков — 1 сердце. */
    public static final int EXTEND_STEP = 20;
    /** Здоровье, списываемое за шаг продления (1 сердце). */
    public static final float EXTEND_HEALTH = 2.0F;
    /** Ниже этого здоровья продлевать нельзя (2 сердца). */
    public static final float EXTEND_FLOOR = 4.0F;
    /** Тихий мираж: цветы тают за столько тиков, потом техника кончается. */
    public static final int MELT = 20;
    /** Последний возможный тик техники (длина JSON-фаз). */
    public static final int END = HOLD_END + EXTEND_MAX + MELT;

    /** Полуугол сектора, градусов. */
    public static final double SECTOR = 50.0D;
    /** Скорость поворота оси сектора за тик, градусов. */
    public static final double TURN = 4.0D;
    /** Захваченный снаряд: скорость ×BRAKE за тик в фазе висения. */
    public static final double BRAKE = 0.45D;
    /** Конец медленного прохода клинка по сектору (14 → 24), поза держится до {@link #TIDE_END}. */
    public static final int SWEEP_END = 24;
    /** Продление: доля стартовой стоимости ци за шаг. */
    public static final double EXTEND_QI = 0.25D;
    /** Огненный шар гаснет в коконе через столько тиков. */
    public static final int SNUFF = 6;
    /** Слот захвата освобождается не позже этого. */
    public static final int HOLD_PROJECTILE = 20;

    /** Слой 0: учебное парирование — узкий сектор, короткий радиус, окно тиков. */
    public static final double TRAINING_SECTOR = 30.0D;
    public static final double TRAINING_RADIUS = 3.0D;
    public static final int TRAINING_TO = 60;

    /** Одновременных захватов по слою: 1/1/1/2/2/3/3/4/4. */
    public static int captures(int layer) {
        return switch (Math.max(0, Math.min(8, layer))) {
            case 0, 1, 2 -> 1;
            case 3, 4 -> 2;
            case 5, 6 -> 3;
            default -> 4;
        };
    }

    /** Радиус сектора по слою, блоков. */
    public static double radius(int layer) {
        return switch (Math.max(0, Math.min(8, layer))) {
            case 0 -> TRAINING_RADIUS;
            case 1 -> 3.5D;
            case 2 -> 4.0D;
            case 3 -> 5.0D;
            case 4 -> 5.5D;
            case 5 -> 6.0D;
            case 6 -> 6.5D;
            case 7 -> 7.0D;
            default -> 8.0D;
        };
    }

    /** Полуугол сектора по слою: учебный уже. */
    public static double sector(int layer) {
        return layer <= 0 ? TRAINING_SECTOR : SECTOR;
    }

    /** Продление за сердце — с 6-го слоя. */
    public static boolean extendable(int layer) {
        return layer >= 6;
    }

    /** Лепестки, море, кокон, надпись, аура — с 3-го слоя. */
    public static boolean petals(int layer) {
        return layer >= 3;
    }

    /** Остаточный образ руки на захвате — со 2-го слоя. */
    public static boolean afterimages(int layer) {
        return layer >= 2;
    }

    /** Волна торможения: от точки захвата по полю расходится поклон цветов — с 4-го слоя. */
    public static boolean bowWave(int layer) {
        return layer >= 4;
    }

    /** Бутоны-слоты на клинке и подъём цветов столбом к высоким снарядам — с 5-го слоя. */
    public static boolean slotBuds(int layer) {
        return layer >= 5;
    }

    /** Плотность моря по слою (доля от полного рисунка слоя 7). */
    public static double density(int layer) {
        return switch (Math.max(0, Math.min(8, layer))) {
            case 0 -> 0.0D;
            case 1 -> 0.10D;
            case 2 -> 0.20D;
            case 3 -> 0.30D;
            case 4 -> 0.50D;
            case 5 -> 0.65D;
            case 6 -> 0.80D;
            case 7 -> 1.0D;
            default -> 1.25D;
        };
    }

    /** Масштаб цветов по слою. */
    public static double scale(int layer) {
        return switch (Math.max(0, Math.min(8, layer))) {
            case 0 -> 0.0D;
            case 1, 2 -> 0.6D;
            case 3 -> 0.75D;
            case 4 -> 0.82D;
            case 5 -> 0.88D;
            case 6 -> 0.94D;
            case 7 -> 1.0D;
            default -> 1.12D;
        };
    }

    /** Доля радиуса, до которой море выросло к тику {@code t} (кольцами от острия). */
    public static double grown(double t) {
        if (t < RELEASE) {
            return 0.0D;
        }
        double k = Math.min(1.0D, (t - RELEASE) / (double) (TIDE_END - RELEASE));
        // Медленно в начале («один, два»), потом быстро — «бескрайнее море».
        return k * k * (3.0D - 2.0D * k);
    }

    /** Поворот оси сектора к желаемой не быстрее {@link #TURN}° за тик (по сфере). */
    public static Vec3 turn(Vec3 axis, Vec3 want) {
        if (want.lengthSqr() < 1.0E-8D) {
            return axis;
        }
        want = want.normalize();
        if (axis.lengthSqr() < 1.0E-8D) {
            return want;
        }
        axis = axis.normalize();
        double cos = Math.max(-1.0D, Math.min(1.0D, axis.dot(want)));
        double angle = Math.acos(cos);
        double max = Math.toRadians(TURN);
        if (angle <= max) {
            return want;
        }
        // Сферическая интерполяция на долю max/angle.
        double k = max / angle;
        double s = Math.sin(angle);
        if (s < 1.0E-6D) {
            return want;
        }
        return axis.scale(Math.sin((1.0D - k) * angle) / s).add(want.scale(Math.sin(k * angle) / s)).normalize();
    }

    /**
     * Точка {@code pos} в секторе: от груди мастера {@code chest} в конусе вокруг {@code axis}
     * с полууглом {@link #sector} (3D: снаряды сверху тоже), ближе {@link #radius} × доля прилива.
     */
    public static boolean inCone(int layer, Vec3 chest, Vec3 axis, Vec3 pos, double grown) {
        Vec3 to = pos.subtract(chest);
        double d = to.length();
        if (d < 0.3D || d > radius(layer) * Math.max(0.15D, grown)) {
            return false;
        }
        return to.normalize().dot(axis.normalize()) >= Math.cos(Math.toRadians(sector(layer)));
    }

    /**
     * Снаряд входит в сектор: отрезок пути за тик (две точки и середина) задевает конус, снаряд летит
     * к мастеру и его прямая проходит ближе {@link #THREAT} от груди — не пролёт мимо.
     */
    public static boolean entering(int layer, Vec3 chest, Vec3 axis, Vec3 pos, Vec3 vel, double grown) {
        if (vel.lengthSqr() < 1.0E-4D || vel.dot(chest.subtract(pos)) <= 0.0D || miss(chest, pos, vel) > THREAT) {
            return false;
        }
        Vec3 next = pos.add(vel);
        return inCone(layer, chest, axis, pos, grown) || inCone(layer, chest, axis, pos.lerp(next, 0.5D), grown)
                || inCone(layer, chest, axis, next, grown);
    }

    /**
     * Скорость захваченного снаряда на следующий тик (по codex: сигнатурный кадр нужен честный):
     * {@link #HANG} тика торможения ×{@link #BRAKE} — снаряд почти висит в обвивке; дальше падение
     * с ускорением {@link #FALL_ACCEL} и остаточным сносом по горизонтали.
     */
    public static Vec3 brake(Vec3 vel, int age) {
        if (age < HANG) {
            return new Vec3(vel.x * BRAKE, vel.y * BRAKE, vel.z * BRAKE);
        }
        return new Vec3(vel.x * 0.6D, Math.min(0.0D, vel.y) - FALL_ACCEL, vel.z * 0.6D);
    }

    /** Тиков висения в обвивке. */
    public static final int HANG = 3;
    /** Ускорение падения после висения, блоков/тик². */
    public static final double FALL_ACCEL = 0.08D;
    /** Прямая снаряда должна пройти ближе этого от груди мастера (не пролёт мимо). */
    public static final double THREAT = 2.0D;
    /** Метка захвата отменяет попадание по сущностям не дольше этого. */
    public static final int MARK_TICKS = 40;

    /** Ближайшее расстояние прямой {@code pos + s·vel} (s ≥ 0) до точки {@code chest}. */
    public static double miss(Vec3 chest, Vec3 pos, Vec3 vel) {
        if (vel.lengthSqr() < 1.0E-8D) {
            return pos.distanceTo(chest);
        }
        Vec3 d = vel.normalize();
        double s = Math.max(0.0D, chest.subtract(pos).dot(d));
        return pos.add(d.scale(s)).distanceTo(chest);
    }

    private SeaRules() {
    }
}
