package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.profile.DantianProfile;
import net.minecraft.util.Mth;

/**
 * Правила создания даньтяня и смены метода — без мира, игрока и пакетов.
 *
 * <p>Вынесено отдельно, чтобы правила проверялись юнит-тестами: сервис медитации только
 * решает, когда сессия закончилась и удержано ли кольцо, а что из этого следует — здесь.
 *
 * <p>Три такта по docs/design/19-dantian-qi-meditation.md §3а:
 * <ol>
 *   <li>первое ощущение — неудача по замыслу: ци рассеивается, так показывается препятствие;</li>
 *   <li>удержание — кольцо у пупка нужно удержать; не удержал — такт не засчитан;</li>
 *   <li>рождение семени — профиль даньтяня получает природу, чистоту и ёмкость метода.</li>
 * </ol>
 */
public final class SeedLogic {

    /** Чем закончилась сессия медитации до семени. */
    public enum Outcome {
        /** Метода нет — практиковать нечего. */
        NO_METHOD,
        /** Такт 1: тепло появилось и рассеялось. */
        FIRST_FEELING,
        /** Такт 2 пройден: кольцо удержано. */
        HELD,
        /** Такт 2 не пройден: кольцо рассыпалось, повторить. */
        SLIPPED,
        /** Такт 3: родилось семя, профиль изменён. */
        SEED,
        /** Семя уже есть — дальше медитация работает как ускоритель. */
        ALREADY_SEEDED
    }

    public record SessionResult(Outcome outcome, CultivationState state) {
    }

    /**
     * Итог одной сессии медитации до семени.
     *
     * @param ringHeld удержал ли игрок кольцо (имеет смысл только на втором такте)
     */
    public static SessionResult finishSession(CultivationState state, boolean ringHeld) {
        if (state.method().isEmpty()) {
            return new SessionResult(Outcome.NO_METHOD, state);
        }
        return switch (state.beats()) {
            case 0 -> new SessionResult(Outcome.FIRST_FEELING, state.withBeats(1));
            case 1 -> ringHeld
                    ? new SessionResult(Outcome.HELD, state.withBeats(2))
                    : new SessionResult(Outcome.SLIPPED, state);
            case 2 -> new SessionResult(Outcome.SEED, state.withBeats(CultivationState.SEEDED));
            default -> new SessionResult(Outcome.ALREADY_SEEDED, state);
        };
    }

    /**
     * Профиль в момент рождения семени.
     *
     * <p>Чистота — от метода и от того, сколько примесей игрок отсеял: у метода с большой долей
     * примесей неотсеянный поток заметно мутит ци, у сектового почти не влияет.
     *
     * @param filteredShare доля отсеянных сгустков, 0..1 (1 — отсеивал всё)
     */
    public static DantianProfile seedProfile(DantianProfile base, CultivationMethod method,
                                             double filteredShare) {
        double filtered = Mth.clamp(filteredShare, 0.0D, 1.0D);
        double purity = method.purity() - method.impurity() * (1.0D - filtered) * PURITY_LOSS;
        return base.withAxes(method.capacity(), purity, base.meridians())
                .withTags(method.nature(), method.id().toString())
                .withCirculating(0.0D);
    }

    /** Сколько чистоты съедает поток, если не отсеивать совсем, при доле примесей 1. */
    static final double PURITY_LOSS = 0.6D;

    /** Что произошло при изучении метода со свитка. */
    public enum Change {
        /** Первый метод. */
        LEARNED,
        /** Этот метод уже практикуется. */
        SAME,
        /** До семени — другой метод заменяет прежний, практика начинается заново. */
        REPLACED_BEFORE_SEED,
        /** После семени, та же природа — часть накопленной ци теряется. */
        SAME_NATURE,
        /** После семени, другая природа — накопленное обнуляется (Myst гл. 126). */
        OTHER_NATURE
    }

    public record LearnResult(Change change, CultivationState state, DantianProfile profile) {
    }

    /** Доля запаса, которая остаётся при смене метода той же природы. */
    static final double SAME_NATURE_KEEP = 0.7D;

    /**
     * Изучение метода со свитка.
     *
     * @param current метод, который практикуется сейчас, или {@code null}
     */
    public static LearnResult learn(CultivationState state, DantianProfile profile,
                                    CultivationMethod method, CultivationMethod current) {
        if (state.method().isEmpty()) {
            return new LearnResult(Change.LEARNED, state.withMethod(method.id()), profile);
        }
        if (state.method().get().equals(method.id())) {
            return new LearnResult(Change.SAME, state, profile);
        }
        if (!state.seeded()) {
            return new LearnResult(Change.REPLACED_BEFORE_SEED,
                    state.withMethod(method.id()).withBeats(0), profile);
        }
        boolean sameNature = current != null && current.nature().equals(method.nature());
        if (sameNature) {
            DantianProfile kept = profile.withPool(profile.pool() * SAME_NATURE_KEEP)
                    .withTags(profile.nature(), method.id().toString());
            return new LearnResult(Change.SAME_NATURE, state.withMethod(method.id()), kept);
        }
        DantianProfile reset = profile.withPool(0.0D).withCirculating(0.0D)
                .withTags(method.nature(), method.id().toString());
        return new LearnResult(Change.OTHER_NATURE, state.withMethod(method.id()), reset);
    }

    private SeedLogic() {
    }
}
