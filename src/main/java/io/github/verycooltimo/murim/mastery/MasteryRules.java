package io.github.verycooltimo.murim.mastery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Правила изучения, освоения и мудрости — без мира, игрока и пакетов (docs/design/19 §3г).
 *
 * <p>Решения автора 30.09: бой качает напрямую; тренировка тоже, но меньше; медитация
 * переводит неосмысленное в освоение выгоднее; слой 0 после чтения; жёсткий порог на
 * изучение; мудрость косвенная и растёт от новых техник и постижения крутых.
 * Все числа здесь — черновые, проверяются на игре.
 */
public final class MasteryRules {

    /** Откуда пережитое. */
    public enum Source {
        /** Попадание по живому противнику. */
        FIGHT(1.0D),
        /** Манекен, удар в воздух: тренировка качает, но меньше (автор: «тренируя технику»). */
        TRAINING(0.3D);

        final double weight;

        Source(double weight) {
            this.weight = weight;
        }
    }

    /** Доля пережитого, сразу идущая в освоение: «через бой тоже качать». */
    static final double DIRECT_SHARE = 0.5D;

    /** Во сколько раз медитация выгоднее для неосмысленного. */
    static final double MEDITATION_GAIN = 3.5D;

    /** Сколько неосмысленного медитация перерабатывает за тик. */
    static final double MEDITATION_RATE = 0.05D;

    /** Сколько пережитого нужно на первый слой; каждый следующий дороже. */
    static final double FIRST_LAYER = 12.0D;
    static final double LAYER_GROWTH = 1.6D;

    /** Мудрость за изучение новой техники и за постижение крутой. */
    static final double WISDOM_PER_TECHNIQUE = 1.0D;
    static final double WISDOM_PER_COMPREHENSION = 4.0D;

    /** Сколько мудрости нужно на каждый проскакиваемый слой простой техники. */
    static final double WISDOM_PER_SKIPPED_LAYER = 6.0D;

    /** Сколько пережитого нужно, чтобы пройти слой {@code layer} → {@code layer + 1}. */
    public static double need(int layer) {
        return FIRST_LAYER * Math.pow(LAYER_GROWTH, layer);
    }

    /** Мудрость ускоряет освоение: +5% за единицу, без потолка, но медленно. */
    public static double wisdomFactor(double wisdom) {
        return 1.0D + 0.05D * Math.max(0.0D, wisdom);
    }

    /** Итог прироста: новое состояние и пройденные слои — для озарения. */
    public record Gain(TechniqueProgress progress, List<Integer> layersReached) {
    }

    /**
     * Пережитое в бою или на тренировке.
     *
     * @param amount сколько пережито (например, одно применение — 1)
     * @param day    текущий игровой день: неосмысленное прошлых дней остывает
     */
    public static Gain experience(TechniqueProgress current, Source source, double amount,
                                  double wisdom, long day) {
        TechniqueProgress cooled = cool(current, day);
        double total = amount * source.weight * wisdomFactor(wisdom);
        double direct = total * DIRECT_SHARE;
        TechniqueProgress withLeftover = new TechniqueProgress(cooled.layer(), cooled.progress(),
                cooled.unprocessed() + (total - direct), day, cooled.cap());
        return advance(withLeftover, direct);
    }

    /** Один тик медитации: часть неосмысленного становится освоением, выгоднее боя. */
    public static Gain meditate(TechniqueProgress current, double wisdom, long day) {
        TechniqueProgress cooled = cool(current, day);
        if (cooled.unprocessed() <= 0.0D) {
            return new Gain(cooled, List.of());
        }
        double taken = Math.min(cooled.unprocessed(), MEDITATION_RATE);
        TechniqueProgress rest = new TechniqueProgress(cooled.layer(), cooled.progress(),
                cooled.unprocessed() - taken, cooled.day(), cooled.cap());
        return advance(rest, taken * MEDITATION_GAIN * wisdomFactor(wisdom));
    }

    /** Наутро неосмысленное остывает: связь «подрался — сел осмыслить». */
    public static TechniqueProgress cool(TechniqueProgress current, long day) {
        if (day > current.day() && current.unprocessed() > 0.0D) {
            return new TechniqueProgress(current.layer(), current.progress(), 0.0D, day, current.cap());
        }
        return current;
    }

    private static Gain advance(TechniqueProgress current, double amount) {
        int layer = current.layer();
        double progress = current.progress() + amount;
        List<Integer> reached = new ArrayList<>();
        // Следующий слой — только после полного предыдущего; выше предела манускрипта не растёт.
        while (layer < current.cap() && progress >= need(layer)) {
            progress -= need(layer);
            layer++;
            reached.add(layer);
        }
        if (layer >= current.cap()) {
            progress = 0.0D;
        }
        return new Gain(new TechniqueProgress(layer, progress, current.unprocessed(), current.day(),
                current.cap()), List.copyOf(reached));
    }

    /**
     * Слой, с которого начинается только что выученная техника. Простую мудрый проходит
     * сразу на несколько слоёв (§18: «высокий ранг проскакивает первые уровни»); крутую —
     * всегда с нуля.
     */
    public static int startLayer(TechniqueTier tier, int cap, double wisdom) {
        if (tier != TechniqueTier.BASIC) {
            return 0;
        }
        int skipped = (int) Math.floor(Math.max(0.0D, wisdom) / WISDOM_PER_SKIPPED_LAYER);
        return Math.min(skipped, Math.max(0, cap - 1));
    }

    /** Мудрость за изучение новой техники: крутые учат больше. */
    public static double wisdomForLearning(TechniqueTier tier) {
        return tier == TechniqueTier.BASIC ? WISDOM_PER_TECHNIQUE : WISDOM_PER_TECHNIQUE * 2.0D;
    }

    /**
     * Постигнута ли крутая техника на этом слое: «нашёл крутую технику и понял её».
     * Порог — три пятых всех слоёв.
     */
    public static boolean comprehends(TechniqueTier tier, int layer, int layers) {
        return tier != TechniqueTier.BASIC && layer >= Math.ceil(layers * 0.6D);
    }

    public static double wisdomForComprehension() {
        return WISDOM_PER_COMPREHENSION;
    }

    /**
     * Жёсткий порог изучения (автор 30.09): чего не хватает, чтобы выучить технику.
     *
     * @param known выученные техники и их слои
     * @return недостающие требования; пусто — можно учить
     */
    public static List<TechniqueRequirement> missing(List<TechniqueRequirement> requires,
                                                     Map<net.minecraft.resources.ResourceLocation, Integer> known) {
        List<TechniqueRequirement> missing = new ArrayList<>();
        for (TechniqueRequirement requirement : requires) {
            Integer layer = known.get(requirement.technique());
            if (layer == null || layer < requirement.layer()) {
                missing.add(requirement);
            }
        }
        return List.copyOf(missing);
    }

    /**
     * Во сколько раз дороже по ци техника на этом слое. Слой 0 — «коряво»: ×1.5;
     * полное освоение — ×0.8. Между ними — линейно (docs/design/19 §3г).
     */
    public static double costFactor(int layer, int layers) {
        double k = layers <= 0 ? 1.0D : Math.min(1.0D, layer / (double) layers);
        return 1.5D - 0.7D * k;
    }

    /** Сила техники на этом слое: слой 0 — ×0.6, полное освоение — ×1.25. */
    public static double powerFactor(int layer, int layers) {
        double k = layers <= 0 ? 1.0D : Math.min(1.0D, layer / (double) layers);
        return 0.6D + 0.65D * k;
    }

    private MasteryRules() {
    }
}
