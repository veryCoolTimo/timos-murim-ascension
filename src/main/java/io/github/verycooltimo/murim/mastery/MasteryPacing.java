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
        SPAR(0.60D, 30, 30, 0.25D),
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

    /**
     * Base income before the per-star release pacing factor. The cost of a star is the table of {@link MasteryRules#need(int, int)} (40 for the first,
     * 7200 in all for twelve stars); this multiplier turns the "price of a hit" below into that unit. Release pacing uses successful active uses, including cooldown time; see ReleaseBalanceSimTest.
     * Thresholds, reserve splitting and the insight wall are unchanged.
     */
    public static final double INCOME = 2.1D;

    /** Fighting pays at least 2.5 times a fresh clean dummy hit, even at the fatigue/rank floor. */
    public static final double COMBAT_MULTIPLIER = 2.5D;

    // Release round 5: repeated solo practice, only on the way to the first three stars.
    private static final double[] EARLY_TRAINING_FACTORS = {2.8D, 4.5D, 4.0D};
    // Keep headroom between dummy and mixed transitions after five-minute Echo rounding.
    private static final double EARLY_COMPLETED_TRAINING_FACTOR = 1.25D;

    /**
     * Ease sustained beginner practice without changing a single use in the frozen
     * dummy/spar/bandit rotation. The first training use and every combat use keep
     * their old income. From current star three onward all sources use round 4 rates.
     * Source: docs/design/27-balance.md, release round 5.
     */
    public static double repeatedTrainingFactor(
            io.github.verycooltimo.murim.technique.TechniqueDefinition definition,
            int stars, MasteryRules.Source source, boolean repeatedTraining) {
        if (source != MasteryRules.Source.TRAINING || !repeatedTraining
                || stars < 0 || stars >= EARLY_TRAINING_FACTORS.length) return 1.0D;
        return completedUse(definition) ? EARLY_COMPLETED_TRAINING_FACTOR : EARLY_TRAINING_FACTORS[stars];
    }

    /** One successful use every six seconds, or the form's longer data-defined cycle. */
    public static double useFactor(io.github.verycooltimo.murim.technique.TechniqueDefinition definition) {
        if (definition == null) return 1.0D;
        return Math.max(120, Math.max(definition.cooldownTicks(), definition.totalTicks())) / 120.0D;
    }

    /**
     * Release round 4 (author 6 November): quick early stars, hard mastery at ten, peak at twelve.
     * Nominal mixed-schedule minutes total 125 at eight, 260 at ten and 585 at twelve.
     * Keep round 3's first eight transitions; only the final four incomes change.
     * A global multiplier alone cannot give both a >=2 minute first star and a <=30 minute
     * eighth star: their unchanged XP costs differ twentyfold. Shape income, not save thresholds.
     */
    private static final double[] TRANSITION_MINUTES = {4, 7, 10, 13, 17, 21, 25, 28, 50, 85, 160, 165};

    /**
     * Income per active use, calibrated for dummy/spar/bandit rotation with a five-minute Echo.
     * Offensive income averages about 1.10 after source weighting, before INCOME; defensive
     * completion averages (.3 + .75 + .75) / 3 = .60. Ten six-second uses make one minute.
     * Form cycle scaling includes foundations. Compensate the existing curriculum practice
     * factor here so every form meets the successful-use release target; its caller still
     * applies it once. Offensive misses undo this compensation in MasteryService.onMiss.
     * No state or save units change; reserves and insight remain MasteryRules' responsibility.
     */
    public static double progressionFactor(io.github.verycooltimo.murim.technique.TechniqueDefinition definition,
                                           int stars) {
        if (definition == null) return 1.0D;
        int at = Math.max(0, Math.min(TRANSITION_MINUTES.length - 1, stars));
        double mixedWorth = completedUse(definition) ? 0.60D : 1.10D;
        double curriculum = io.github.verycooltimo.murim.technique.JunkArts.practiceFactor(definition.id());
        return useFactor(definition) * MasteryRules.need(stars, definition.starCap())
                / (TRANSITION_MINUTES[at] * 10.0D * INCOME * mixedWorth * curriculum);
    }

    /** Defensive and self arts complete useful practice without hitting a target. */
    public static boolean completedUse(io.github.verycooltimo.murim.technique.TechniqueDefinition definition) {
        return definition != null && (Arts.isFootwork(definition.id())
                || definition.behavior() instanceof io.github.verycooltimo.murim.technique.TechniqueBehavior.SelfArt);
    }

    /** Во сколько раз дороже противник на ступень выше игрока (и дешевле — на ступень ниже). */
    public static final double RANK_STEP = 1.5D;

    /** Пределы поправки за ранг: не обнулять слабых совсем и не давать «один удар — слой». */
    public static final double RANK_MIN = 0.25D;
    public static final double RANK_MAX = 2.5D;

    /** Доля усталости: 1 — свежий, к полу — надоело. */
    public static double fatigue(Kind kind, int hitsToday) {
        return fatigue(kind, hitsToday, kind.floor);
    }

    /** The same curve with another floor: the dummies of the yard lift it while the forms are varied ({@link YardRules}). */
    public static double fatigue(Kind kind, int hitsToday, double floor) {
        int over = hitsToday - kind.fresh;
        if (over < 0) {
            return 1.0D;
        }
        double decayed = kind.half / (double) (kind.half + over);
        return floor + (1.0D - floor) * decayed;
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
        double value = kind.worth * rank * fatigue(kind, hitsToday);
        if (kind != Kind.TRAINING) {
            // Source.TRAINING weighs .3; the clean dummy bonus is at most 2. Combat sources weigh 1.
            value = COMBAT_MULTIPLIER * 0.3D * YardRules.CLEAN_FACTOR + 0.1D * value;
        }
        return value * INCOME;
    }

    /** A hit of a yard dummy: the floor of the fatigue follows the variety of the forms ({@link YardRules#floor}). */
    public static double dummyWorth(int hitsToday, boolean varied) {
        return Kind.TRAINING.worth * YardRules.fatigue(hitsToday, varied) * INCOME;
    }

    private MasteryPacing() {
    }
}
