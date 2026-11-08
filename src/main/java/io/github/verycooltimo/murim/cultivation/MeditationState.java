package io.github.verycooltimo.murim.cultivation;

import net.minecraft.resources.ResourceLocation;

/**
 * Идущая сессия медитации. Не сохраняется: сессия требует неподвижности, и продолжать
 * её после перезахода значило бы обходить прерывание.
 *
 * <p>Метод (Шесть Равновесий) — маршрут дыхания; ЗАНЯТИЕ — отдельный выбор (hua-main-1810 §1): «Очищение» (обычный сбор ци,
 * {@code echo == null}) или «Эхо» — осмысление искусства, которое игрок держал в руке, когда сел. Выбор делается до сидения
 * и не требует новой клавиши; сменить занятие — встать и сесть с другим предметом в руке. Затухания притока нет.
 *
 * @param active  идёт ли сессия
 * @param ticks   сколько тиков длится сессия
 * @param holding держит ли игрок клавишу сжатия прямо сейчас (присылает клиент)
 * @param filter  отсеивать ли примеси: выбор делается перед тем, как сесть
 * @param ring    мини-игра кольца на тактах 2 и 3, иначе {@code null}
 * @param breakthrough тики идущей сцены прорыва, {@code -1} — прорыва нет (docs/design/19 §3е)
 * @param echo    искусство, которое осмысляет Эхо; {@code null} — идёт Очищение
 * @param echoTicks сколько тиков Эха уже прошло на этом искусстве (30 с обрабатывают весь резерв)
 * @param guarded  страж сцены прорыва уже принял первый срыв (hua-main-1810 §4): следующий прерывает сцену, но запас цел
 */
public record MeditationState(boolean active, int ticks, boolean holding, boolean filter, RingMinigame ring,
                              int breakthrough, ResourceLocation echo, int echoTicks, boolean guarded) {

    public static final MeditationState IDLE = new MeditationState(false, 0, false, true, null, -1, null, 0, false);

    /** Сессия без занятия-Эха: «Очищение». */
    public MeditationState(boolean active, int ticks, boolean holding, boolean filter, RingMinigame ring,
                           int breakthrough) {
        this(active, ticks, holding, filter, ring, breakthrough, null, 0, false);
    }

    /** Новая сессия на данном такте: с кольцом, если такт играется. */
    public static MeditationState started(boolean filter, int beats) {
        return new MeditationState(true, 0, false, filter,
                beats == 1 || beats == 2 ? RingMinigame.start(beats, beats == 2 ? 0.55D : 0.72D) : null, -1);
    }

    /** Continue the sitting without losing the final radius of the holding round. */
    public MeditationState nextBeat(int beats) {
        MeditationState next = started(filter, beats);
        return beats == 2 && ring != null
                ? new MeditationState(true, 0, false, filter, RingMinigame.start(2, ring.radius()), -1)
                : next;
    }

    public MeditationState tick(RingMinigame nextRing) {
        return new MeditationState(active, ticks + 1, holding, filter, nextRing,
                breakthrough >= 0 ? breakthrough + 1 : -1, echo, echo == null ? 0 : echoTicks + 1, guarded);
    }

    public MeditationState withHolding(boolean value) {
        return new MeditationState(active, ticks, value, filter, ring, breakthrough, echo, echoTicks, guarded);
    }

    /** Начало сцены прорыва ({@code 0}) или её конец ({@code -1}). */
    public MeditationState withBreakthrough(int value) {
        return new MeditationState(active, ticks, holding, filter, ring, value, echo, echoTicks, false);
    }

    /** The guardian has taken the first disruption of this scene (or not yet). */
    public MeditationState withGuarded(boolean value) {
        return new MeditationState(active, ticks, holding, filter, ring, breakthrough, echo, echoTicks, value);
    }

    /** Занятие сессии: Эхо искусства {@code art}, или {@code null} — Очищение. */
    public MeditationState withEcho(ResourceLocation art) {
        return new MeditationState(active, ticks, holding, filter, ring, breakthrough, art, 0, guarded);
    }

    public boolean breakingThrough() {
        return active && breakthrough >= 0;
    }

    /** Идёт ли Эхо (а не Очищение). */
    public boolean echoing() {
        return active && echo != null;
    }
}
