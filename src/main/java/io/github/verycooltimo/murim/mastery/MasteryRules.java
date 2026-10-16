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

    /** Доля пережитого, сразу идущая в освоение: «через бой тоже качать». */
    static final double DIRECT_SHARE = 0.5D;

    /**
     * Во сколько раз медитация выгоднее для неосмысленного. Было 3,5: бой + медитация давали ×2,25 к
     * чистому бою, и освоение Шести Равновесий шло за 2–3 зомби (автор 05.10). 1,5 — ×1,25 (codex 05.10: 1,25–1,4)
     * (docs/design/27-balance.md).
     */
    static final double MEDITATION_GAIN = 1.5D;

    /** Сколько неосмысленного медитация перерабатывает за тик. */
    static final double MEDITATION_RATE = 0.02D;

    /**
     * Первый переход требует 17 опыта; каждый следующий — в 1,6 раза больше.
     * Последние звёзды дополнительно требуют осмысления. Цена попадания — {@link MasteryPacing}.
     */
    static final double FIRST_STAR = 17.0D;
    static final double STAR_GROWTH = 1.6D;

    /** The final two transitions need enlightenment, relative to the full art, not the manuscript. */
    public static int insightStar(int artCap) {
        return Math.max(0, artCap - 2);
    }

    /** Мудрость за изучение новой техники и за постижение крутой. */
    static final double WISDOM_PER_TECHNIQUE = 1.0D;
    static final double WISDOM_PER_COMPREHENSION = 4.0D;

    /** Сколько мудрости нужно на каждую проскакиваемую звезду простой техники. */
    static final double WISDOM_PER_SKIPPED_STAR = 6.0D;

    /** Сколько пережитого нужно, чтобы достичь звезды {@code stars} → {@code stars + 1}. */
    public static double need(int stars) {
        return FIRST_STAR * Math.pow(STAR_GROWTH, stars);
    }

    /** Мудрость ускоряет освоение: +5% за единицу, без потолка, но медленно. */
    public static double wisdomFactor(double wisdom) {
        return 1.0D + 0.05D * Math.max(0.0D, wisdom);
    }

    /** Итог прироста: новое состояние и пройденные звёзды — для озарения. */
    public record Gain(TechniqueProgress progress, List<Integer> starsReached) {
    }

    /**
     * Пережитое в бою или на тренировке.
     *
     * @param amount сколько пережито (например, одно применение — 1)
     * @param day    текущий игровой день: неосмысленное прошлых дней остывает
     */
    public static Gain experience(TechniqueProgress current, Source source, double amount,
                                  double wisdom, long day) {
        return experience(current, source, amount, wisdom, day, Arts.MAX_STARS);
    }

    public static Gain experience(TechniqueProgress current, Source source, double amount,
                                  double wisdom, long day, int artCap) {
        TechniqueProgress cooled = cool(current, day);
        double total = amount * source.weight * wisdomFactor(wisdom);
        double direct = total * DIRECT_SHARE;
        TechniqueProgress withLeftover = new TechniqueProgress(cooled.stars(), cooled.progress(),
                cooled.unprocessed() + (total - direct), day, cooled.cap());
        return advance(withLeftover, direct, source == Source.SPARRING, artCap);
    }

    /** Один тик медитации: часть неосмысленного становится освоением, выгоднее боя. */
    public static Gain meditate(TechniqueProgress current, double wisdom, long day) {
        return meditate(current, wisdom, day, Arts.MAX_STARS);
    }

    public static Gain meditate(TechniqueProgress current, double wisdom, long day, int artCap) {
        TechniqueProgress cooled = cool(current, day);
        if (cooled.unprocessed() <= 0.0D) {
            if (!cooled.atCap() && cooled.progress() >= need(cooled.stars())) {
                return advance(cooled, 0.0D, true, artCap);
            }
            return new Gain(cooled, List.of());
        }
        double taken = Math.min(cooled.unprocessed(), MEDITATION_RATE);
        TechniqueProgress rest = new TechniqueProgress(cooled.stars(), cooled.progress(),
                cooled.unprocessed() - taken, cooled.day(), cooled.cap());
        return advance(rest, taken * MEDITATION_GAIN * wisdomFactor(wisdom), true, artCap);
    }

    /**
     * Озарение от страницы или перечитанной книги (docs/design/24-bandit-camp.md §3): освоение
     * сразу, мимо неосмысленного, но не выше предела манускрипта.
     */
    public static Gain study(TechniqueProgress current, double amount, long day) {
        return study(current, amount, day, Arts.MAX_STARS, false);
    }

    public static Gain study(TechniqueProgress current, double amount, long day, int artCap, boolean insight) {
        return advance(cool(current, day), Math.max(0.0D, amount), insight, artCap);
    }

    /** Наутро неосмысленное остывает: связь «подрался — сел осмыслить». */
    public static TechniqueProgress cool(TechniqueProgress current, long day) {
        if (day > current.day() && current.unprocessed() > 0.0D) {
            return new TechniqueProgress(current.stars(), current.progress(), 0.0D, day, current.cap());
        }
        return current;
    }

    private static Gain advance(TechniqueProgress current, double amount, boolean insight, int artCap) {
        int stars = current.stars();
        double progress = current.progress() + amount;
        List<Integer> reached = new ArrayList<>();
        int limit = Math.min(current.cap(), artCap);
        // Следующая звезда — только после полного предыдущего; выше предела манускрипта не растёт.
        while (stars < limit && progress >= need(stars)) {
            // Store all practice at the wall; insight consumes it without resetting mastery.
            if (!insight && stars >= insightStar(artCap)) break;
            progress -= need(stars);
            stars++;
            reached.add(stars);
        }
        if (stars >= limit) {
            progress = 0.0D;
        }
        return new Gain(new TechniqueProgress(stars, progress, current.unprocessed(), current.day(),
                current.cap()), List.copyOf(reached));
    }

    /**
     * Звёзды, с которых начинается только что выученная техника. Простую мудрый проходит
     * сразу на несколько звёзд (§18: «высокий ранг проскакивает первые уровни»); крутую —
     * всегда с нуля.
     */
    public static int startStars(TechniqueTier tier, int cap, double wisdom) {
        return startStars(tier, cap, wisdom, Arts.MAX_STARS);
    }

    public static int startStars(TechniqueTier tier, int cap, double wisdom, int artCap) {
        if (tier != TechniqueTier.BASIC) {
            return 0;
        }
        int skipped = (int) Math.floor(Math.max(0.0D, wisdom) / WISDOM_PER_SKIPPED_STAR);
        return Math.min(skipped, Math.max(0, Math.min(cap - 1, insightStar(artCap))));
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
