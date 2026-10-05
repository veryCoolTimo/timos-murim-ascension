package io.github.verycooltimo.murim.mastery;

/**
 * Сколько стоит одно попадание для освоения: кто противник и сколько таких уже было сегодня
 * (docs/design/27-balance.md). Без мира и игрока — только числа, их проверяет симулятор
 * {@code PacingSimTest}.
 *
 * <p>Жалоба автора 05.10: «через 2–3 убийства зомби меня сразу уровень повышали». Отсюда три правила:
 * <ol>
 *   <li>слабый моб стоит меньше бандита, бандит с ци и главарь — больше, хозяин крепости — больше всех;</li>
 *   <li>противник ниже ранга игрока стоит меньше, выше — больше ({@link #RANK_STEP} за ступень);</li>
 *   <li>усталость за день по виду противника: первые {@code fresh} попаданий полные, дальше
 *       убывают до пола — «фармить зомби» быстро перестаёт качать, новый противник снова учит.</li>
 * </ol>
 * Все ручки — здесь, по одной на число.
 */
public final class MasteryPacing {

    /** Кого бьёт игрок. */
    public enum Kind {
        /** Взмах в воздух, манекен, стойка: только форма. */
        TRAINING(1.0D, 40, 40, 0.10D),
        /** Ванильные враждебные и прочие мобы без ци (зомби, скелеты, пауки, звери). */
        WEAK(0.45D, 12, 12, 0.10D),
        /** Поединок с учеником секты: живой противник, но вполсилы (документ 23 §С3). */
        SPAR(0.70D, 30, 30, 0.25D),
        /** Бандит и другие противники-мастера ({@code Casters.Caster}). */
        BANDIT(1.0D, 60, 60, 0.40D),
        /** Хозяин крепости: без усталости, бой бывает один. */
        BOSS(1.6D, Integer.MAX_VALUE, 1, 1.0D);

        /** Цена попадания по свежему противнику своего ранга. */
        final double worth;
        /** Сколько попаданий за день идёт без убывания. */
        final int fresh;
        /** За сколько попаданий сверх свежих цена падает вдвое (до пола). */
        final int half;
        /** Ниже этой доли цена не падает. */
        final double floor;

        Kind(double worth, int fresh, int half, double floor) {
            this.worth = worth;
            this.fresh = fresh;
            this.half = half;
            this.floor = floor;
        }
    }

    /** Во сколько раз дороже противник на ступень выше игрока (и дешевле — на ступень ниже). */
    public static final double RANK_STEP = 1.5D;

    /** Пределы поправки за ранг: не обнулять слабых совсем и не давать «один удар — слой». */
    public static final double RANK_MIN = 0.25D;
    public static final double RANK_MAX = 2.5D;

    /** Доля усталости: 1 — свежий, к полу — надоело. */
    public static double fatigue(Kind kind, int hitsToday) {
        int over = hitsToday - kind.fresh;
        if (over < 0) {
            return 1.0D;
        }
        double decayed = kind.half / (double) (kind.half + over);
        return kind.floor + (1.0D - kind.floor) * decayed;
    }

    /** Поправка за разницу рангов противника и игрока. */
    public static double rankFactor(int targetRank, int playerRank) {
        double f = Math.pow(RANK_STEP, targetRank - playerRank);
        return Math.max(RANK_MIN, Math.min(RANK_MAX, f));
    }

    /**
     * Сколько пережитого даёт это попадание или взмах.
     *
     * @param hitsToday сколько попаданий по этому виду противника уже было сегодня (до этого)
     */
    public static double worth(Kind kind, int targetRank, int playerRank, int hitsToday) {
        double rank = kind == Kind.TRAINING ? 1.0D : rankFactor(targetRank, playerRank);
        return kind.worth * rank * fatigue(kind, hitsToday);
    }

    private MasteryPacing() {
    }
}
