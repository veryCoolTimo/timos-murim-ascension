package io.github.verycooltimo.murim.cultivation;

/**
 * Идущая сессия медитации. Не сохраняется: сессия длится секунды и требует неподвижности,
 * продолжать её после перезахода значило бы обходить прерывание.
 *
 * @param active    идёт ли сессия
 * @param ticks     сколько тиков длится сессия
 * @param holdTicks сколько тиков кольцо удерживалось в окне удержания
 * @param holding   держит ли игрок клавишу удержания прямо сейчас (присылает клиент)
 * @param filter    отсеивать ли примеси: выбор делается перед тем, как сесть
 */
public record MeditationState(boolean active, int ticks, int holdTicks, boolean holding, boolean filter) {

    public static final MeditationState IDLE = new MeditationState(false, 0, 0, false, true);

    public static MeditationState started(boolean filter) {
        return new MeditationState(true, 0, 0, false, filter);
    }

    public MeditationState tick(boolean countHold) {
        return new MeditationState(active, ticks + 1, holdTicks + (countHold ? 1 : 0), holding, filter);
    }

    public MeditationState withHolding(boolean value) {
        return new MeditationState(active, ticks, holdTicks, value, filter);
    }
}
