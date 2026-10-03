package io.github.verycooltimo.murim.cultivation;

/**
 * Идущая сессия медитации. Не сохраняется: сессия требует неподвижности, и продолжать
 * её после перезахода значило бы обходить прерывание.
 *
 * @param active  идёт ли сессия
 * @param ticks   сколько тиков длится сессия
 * @param holding держит ли игрок клавишу сжатия прямо сейчас (присылает клиент)
 * @param filter  отсеивать ли примеси: выбор делается перед тем, как сесть
 * @param ring    мини-игра кольца на тактах 2 и 3, иначе {@code null}
 * @param breakthrough тики идущей сцены прорыва, {@code -1} — прорыва нет (docs/design/19 §3е)
 */
public record MeditationState(boolean active, int ticks, boolean holding, boolean filter, RingMinigame ring,
                              int breakthrough) {

    public static final MeditationState IDLE = new MeditationState(false, 0, false, true, null, -1);

    /** Новая сессия на данном такте: с кольцом, если такт играется. */
    public static MeditationState started(boolean filter, int beats) {
        return new MeditationState(true, 0, false, filter,
                beats == 1 || beats == 2 ? RingMinigame.start() : null, -1);
    }

    public MeditationState tick(RingMinigame nextRing) {
        return new MeditationState(active, ticks + 1, holding, filter, nextRing,
                breakthrough >= 0 ? breakthrough + 1 : -1);
    }

    public MeditationState withHolding(boolean value) {
        return new MeditationState(active, ticks, value, filter, ring, breakthrough);
    }

    /** Начало сцены прорыва ({@code 0}) или её конец ({@code -1}). */
    public MeditationState withBreakthrough(int value) {
        return new MeditationState(active, ticks, holding, filter, ring, value);
    }

    public boolean breakingThrough() {
        return active && breakthrough >= 0;
    }
}
