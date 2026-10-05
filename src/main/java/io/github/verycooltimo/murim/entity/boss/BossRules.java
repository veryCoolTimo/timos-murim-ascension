package io.github.verycooltimo.murim.entity.boss;

/**
 * Чистые правила боя с хозяином крепости (docs/design/26-boss.md §3–4): фазы по здоровью,
 * геометрия меток на земле, выбор приёма. Без Minecraft — их проверяют юнит-тесты, а сервер
 * и клиент считают по ним одно и то же (метка на земле = зона урона).
 *
 * <p>Поворот — ванильный yaw: 0 — юг (+Z), 90 — запад (−X).
 */
public final class BossRules {

    /** Фаза 2 — с этой доли здоровья (рык перехода). */
    public static final float PHASE2_AT = 0.60F;
    /** Фаза 3 — «кровь кипит». */
    public static final float PHASE3_AT = 0.25F;

    /** Фаза 3: быстрее ходит и бьёт сильнее; телеграфы и окна те же. */
    public static final float PHASE3_DAMAGE = 1.15F;
    public static final double PHASE3_SPEED = 1.15D;

    /** Броня по фазам: в фазе 3 внешнее тело раскрыто — броня падает. */
    public static final double ARMOR = 6.0D;
    public static final double ARMOR_PHASE3 = 2.0D;

    /** Здоровье (docs/design/26 §3; codex: 260 → 200). */
    public static final double HEALTH = 200.0D;

    /** Через сколько тиков без цели в плацу бой сбрасывается: 30 с. */
    public static final int RESET_TICKS = 600;

    private BossRules() {
    }

    /** Фаза по доле здоровья: 1, 2 или 3. */
    public static int phase(float healthFraction) {
        return healthFraction <= PHASE3_AT ? 3 : healthFraction <= PHASE2_AT ? 2 : 1;
    }

    /** Направление взгляда по yaw: {x, z}. */
    public static double[] forward(double yawDeg) {
        double r = Math.toRadians(yawDeg);
        return new double[] {-Math.sin(r), Math.cos(r)};
    }

    /** Yaw на точку (dx, dz). */
    public static float yawTo(double dx, double dz) {
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
    }

    /** Удар по дуге: цель в пределах дальности, дуги и высоты 2 блоков. */
    public static boolean inArc(double dx, double dy, double dz, double yawDeg, double reach, double arcDeg) {
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat > reach || Math.abs(dy) > 2.0D) {
            return false;
        }
        if (flat < 0.8D) {
            return true;
        }
        double[] f = forward(yawDeg);
        return (dx * f[0] + dz * f[1]) / flat >= Math.cos(Math.toRadians(arcDeg * 0.5D));
    }

    /** Кольцо: цель внутри радиуса по горизонтали и не выше 2,5 блока. */
    public static boolean inRing(double dx, double dy, double dz, double radius) {
        return dx * dx + dz * dz <= radius * radius && Math.abs(dy) <= 2.5D;
    }

    /**
     * Расстояние вдоль полосы от её начала (отрицательное — позади) и поперёк. Полоса идёт
     * из точки начала по yaw.
     *
     * @return {вдоль, поперёк}
     */
    public static double[] laneCoords(double dx, double dz, double yawDeg) {
        double[] f = forward(yawDeg);
        double along = dx * f[0] + dz * f[1];
        double across = Math.abs(dx * f[1] - dz * f[0]);
        return new double[] {along, across};
    }

    /** Цель на полосе: от {@code from} до {@code to} вдоль, не дальше {@code halfWidth} поперёк. */
    public static boolean onLane(double dx, double dy, double dz, double yawDeg, double from, double to, double halfWidth) {
        if (Math.abs(dy) > 2.5D) {
            return false;
        }
        double[] c = laneCoords(dx, dz, yawDeg);
        return c[0] >= from && c[0] <= to && c[1] <= halfWidth;
    }

    /** Поворот трёх линий раскола: центр на цель, две по бокам через {@link BossMove#SPLIT_SPREAD}. */
    public static double[] splitYaws(double centreYaw) {
        return new double[] {centreYaw - BossMove.SPLIT_SPREAD, centreYaw, centreYaw + BossMove.SPLIT_SPREAD};
    }

    /**
     * Попадает ли волна раскола в цель на тике {@code tick} удара: линия лопается от босса наружу
     * за время удара, и задевает то, что под фронтом (последние 3 блока).
     */
    public static boolean splitHits(double dx, double dy, double dz, double centreYaw, int tick, int strikeTicks) {
        double front = BossMove.SPLIT_LENGTH * Math.min(1.0D, (tick + 1) / (double) strikeTicks);
        for (double yaw : splitYaws(centreYaw)) {
            if (onLane(dx, dy, dz, yaw, Math.max(0.0D, front - 3.0D), front, BossMove.SPLIT_HALF_WIDTH)) {
                return true;
            }
        }
        return false;
    }

    /** Точка внутри плаца: координата, прижатая к краю с отступом {@code margin}. */
    public static double clampToYard(double v, double centre, double half, double margin) {
        return Math.max(centre - half + margin, Math.min(centre + half - margin, v));
    }

    /** Внутри ли плаца (по горизонтали) с запасом {@code margin} (отрицательный — строже). */
    public static boolean inYard(double x, double z, double cx, double cz, double half, double margin) {
        return Math.abs(x - cx) <= half + margin && Math.abs(z - cz) <= half + margin;
    }

    /**
     * Длина полосы до края плаца: сколько можно пройти из точки по yaw, не выходя из плаца с
     * отступом {@code margin}; не больше {@code max}.
     */
    public static double laneLength(double x, double z, double yawDeg, double cx, double cz, double half, double margin, double max) {
        double[] f = forward(yawDeg);
        double len = max;
        double lo = -half + margin, hi = half - margin;
        double rx = x - cx, rz = z - cz;
        if (f[0] > 1.0E-6) {
            len = Math.min(len, (hi - rx) / f[0]);
        } else if (f[0] < -1.0E-6) {
            len = Math.min(len, (lo - rx) / f[0]);
        }
        if (f[1] > 1.0E-6) {
            len = Math.min(len, (hi - rz) / f[1]);
        } else if (f[1] < -1.0E-6) {
            len = Math.min(len, (lo - rz) / f[1]);
        }
        return Math.max(0.0D, len);
    }

    /** Кулдауны приёмов в тиках (после окончания приёма). */
    public static int cooldown(BossMove m) {
        if (m == BossMove.POUNCE) {
            return 160;
        }
        if (m == BossMove.RAM) {
            return 100;
        }
        if (m == BossMove.ROAR) {
            return 600;
        }
        if (m == BossMove.SPLIT) {
            return 200;
        }
        if (m == BossMove.WHIRL) {
            return 320;
        }
        return 0;
    }

    /**
     * Выбор следующего приёма. Связка — по умолчанию вблизи; дальние приёмы — по дистанции
     * и готовности. {@code ready[id]} — готов ли приём (кулдаун прошёл).
     *
     * @param distance расстояние до цели по горизонтали
     * @param phase    1–3
     * @param roll     случайное [0, 1)
     * @return приём или {@code null}, если нужно сближаться
     */
    public static BossMove choose(double distance, int phase, boolean[] ready, float roll) {
        if (phase >= 3 && ready[BossMove.WHIRL.id()] && distance <= 12.0D && roll < 0.45F) {
            return BossMove.WHIRL;
        }
        if (phase >= 2 && ready[BossMove.ROAR.id()] && distance <= 6.5D) {
            return BossMove.ROAR;
        }
        if (phase >= 2 && ready[BossMove.SPLIT.id()] && distance >= 4.0D && distance <= 12.0D && roll < 0.7F) {
            return BossMove.SPLIT;
        }
        if (distance >= BossMove.RAM_FROM && ready[BossMove.RAM.id()]) {
            return BossMove.RAM;
        }
        if (distance >= BossMove.POUNCE_MIN && distance <= BossMove.POUNCE_MAX && ready[BossMove.POUNCE.id()] && roll < 0.6F) {
            return BossMove.POUNCE;
        }
        if (distance <= BossMove.CHAIN_REACH - 0.4D) {
            return BossMove.CHAIN;
        }
        return null;
    }
}
