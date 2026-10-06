package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.entity.SectPose;

/**
 * Свой ритм у каждого в цикле позы (автор 06.10: «они как роботы одну и ту же анимацию в одно время показывают,
 * выглядит как хор»). Время клипа считается у каждой сущности отдельно: сдвиг фазы и темп (±13 %) по UUID, а каждый
 * цикл ещё и чуть свой — иногда пауза между циклами (между глотками за столом), в паузе человек может глянуть на
 * соседа или опустить чашку на колени. Чистая функция без мира: юнит-тест {@code LoopVarietyTest}.
 *
 * <p>Строй (формы Шести Равновесий) сюда не идёт: он синхронный намеренно — его клипы идут мимо.
 */
public final class LoopVariety {

    /** Циклов в повторяющемся узоре: дальше узор повторяется (чтобы не перебирать часы сна). */
    static final int PATTERN = 8;

    /**
     * Кадр цикла.
     *
     * @param clip   время клипа, секунд (0..длина)
     * @param pause  0..1 — глубина паузы между циклами (1 — посередине паузы)
     * @param glance −1..1 — взгляд в сторону соседа в паузе
     * @param lower  0..1 — опустил чашку (руки) на колени в долгой паузе
     */
    public record Frame(float clip, float pause, float glance, float lower) {
    }

    /** Насколько часто и надолго паузы между циклами в этой позе. */
    record Rest(double chance, double min, double max, double glance, double lower) {
        static final Rest NONE = new Rest(0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    private LoopVariety() {
    }

    static Rest rest(SectPose pose) {
        return switch (pose) {
            // За столом: между глотками — пауза, взгляд на соседа, иногда чашка на коленях.
            case EAT -> new Rest(0.6D, 0.5D, 2.6D, 0.45D, 0.35D);
            case GRIND -> new Rest(0.35D, 0.3D, 1.2D, 0.3D, 0.0D);
            case SWEEP -> new Rest(0.3D, 0.3D, 1.0D, 0.25D, 0.0D);
            case TALK, READ, COUNT, BREW -> new Rest(0.3D, 0.4D, 1.6D, 0.3D, 0.0D);
            case GUARD -> new Rest(0.25D, 0.5D, 2.0D, 0.0D, 0.0D);
            default -> Rest.NONE;
        };
    }

    /** Сид по сущности: UUID одинаков на клиенте и сервере и у каждого свой. */
    public static long seed(java.util.UUID id) {
        return mix(id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 17));
    }

    /** Темп этого человека: 0,87..1,13. */
    public static float tempo(long seed) {
        return (float) (0.87D + 0.26D * unit(seed, 1));
    }

    /** Сдвиг фазы, 0..1 доли цикла (для простых синусов — дыхание, покачивание в разговоре). */
    public static float phase(long seed) {
        return (float) unit(seed, 2);
    }

    /**
     * Время клипа для зацикленной позы.
     *
     * @param seed {@link #seed}
     * @param sec  секунд с прихода позы (у сущности своё)
     * @param len  длина клипа, секунд
     */
    public static Frame frame(long seed, SectPose pose, float sec, float len) {
        if (len <= 0.0F) {
            return new Frame(0.0F, 0.0F, 0.0F, 0.0F);
        }
        Rest rest = rest(pose);
        double tempo = tempo(seed);
        double total = 0.0D;
        for (int k = 0; k < PATTERN; k++) {
            total += cycle(seed, k, len, tempo) + gap(seed, k, rest);
        }
        // Сдвиг фазы: кто-то садится уже посреди глотка.
        double t = (sec + unit(seed, 3) * total) % total;
        for (int k = 0; k < PATTERN; k++) {
            double c = cycle(seed, k, len, tempo);
            if (t < c) {
                return new Frame((float) (t / c * len), 0.0F, 0.0F, 0.0F);
            }
            t -= c;
            double g = gap(seed, k, rest);
            if (t < g) {
                // Мягко в паузу и из неё (по 0,35 с).
                double env = Math.min(1.0D, Math.min(t, g - t) / 0.35D);
                env = env * env * (3.0D - 2.0D * env);
                float glance = unit(seed, 100 + k) < rest.glance() ? (float) (env * (unit(seed, 200 + k) < 0.5D ? -1.0D : 1.0D)) : 0.0F;
                float lower = g > 1.6D && unit(seed, 300 + k) < rest.lower() ? (float) env : 0.0F;
                // Пауза — на кадре начала цикла: у циклов позы он же кадр покоя (чашка у груди, метла внизу).
                return new Frame(0.0F, (float) env, glance, lower);
            }
            t -= g;
        }
        return new Frame(0.0F, 0.0F, 0.0F, 0.0F);
    }

    /** Длина k-го цикла: общий темп человека и ±7 % у каждого цикла. */
    static double cycle(long seed, int k, float len, double tempo) {
        return len / (tempo * (0.93D + 0.14D * unit(seed, 10 + k)));
    }

    /** Пауза после k-го цикла, секунд (0 — без паузы). */
    static double gap(long seed, int k, Rest rest) {
        if (rest.chance() <= 0.0D || unit(seed, 30 + k) >= rest.chance()) {
            return 0.0D;
        }
        return rest.min() + (rest.max() - rest.min()) * unit(seed, 50 + k);
    }

    /** Детерминированное число 0..1 по сиду и номеру. */
    static double unit(long seed, int salt) {
        return (mix(seed + 0x9E3779B97F4A7C15L * (salt + 1)) >>> 11) * 0x1.0p-53;
    }

    /** SplitMix64. */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
