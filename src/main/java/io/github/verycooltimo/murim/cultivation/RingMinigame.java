package io.github.verycooltimo.murim.cultivation;

import java.util.function.DoubleSupplier;

/**
 * Мини-игра «давление кольца» — такты 2 и 3 создания даньтяня (решение автора 30.09).
 *
 * <p>Одна клавиша: держишь — кольцо ци сжимается, отпускаешь — расширяется. Кольцо надо
 * держать в светлой полосе. Перетянул — кольцо трещит, отпустил — рассыпается; и то и
 * другое горит красным и копит напряжение. Напряжение до конца — искажение ци.
 * Во втором раунде полная устойчивость требует также малого радиуса внутри полосы.
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
 * @param beat      раунд: 1 — удержание, 2 — осаждение
 * @param bandStart начальный радиус полосы осаждения, только на время сессии
 */
public record RingMinigame(double radius, double stability, double strain, double surge, int ticks, int beat, double bandStart) {

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
        return start(1, 0.72D);
    }

    public static RingMinigame start(int beat, double radius) {
        return new RingMinigame(radius, 0.0D, 0.0D, 0.0D, 0, beat, radius);
    }

    /** Actual band, including the radius carried from the preceding round. */
    public double bandCentre() {
        if (beat >= 2) {
            return bandStart + (0.10D - bandStart) * Math.min(1.0D, ticks / 200.0D);
        }
        return 0.55D + 0.075D * Math.sin(ticks * 0.021D) + 0.025D * Math.sin(ticks * 0.067D);
    }

    public double bandHalfWidth() {
        return beat >= 2 ? 0.12D - 0.06D * Math.min(1.0D, ticks / 200.0D) : 0.14D;
    }

    /** Промах: 0 — в полосе, больше нуля — насколько вышел; знак — сторона. */
    public double miss(int beat) {
        double centre = bandCentre();
        double half = bandHalfWidth();
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
        if (result() != Result.PLAYING) {
            return this;
        }
        // Толчки ци: раз в полторы-три секунды кольцо дёргает в случайную сторону.
        // Без них одна клавиша решалась бы ритмичным нажатием не глядя.
        double nextSurge = surge * 0.9D;
        double chance = beat >= 2 ? 1.0D / 30.0D : 1.0D / 45.0D;
        if (random.getAsDouble() < chance) {
            double strength = beat >= 2 ? 0.008D : 0.009D;
            nextSurge += (random.getAsDouble() < 0.5D ? -1.0D : 1.0D) * strength;
        }
        double nextRadius = radius + (holding ? -(beat >= 2 ? 0.008D : SQUEEZE) : (beat >= 2 ? 0.006D : RELEASE)) + nextSurge;
        nextRadius = Math.max(0.0D, Math.min(1.0D, nextRadius));
        RingMinigame moved = new RingMinigame(nextRadius, stability, strain, nextSurge, ticks + 1, beat, bandStart);

        double miss = Math.abs(moved.miss(beat));
        double nextStability;
        double nextStrain;
        if (miss == 0.0D) {
            nextStability = stability + (beat >= 2 ? STABILITY_GAIN : 1.0D / 200.0D);
            nextStrain = strain - STRAIN_RELIEF;
        } else {
            nextStability = stability - STABILITY_LOSS;
            nextStrain = ticks < GRACE_TICKS ? strain : strain + STRAIN_BASE + STRAIN_PER_MISS * miss;
        }
        return new RingMinigame(nextRadius, clamp(nextStability), clamp(nextStrain), nextSurge, ticks + 1, beat, bandStart);
    }

    public Result result() {
        if (strain >= 1.0D) {
            return Result.BACKLASH;
        }
        if (stability >= 1.0D && (beat < 2 || (radius <= 0.16D && miss(beat) == 0.0D))) {
            return Result.PASSED;
        }
        return Result.PLAYING;
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
