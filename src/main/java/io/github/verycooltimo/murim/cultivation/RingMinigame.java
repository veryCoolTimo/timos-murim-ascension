package io.github.verycooltimo.murim.cultivation;

import java.util.function.DoubleSupplier;

/**
 * Мини-игра «давление кольца» — такты 2 и 3 создания даньтяня (решение автора 30.09).
 *
 * <p>Одна клавиша: держишь — кольцо ци сжимается, отпускаешь — расширяется. Кольцо надо
 * держать в светлой полосе. Перетянул — кольцо трещит, отпустил — рассыпается; и то и
 * другое горит красным и копит напряжение. Напряжение до конца — искажение ци.
 * Устойчивость до конца — такт пройден.
 *
 * <ul>
 *   <li>такт 2 «удержи кольцо»: полоса широкая и медленно плавает;</li>
 *   <li>такт 3 «сожми в семя»: полоса ползёт к центру и сужается — кольцо надо дожать
 *       до точки, ровно то, что показывает визуал стягивания.</li>
 * </ul>
 *
 * <p>Без мира и сети: считается на сервере, правила проверяются юнит-тестами.
 * Радиус нормирован: 1 — широкое рыхлое кольцо, 0 — точка.
 *
 * @param radius    радиус кольца, 0..1
 * @param stability устойчивость, 0..1; единица — такт пройден
 * @param strain    напряжение, 0..1; единица — искажение ци
 * @param surge     текущий толчок ци, добавляется к радиусу каждый тик и затухает
 * @param ticks     тиков с начала игры
 */
public record RingMinigame(double radius, double stability, double strain, double surge, int ticks) {

    public enum Result { PLAYING, PASSED, BACKLASH }

    /** Сжатие за тик при зажатой клавише и расширение при отпущенной. */
    static final double SQUEEZE = 0.012D;
    static final double RELEASE = 0.009D;

    /** Устойчивость внутри полосы: полная — за 12 секунд чистого удержания. */
    static final double STABILITY_GAIN = 1.0D / 240.0D;
    static final double STABILITY_LOSS = 1.0D / 600.0D;

    /** Напряжение вне полосы: базовое плюс за глубину промаха. */
    static final double STRAIN_BASE = 1.0D / 200.0D;
    static final double STRAIN_PER_MISS = 0.03D;
    static final double STRAIN_RELIEF = 1.0D / 300.0D;

    /** Первые три секунды напряжение не копится: игрок осваивается. */
    static final int GRACE_TICKS = 60;

    public static RingMinigame start() {
        return new RingMinigame(0.85D, 0.0D, 0.0D, 0.0D, 0);
    }

    /** Центр полосы на данном такте и тике. */
    public static double bandCentre(int beat, int ticks) {
        if (beat >= 2) {
            // Сжатие в семя: к концу полоса упирается почти в центр.
            double k = Math.min(1.0D, ticks / 500.0D);
            return 0.62D - 0.52D * k + 0.05D * Math.sin(ticks * 0.05D);
        }
        return 0.55D + 0.16D * Math.sin(ticks * 0.021D) + 0.05D * Math.sin(ticks * 0.067D);
    }

    /** Полуширина полосы: на третьем такте уже. */
    public static double bandHalfWidth(int beat, int ticks) {
        if (beat >= 2) {
            return 0.11D - 0.04D * Math.min(1.0D, ticks / 500.0D);
        }
        return 0.13D;
    }

    /** Промах: 0 — в полосе, больше нуля — насколько вышел; знак — сторона. */
    public double miss(int beat) {
        double centre = bandCentre(beat, ticks);
        double half = bandHalfWidth(beat, ticks);
        double d = radius - centre;
        if (Math.abs(d) <= half) {
            return 0.0D;
        }
        return d > 0 ? d - half : d + half;
    }

    /**
     * Один тик.
     *
     * @param holding зажата ли клавиша
     * @param beat    такт (1 — удержание, 2 — сжатие в семя)
     * @param random  источник случайности 0..1 для толчков ци
     */
    public RingMinigame step(boolean holding, int beat, DoubleSupplier random) {
        // Толчки ци: раз в полторы-три секунды кольцо дёргает в случайную сторону.
        // Без них одна клавиша решалась бы ритмичным нажатием не глядя.
        double nextSurge = surge * 0.9D;
        double chance = beat >= 2 ? 1.0D / 30.0D : 1.0D / 45.0D;
        if (random.getAsDouble() < chance) {
            double strength = beat >= 2 ? 0.012D : 0.009D;
            nextSurge += (random.getAsDouble() < 0.5D ? -1.0D : 1.0D) * strength;
        }
        double nextRadius = radius + (holding ? -SQUEEZE : RELEASE) + nextSurge;
        nextRadius = Math.max(0.0D, Math.min(1.0D, nextRadius));
        RingMinigame moved = new RingMinigame(nextRadius, stability, strain, nextSurge, ticks + 1);

        double miss = Math.abs(moved.miss(beat));
        double nextStability;
        double nextStrain;
        if (miss == 0.0D) {
            nextStability = stability + STABILITY_GAIN;
            nextStrain = strain - STRAIN_RELIEF;
        } else {
            nextStability = stability - STABILITY_LOSS;
            nextStrain = ticks < GRACE_TICKS ? strain : strain + STRAIN_BASE + STRAIN_PER_MISS * miss;
        }
        return new RingMinigame(nextRadius, clamp(nextStability), clamp(nextStrain), nextSurge, ticks + 1);
    }

    public Result result() {
        if (strain >= 1.0D) {
            return Result.BACKLASH;
        }
        if (stability >= 1.0D) {
            return Result.PASSED;
        }
        return Result.PLAYING;
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
