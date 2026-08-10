package io.github.verycooltimo.murim.profile;

/**
 * Состояние идущего ритуала. Живёт только пока игрок сидит в медитации.
 *
 * <p>Неизменяемая запись, как и состояние техники: ритуал читается из нескольких мест
 * (тик, урон, движение), и общий изменяемый объект тут же дал бы гонки на ровном месте.
 *
 * @param active   идёт ли ритуал
 * @param tick     тик от начала текущего круга
 * @param cycles   сколько кругов уже завершено и накоплено
 * @param strain   напряжение: растёт с каждым кругом и решает, чем кончится жадность
 * @param gathered сколько ци набрано за сеанс
 */
public record RitualState(boolean active, int tick, int cycles, double strain, double gathered) {

    public static final RitualState IDLE = new RitualState(false, 0, 0, 0.0D, 0.0D);

    /** Длительность одного круга циркуляции в тиках. */
    public static final int CYCLE_TICKS = 60;

    public RitualState {
        if (tick < 0 || cycles < 0) {
            throw new IllegalArgumentException("Отрицательные счётчики ритуала");
        }
        if (!(strain >= 0.0D) || !(gathered >= 0.0D)) {
            throw new IllegalArgumentException("Отрицательное или нечисловое значение ритуала");
        }
    }

    public static RitualState started() {
        return new RitualState(true, 0, 0, 0.0D, 0.0D);
    }

    public RitualState advanced(double gain, double strainGain) {
        return new RitualState(active, tick + 1, cycles, strain + strainGain, gathered + gain);
    }

    /** Круг завершён: счётчик тиков обнуляется, круг засчитан. */
    public RitualState cycleDone() {
        return new RitualState(active, 0, cycles + 1, strain, gathered);
    }

    public RitualState stopped() {
        return IDLE;
    }

    /** Доля текущего круга от нуля до единицы — для полосы на экране. */
    public float cycleProgress() {
        return Math.min(1.0F, tick / (float) CYCLE_TICKS);
    }
}
