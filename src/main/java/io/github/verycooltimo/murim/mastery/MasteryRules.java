package io.github.verycooltimo.murim.mastery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Правила изучения, освоения и мудрости — без мира, игрока и пакетов (docs/design/19 §3г).
 *
 * <p>Решения автора 30.09: бой качает напрямую; тренировка тоже, но меньше; медитация
 * переводит неосмысленное в освоение выгоднее; звезда 0 после чтения; жёсткий порог на
 * изучение; мудрость косвенная и растёт от новых техник и постижения крутых.
 * Все числа здесь — черновые, проверяются на игре.
 */
public final class MasteryRules {

    /** Откуда пережитое. */
    public enum Source {
        /** Попадание по живому противнику. */
        FIGHT(1.0D),
        /** Sparring provides the insight needed to complete the final stars. */
        SPARRING(1.0D),
        /** Манекен, удар в воздух: тренировка качает, но меньше (автор: «тренируя технику»). */
        TRAINING(0.3D);

        final double weight;

        Source(double weight) {
            this.weight = weight;
        }
    }

    /**
     * Можно ли вообще изучать технику: без сформированного даньтяня ци не удержать
     * (автор 03.10: «изучить техники, не имея даже даньтяня» — баг). Путь к даньтяню —
     * свиток метода и медитация до семени; сам свиток метода учится без даньтяня.
     */
    public static boolean canLearn(io.github.verycooltimo.murim.profile.DantianProfile profile) {
        return profile != null && profile.isAwakened();
    }

    /**
     * С какого ранга учится техника этого уровня. Сокровенные (24 Движения) — только с Пика
     * (автор 03.10, этап M2): ниже него книга читается, но не учит. Остальные — с любого ранга.
     */
    public static int rankNeed(TechniqueTier tier) {
        return tier == TechniqueTier.SECRET ? io.github.verycooltimo.murim.cultivation.Realm.PEAK : 0;
    }

    /** Хватает ли ранга, чтобы изучать технику этого уровня. */
    public static boolean rankAllows(io.github.verycooltimo.murim.profile.DantianProfile profile, TechniqueTier tier) {
        return profile != null && profile.rank() >= rankNeed(tier);
    }

    /**
     * Share of earned practice that counts at once; the rest goes to the per-art RESERVE (author 18.10, decision final:
     * 80 / 20). The reserve never burns: 30 s of the Echo on that art, or a night in a bed, turns the whole of it into
     * mastery with no multiplier.
     */
    public static final double DIRECT_SHARE = 0.8D;

    /**
     * Cumulative experience to reach the star {@code n} of a twelve-star art, {@code CUMULATIVE[n]}
     * (author 18.10, hua-main-1810 §5 and D-4; the joint plan "Темп для симуляции"). The star {@code n} costs
     * {@code CUMULATIVE[n] - CUMULATIVE[n-1]}: 40, 80, 160, 240, 330, 450, 600, 800, 900, 1100, 1200, 1300. The last two
     * stars (the insight wall) are 34.7 % of the whole.
     */
    private static final double[] CUMULATIVE = {0, 40, 120, 280, 520, 850, 1300, 1900, 2700, 3600, 4700, 5900, 7200};

    /** The whole cost of a twelve-star art. */
    public static final double TOTAL_XP = CUMULATIVE[CUMULATIVE.length - 1];

    private static final double STAR_COUNT = CUMULATIVE.length - 1;

    /** The old cost of the first star on the exponential curve; kept only for the migration of old saves. */
    private static final double LEGACY_FIRST_STAR = 17.0D;
    private static final double LEGACY_STAR_GROWTH = 1.6D;

    /** The old exponential cost of a star (saves before version 5 counted progress in these units). */
    public static double legacyNeed(int stars) {
        return LEGACY_FIRST_STAR * Math.pow(LEGACY_STAR_GROWTH, stars);
    }

    /** The last two transitions need enlightenment, relative to the full art, not the manuscript. */
    public static int insightStar(int artCap) {
        return Math.max(0, artCap - 2);
    }

    /** Мудрость за изучение новой техники и за постижение крутой. */
    static final double WISDOM_PER_TECHNIQUE = 1.0D;
    static final double WISDOM_PER_COMPREHENSION = 4.0D;

    /**
     * Experience needed to be at the star {@code stars} of an art of {@code artCap} stars, counted from nothing.
     * An art with fewer stars (footwork has ten) keeps the SHAPE of the table and its price in proportion: the curve is
     * stretched over the stars the art has and scaled by {@code artCap / 12}, so a ten-star art costs 6000 in all, not 7200.
     */
    public static double cumulative(int stars, int artCap) {
        int cap = artCap <= 0 ? Arts.MAX_STARS : artCap;
        double at = Math.max(0.0D, Math.min(cap, stars)) * STAR_COUNT / cap;
        int low = (int) Math.floor(at);
        double fraction = at - low;
        double xp = low >= STAR_COUNT ? TOTAL_XP : CUMULATIVE[low] + fraction * (CUMULATIVE[low + 1] - CUMULATIVE[low]);
        return xp * cap / STAR_COUNT;
    }

    /** Experience of the transition {@code stars} → {@code stars + 1} of a twelve-star art. */
    public static double need(int stars) {
        return need(stars, Arts.MAX_STARS);
    }

    /** Experience of the transition {@code stars} → {@code stars + 1} of an art with {@code artCap} stars. */
    public static double need(int stars, int artCap) {
        int cap = artCap <= 0 ? Arts.MAX_STARS : artCap;
        int star = Math.max(0, Math.min(stars, cap - 1));
        return cumulative(star + 1, cap) - cumulative(star, cap);
    }

    /** Wisdom is breadth only: +5 % per unit, never more than +10 % (author 19.10; it used to have no ceiling). */
    static final double BREADTH_PER_WISDOM = 0.05D;
    public static final double BREADTH_MAX = 0.10D;

    /**
     * ONE shared ceiling of everything that is not the player's own practice (author 18.10, decision final): pages,
     * mentor lessons, readings, an elder's guidance and the one-off Rush of the Apex Echo TOGETHER give at most a fifth
     * of each star transition of each art; the other four fifths are practice. The amount already given lives in the
     * mastery state ({@link TechniqueProgress#help()}) and starts again at every new star.
     */
    public static final double HELP_CAP = 0.20D;

    /** Whole practice multiplier ceiling: breadth +0.10 and the Echo +0.20 (author 19.10). */
    public static final double MAX_PRACTICE_FACTOR = 1.0D + BREADTH_MAX + Resonance.ECHO_CAP;

    /** Breadth of learning (wisdom) speeds practice up: +5 % per unit, capped at +10 %. */
    public static double wisdomFactor(double wisdom) {
        return 1.0D + Math.min(BREADTH_MAX, BREADTH_PER_WISDOM * Math.max(0.0D, wisdom));
    }

    /**
     * The one practice multiplier: breadth plus the Apex Echo, never above {@link #MAX_PRACTICE_FACTOR}.
     * Applied once, when the practice is gained (the stored half is not multiplied by the Echo again).
     */
    public static double practiceFactor(double wisdom, double echo) {
        return wisdomFactor(wisdom) + Math.min(Resonance.ECHO_CAP, Math.max(0.0D, echo));
    }

    /** Итог прироста: новое состояние и пройденные звёзды — для озарения. */
    public record Gain(TechniqueProgress progress, List<Integer> starsReached) {
    }

    /** The result of a help: the gain and how much experience the shared help pool really let through. */
    public record Helped(Gain gain, double granted) {
    }

    /** What gives help. Every source draws from the ONE pool of the transition ({@link #HELP_CAP}). */
    public enum HelpSource {
        /** A page of the art (shop or loot). */
        PAGE,
        /** A mentor's lesson, a review after sparring, the drill after a lost bout. */
        LESSON,
        /** A reread book or a reading in the sect archive. */
        READING,
        /** An elder's guidance (the practice bonus of the shop). */
        GUIDANCE,
        /** The one-off Rush of an Apex Echo milestone. */
        RUSH
    }

    /**
     * Practice earned in a fight or at a dummy. {@code amount} is what was lived through (for example one hit).
     * 80 % counts at once; 20 % goes to the reserve of the art, where it waits and never burns. Experience is never
     * lost to a day change any more: there is no cooling.
     */
    public static Gain experience(TechniqueProgress current, Source source, double amount, double wisdom) {
        return experience(current, source, amount, wisdom, Arts.MAX_STARS);
    }

    public static Gain experience(TechniqueProgress current, Source source, double amount, double wisdom, int artCap) {
        return experience(current, source, amount, wisdom, 0.0D, artCap);
    }

    /** As above, with the Echo bonus of the art ({@link Resonance#echo}) applied once with the breadth. */
    public static Gain experience(TechniqueProgress current, Source source, double amount,
                                  double wisdom, double echo, int artCap) {
        double total = Math.max(0.0D, amount) * source.weight * practiceFactor(wisdom, echo);
        double direct = total * DIRECT_SHARE;
        boolean full = current.stars() >= Math.min(current.cap(), artCap);
        // A finished art (or a torn manuscript at its limit) keeps no reserve: there is nothing left to understand.
        TechniqueProgress withReserve = full ? current : current.withUnprocessed(current.unprocessed() + (total - direct));
        return advance(withReserve, direct, source == Source.SPARRING, artCap, 0.0D);
    }

    /**
     * The Echo of an art (30 s) or a night in a bed: the WHOLE reserve of the art becomes mastery, with no multiplier,
     * and an experience that waited at the insight wall is released. Only what the art can still take before its limit
     * (the full art, or the manuscript of a torn book) is moved; the rest of the reserve stays and waits (it never burns:
     * a deepened manuscript takes it later).
     */
    public static Gain process(TechniqueProgress current, int artCap) {
        int limit = Math.min(current.cap(), artCap);
        if (current.stars() >= limit) {
            return new Gain(current, List.of());
        }
        double room = cumulative(limit, artCap) - cumulative(current.stars(), artCap) - current.progress();
        double take = Math.min(current.unprocessed(), Math.max(0.0D, room));
        TechniqueProgress rest = current.withUnprocessed(current.unprocessed() - take);
        if (take <= 0.0D) {
            if (rest.progress() >= need(rest.stars(), artCap)) {
                return advance(rest, 0.0D, true, artCap, 0.0D);
            }
            return new Gain(current, List.of());
        }
        return advance(rest, take, true, artCap, 0.0D);
    }

    /**
     * Experience that is NOT the player's own practice, without the help pool: used by migrations and by tests. Pages,
     * lessons, readings, guidance and the Rush go through {@link #help} instead.
     */
    public static Gain study(TechniqueProgress current, double amount) {
        return study(current, amount, Arts.MAX_STARS, false);
    }

    public static Gain study(TechniqueProgress current, double amount, int artCap, boolean insight) {
        return advance(current, Math.max(0.0D, amount), insight, artCap, 0.0D);
    }

    /** How much more help the transition in front of the art can take (0 at the end of the art). */
    public static double helpRoom(TechniqueProgress current, int artCap) {
        if (current.stars() >= Math.min(current.cap(), artCap)) {
            return 0.0D;
        }
        return Math.max(0.0D, HELP_CAP * need(current.stars(), artCap) - current.help());
    }

    /**
     * Help from a page, a lesson, a reading, a guidance or the Rush: the amount is cut to what is left of the ONE help
     * pool of this transition (a fifth of its cost); the other four fifths are the player's own practice. The part above
     * the pool is lost, but the non-experience effect remains: {@code insight} still releases the insight wall.
     */
    public static Helped help(TechniqueProgress current, double amount, boolean insight, int artCap) {
        double granted = Math.min(Math.max(0.0D, amount), helpRoom(current, artCap));
        double fill = Math.max(0.0D, need(current.stars(), artCap) - current.progress());
        Gain gain = advance(current, granted, insight, artCap, granted);
        TechniqueProgress after = gain.progress();
        if (!gain.starsReached().isEmpty() && after.stars() < Math.min(after.cap(), artCap)) {
            // Help that went past the end of the old star is help of the NEW transition: it counts in its pool, so the
            // two stars together never take more than a fifth each.
            double carried = Math.min(Math.max(0.0D, granted - fill), HELP_CAP * need(after.stars(), artCap));
            after = after.withHelp(carried);
        }
        return new Helped(new Gain(after, gain.starsReached()), granted);
    }

    private static Gain advance(TechniqueProgress current, double amount, boolean insight, int artCap, double helped) {
        int stars = current.stars();
        double progress = current.progress() + amount;
        double help = current.help() + helped;
        List<Integer> reached = new ArrayList<>();
        int limit = Math.min(current.cap(), artCap);
        // Следующая звезда — только после полного предыдущего; выше предела манускрипта не растёт.
        while (stars < limit && progress >= need(stars, artCap)) {
            // Store all practice at the wall; insight consumes it without resetting mastery.
            if (!insight && stars >= insightStar(artCap)) break;
            progress -= need(stars, artCap);
            stars++;
            reached.add(stars);
            // A new transition starts with an empty help pool.
            help = 0.0D;
        }
        if (stars >= limit) {
            progress = 0.0D;
            help = 0.0D;
        }
        return new Gain(new TechniqueProgress(stars, progress, current.unprocessed(), current.day(),
                current.cap(), TechniqueProgress.VERSION, help), List.copyOf(reached));
    }

    /**
     * Stars a freshly learned art starts with: the height an apex already gave it ({@link Resonance#startHeight}),
     * never past the insight wall and never for a secret art (those keep their hard requirements). Replaces the
     * old wisdom skip (author 19.10).
     */
    public static int startStars(TechniqueTier tier, int cap, int height, int artCap) {
        if (tier == TechniqueTier.SECRET) {
            return 0;
        }
        return Math.min(Math.max(0, height), Math.max(0, Math.min(cap - 1, insightStar(artCap))));
    }

    /** Мудрость за изучение новой техники: крутые учат больше. */
    public static double wisdomForLearning(TechniqueTier tier) {
        return tier == TechniqueTier.BASIC ? WISDOM_PER_TECHNIQUE : WISDOM_PER_TECHNIQUE * 2.0D;
    }

    /**
     * Постигнута ли крутая техника на этой звезде: «нашёл крутую технику и понял её».
     * Порог — три пятых всех звёзд.
     */
    public static boolean comprehends(TechniqueTier tier, int stars, int artCap) {
        return tier != TechniqueTier.BASIC && stars >= Math.ceil(artCap * 0.6D);
    }

    public static double wisdomForComprehension() {
        return WISDOM_PER_COMPREHENSION;
    }

    /** The foundation copy teaches Bamboo before its first execution star. Shared by server and book. */
    public static boolean learnsAtZero(net.minecraft.resources.ResourceLocation id) {
        return io.github.verycooltimo.murim.technique.Styles.BAMBOO_HAND.forms().getFirst().equals(id);
    }

    /**
     * Жёсткий порог изучения (автор 30.09): чего не хватает, чтобы выучить технику.
     *
     * @param known выученные техники и их звёзды
     * @return недостающие требования; пусто — можно учить
     */
    public static List<TechniqueRequirement> missing(List<TechniqueRequirement> requires,
                                                     Map<net.minecraft.resources.ResourceLocation, Integer> known) {
        List<TechniqueRequirement> missing = new ArrayList<>();
        for (TechniqueRequirement requirement : requires) {
            Integer stars = known.get(requirement.technique());
            if (stars == null || stars < requirement.star()) {
                missing.add(requirement);
            }
        }
        return List.copyOf(missing);
    }

    /**
     * Во сколько раз дороже по ци техника на этой звезде. Звезда 0 — «коряво»: ×1.5;
     * полное освоение — ×0.8. Между ними — линейно (docs/design/19 §3г).
     */
    public static double costFactor(int stars, int artCap) {
        double k = artCap <= 0 ? 1.0D : Math.max(0.0D, Math.min(1.0D, stars / (double) artCap));
        return 1.5D - 0.7D * k;
    }

    /** Сила техники на этой звезде: звезда 0 — ×0.6, полное освоение — ×1.25. */
    public static double powerFactor(int stars, int artCap) {
        double k = artCap <= 0 ? 1.0D : Math.max(0.0D, Math.min(1.0D, stars / (double) artCap));
        return 0.6D + 0.65D * k;
    }

    private MasteryRules() {
    }
}
