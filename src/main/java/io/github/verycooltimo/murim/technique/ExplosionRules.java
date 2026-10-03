package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;

/**
 * «Взрыв Цветущей Сливы» — седьмая, последняя и самая мощная форма Меча Семи Цветков Сливы
 * (docs/design/techniques/seven-plum-explosion-spec.md, рефы «7 plum blossoms sword/explosion» 01–03;
 * канон — «Сокрушение Цветка Сливы. Квааааанг!»).
 *
 * <p>Автор 03.10: «там строится частокол (стена из цветов, как наш Разрез·Частокол), просто мечом
 * ударяют, и там как взрыв происходит из листьев/лепестков». Стойка → пять быстрых взмахов
 * вырезают из земли стену цветущих кольев поперёк линии на цель → удержание, меч за плечо →
 * прыжок-удар двумя руками в середину стены → стена разлетается направленным выбросом лепестков
 * и обломков ветвей к цели (конус расширяется от ширины стены), урон по фронту, отброс,
 * оглушение.
 *
 * <p>Тики — от начала техники (T). Сервер получает тики техники напрямую (каждый тик), клиент
 * сверяет шкалу по пакетам WALL (T0) и BLAST ({@link #CONTACT}). Общие для сервера и клиента.
 */
public final class ExplosionRules {

    /** Стойка: синяя ци, пыль из-под ног, меч поднимается. */
    public static final int STANCE = 6;
    /** Пять быстрых взмахов-разрезов (автор: стена «быстро воздвигается»), раз в {@link #STROKE_GAP}. */
    public static final int STROKES = 5;
    public static final int STROKE_GAP = 3;
    /**
     * Удержание: стена дышит, мастер заводит меч за плечо двумя руками. Короче, чем у Частокола
     * (codex 03.10: долгая экспозиция делает стену главным зрелищем, а взрыв — её продолжением).
     */
    public static final int HOLD = STANCE + STROKES * STROKE_GAP + 2;
    /** Надпись — в паузе перед ударом. */
    public static final int CAPTION = HOLD;
    /** Начало IMPACT: прыжок-рывок к стене. */
    public static final int LUNGE = 28;
    public static final int LUNGE_TICKS = 4;
    /** Меч входит в стену — она взрывается. */
    public static final int CONTACT = LUNGE + LUNGE_TICKS;
    /** Отброс виден, потом оглушение (оглушение гасит ход — иначе отброса не было бы). */
    public static final int STUN_DELAY = 7;
    /** Лепестковая буря дорезает задетых (тики после контакта). */
    public static final int[] GRIND = {9, 15};
    public static final int END = 80;

    /** Стена — в стольких блоках перед мастером (по горизонтали к цели). */
    public static final double WALL_DIST = 2.8D;
    /** Мастер прыгает к стене на столько блоков. */
    public static final double LUNGE_REACH = 1.4D;
    /** Скорость фронта выброса, блоков/тик, и старт фронта от плоскости стены. */
    public static final double SPEED = 1.6D;
    public static final double START = 0.6D;
    /** Раскрытие конуса: тангенсы полуугла по горизонтали и по вертикали. */
    public static final double SPREAD_H = Math.tan(Math.toRadians(20.0D));
    public static final double SPREAD_V = Math.tan(Math.toRadians(14.0D));
    /** Высота точки удара над землёй у стены (там, куда входит меч). */
    public static final double STRIKE_Y = 1.5D;
    /** Ось выброса не отклоняется от нормали стены дальше этого угла (цель ушла вбок). */
    public static final double MAX_SWING_DEG = 55.0D;

    /** Урон (× урон в руке × сила слоя): основной выброс и два дореза лепестковой бурей. */
    public static final double DMG_BLAST = 2.8D;
    public static final double DMG_GRIND = 0.5D;
    public static final double DMG_TRAINING = 1.0D;
    /** Отброс по оси выброса и подброс. */
    public static final double KNOCK = 1.25D;
    public static final double LIFT = 0.35D;

    /** Сила слоя: слой 7 = 1, слой 8 сильнее. */
    public static double power(int layer) {
        return layer <= 0 ? 0.0D : layer >= 8 ? 1.15D : 0.3D + 0.1D * layer;
    }

    /** Дальность выброса по слою: слой 1 — 6,6 блока, слой 7 — 16. */
    public static double range(int layer) {
        return layer <= 0 ? 0.0D : Math.min(17.5D, 5.0D + 1.6D * layer);
    }

    /** Кольев в стене по слою (нечётно — один в середине, куда бьёт меч). */
    public static int stakes(int layer) {
        return switch (Math.max(0, Math.min(8, layer))) {
            case 0 -> 0;
            case 1 -> 3;
            case 2 -> 5;
            case 3, 4 -> 7;
            case 5, 6 -> 9;
            default -> 11;
        };
    }

    /** Расстояние между кольями, блоков (codex 03.10: просветы не должны доминировать — частокол плотный). */
    public static final double STAKE_GAP = 0.62D;

    /** Полуширина стены, блоков. */
    public static double halfWidth(int layer) {
        return Math.max(0, stakes(layer) - 1) * STAKE_GAP * 0.5D + 0.4D;
    }

    /** Высота кольев: слой 1 — 3,2, слой 7 — 6 блоков (середина выше краёв). */
    public static double height(int layer) {
        return layer <= 0 ? 0.0D : Math.min(6.4D, 2.8D + 0.47D * layer);
    }

    /** Лепестки — с 3-го слоя; до этого стена из холодных разрезов. */
    public static boolean petals(int layer) {
        return layer >= 3;
    }

    /** Плотность эффектов по слою (доля от полного рисунка слоя 7). */
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

    /** Фронт выброса (блоков от точки удара) через {@code s} тиков после контакта. */
    public static double front(double s, int layer) {
        return Math.min(range(layer), START + SPEED * Math.max(0.0D, s));
    }

    /** Центр стены: перед мастером по горизонтали направления {@code aim}. */
    public static Vec3 wallCentre(Vec3 feet, Vec3 aim) {
        Vec3 f = flat(aim);
        return feet.add(f.scale(WALL_DIST));
    }

    /**
     * Ось выброса: от точки удара к цели (в том числе вверх/вниз), но не дальше
     * {@link #MAX_SWING_DEG} от нормали стены по горизонтали.
     */
    public static Vec3 axis(Vec3 strike, Vec3 normal, Vec3 targetCentre) {
        Vec3 n = flat(normal);
        if (targetCentre == null) {
            return n;
        }
        Vec3 to = targetCentre.subtract(strike);
        if (to.lengthSqr() < 1.0E-4D) {
            return n;
        }
        Vec3 dir = to.normalize();
        Vec3 df = flat(dir);
        double cos = df.dot(n);
        if (cos < Math.cos(Math.toRadians(MAX_SWING_DEG))) {
            return n;
        }
        return dir;
    }

    /**
     * Внутри ли точка {@code p} (с запасом {@code pad}) объёма выброса до фронта {@code front}:
     * сечение — эллипс, полуширина растёт от ширины стены, полувысота — от половины её высоты.
     *
     * @return расстояние вдоль оси или −1, если снаружи
     */
    public static double inside(Vec3 strike, Vec3 axis, Vec3 p, double front, int layer, double pad) {
        Vec3 rel = p.subtract(strike);
        double along = rel.dot(axis);
        // Между мастером и стеной тоже бьёт: подбежавший вплотную враг попадает под удар меча
        // и обратный выброс (живой зомби на стенде 03.10 стоял у мастера и не получал ничего).
        if (along < -(WALL_DIST + 0.5D + pad) || along > front + pad) {
            return -1.0D;
        }
        Vec3 side = flat(new Vec3(-axis.z, 0.0D, axis.x));
        if (side.lengthSqr() < 1.0E-6D) {
            side = new Vec3(1.0D, 0.0D, 0.0D);
        }
        Vec3 up = side.cross(axis).normalize();
        double a = Math.max(0.0D, along);
        double hw = halfWidth(layer) + a * SPREAD_H + pad;
        double hh = height(layer) * 0.5D + a * SPREAD_V + pad;
        double x = rel.dot(side) / hw;
        double y = rel.dot(up) / hh;
        return x * x + y * y <= 1.0D ? Math.max(0.0D, along) : -1.0D;
    }

    public static Vec3 flat(Vec3 v) {
        Vec3 f = new Vec3(v.x, 0.0D, v.z);
        return f.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : f.normalize();
    }

    private ExplosionRules() {
    }
}
