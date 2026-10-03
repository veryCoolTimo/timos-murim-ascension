package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;

/**
 * «Взрыв Цветущей Сливы» — седьмая, последняя и самая мощная форма Меча Семи Цветков Сливы
 * (docs/design/techniques/seven-plum-explosion-spec.md, рефы «7 plum blossoms sword/explosion» 01–03;
 * канон — «Сокрушение Цветка Сливы. Квааааанг!»).
 *
 * <p>Автор 03.10 (после первой версии со стеной кольев): «взрыв очень крут, но я не понимаю, откуда
 * там деревья. Он короткий: просто долгий замах справа вверх, и на ударе — взрыв лепестков».
 * Последняя правка автора 03.10: «супер, только удар — снизу вверх, а не сверху вниз».
 * Стойка → один долгий медленный замах: клинок уходит вниз и назад к правой ноге (ци и лепестки
 * стягиваются к нему) → короткая пауза внизу → быстрый восходящий разрез снизу справа вверх-
 * вперёд-влево с полушагом → на вершине дуги, в точке удара
 * (1,5 блока перед мастером) взрыв лепестков конусом к цели (в небо тоже): урон по фронту,
 * отброс, оглушение, два дореза.
 *
 * <p>Тики — от начала техники (T). Сервер получает тики техники напрямую, клиент сверяет шкалу
 * по пакетам START (T0), LUNGE ({@link #LUNGE}) и BLAST ({@link #CONTACT}).
 */
public final class ExplosionRules {

    /** Стойка: синяя ци, пыль из-под ног, меч опущен справа. */
    public static final int STANCE = 6;
    /** Долгий замах: клинок медленно уходит вниз и назад к правой ноге, 0,9 с. */
    public static final int WINDUP_END = 24;
    /** Надпись — когда клинок доходит до низа. */
    public static final int CAPTION = 22;
    /** Начало IMPACT: восходящий разрез снизу вверх-вперёд с полушагом. */
    public static final int LUNGE = 28;
    public static final int LUNGE_TICKS = 3;
    /** Клинок доходит до точки удара — взрыв. */
    public static final int CONTACT = LUNGE + LUNGE_TICKS;
    /** Отброс виден, потом оглушение (оглушение гасит ход — иначе отброса не было бы). */
    public static final int STUN_DELAY = 7;
    /** Лепестковая буря дорезает задетых (тики после контакта). */
    public static final int[] GRIND = {9, 15};
    /** Конец техники: 2,5 с. */
    public static final int END = 50;
    /** Оглушение мобов, тиков (игрок 0,6 с, босс 0,5 с). */
    public static final int STUN_MOB = 40;

    /** Полушаг вперёд на ударе и вынос точки удара перед мастером после шага, блоков. */
    public static final double STEP = 0.8D;
    public static final double STRIKE_DIST = 1.5D;
    /** Скорость фронта выброса, блоков/тик, и старт фронта от точки удара. */
    public static final double SPEED = 1.6D;
    public static final double START = 0.6D;
    /** Сечение у точки удара: полуширина и полувысота, блоков; раскрытие конуса — тангенсы полуугла. */
    public static final double HALF_W0 = 1.4D;
    public static final double HALF_H0 = 1.2D;
    public static final double SPREAD_H = Math.tan(Math.toRadians(26.0D));
    public static final double SPREAD_V = Math.tan(Math.toRadians(18.0D));
    /** Высота точки удара над ступнями. */
    public static final double STRIKE_Y = 1.7D;
    /** Ось выброса не отклоняется от направления удара по горизонтали дальше этого угла. */
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

    /** Лепестки — с 3-го слоя; до этого выброс из холодных штрихов. */
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

    /** Точка удара: перед мастером по горизонтали направления {@code aim}, с учётом полушага. */
    public static Vec3 strikePoint(Vec3 feet, Vec3 aim) {
        return feet.add(flat(aim).scale(STEP + STRIKE_DIST)).add(0.0D, STRIKE_Y, 0.0D);
    }

    /**
     * Ось выброса: от точки удара к цели (в том числе вверх/вниз), но не дальше
     * {@link #MAX_SWING_DEG} от направления удара по горизонтали; без цели — {@code fallback}.
     */
    public static Vec3 axis(Vec3 strike, Vec3 fallback, Vec3 targetCentre) {
        Vec3 n = fallback.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : fallback.normalize();
        if (targetCentre == null) {
            return n;
        }
        Vec3 to = targetCentre.subtract(strike);
        if (to.lengthSqr() < 1.0E-4D) {
            return n;
        }
        Vec3 dir = to.normalize();
        if (flat(dir).dot(flat(n)) < Math.cos(Math.toRadians(MAX_SWING_DEG))) {
            return n;
        }
        return dir;
    }

    /**
     * Внутри ли точка {@code p} (с запасом {@code pad}) объёма выброса до фронта {@code front}:
     * сечение — эллипс, растущий от {@link #HALF_W0}×{@link #HALF_H0}. Позади точки удара — до
     * самого мастера (подбежавший вплотную тоже под ударом).
     *
     * @return расстояние вдоль оси или −1, если снаружи
     */
    public static double inside(Vec3 strike, Vec3 axis, Vec3 p, double front, int layer, double pad) {
        Vec3 rel = p.subtract(strike);
        double along = rel.dot(axis);
        if (along < -(STEP + STRIKE_DIST + 0.5D + pad) || along > front + pad) {
            return -1.0D;
        }
        Vec3 side = flat(new Vec3(-axis.z, 0.0D, axis.x));
        if (side.lengthSqr() < 1.0E-6D) {
            side = new Vec3(1.0D, 0.0D, 0.0D);
        }
        Vec3 up = side.cross(axis).normalize();
        double a = Math.max(0.0D, along);
        double hw = HALF_W0 + a * SPREAD_H + pad;
        double hh = HALF_H0 + a * SPREAD_V + pad;
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
