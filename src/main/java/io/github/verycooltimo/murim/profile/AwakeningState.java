package io.github.verycooltimo.murim.profile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Mth;

/**
 * Создание даньтяня: единственная церемония, после которой игроку доступны техники.
 *
 * <p>Отдельно от {@link RitualState}. Циркуляция — рутинное действие, которое игрок
 * повторяет сотни раз; создание даньтяня происходит один раз за жизнь персонажа и имеет
 * постановку. Смешивать их в одном состоянии значило бы навесить на рутину церемониальную
 * камеру и наоборот.
 *
 * <p>Неизменяемая запись: состояние читается из тика, из обработчика урона и из сети,
 * и общий изменяемый объект дал бы гонки.
 *
 * @param phase      текущая фаза
 * @param tick       тик от начала ФАЗЫ, а не от начала церемонии
 * @param foundation выбранное основание или {@code null}, пока выбор не сделан
 * @param depth      на какой высоте игрок остановил поток, от 0 до 1
 */
public record AwakeningState(Phase phase, int tick, Foundation foundation, float depth) {

    /**
     * Фазы церемонии.
     *
     * <p>Длительности — в тиках, потому что это игровые фазы; визуальные кривые внутри них
     * считаются по дробному возрасту в рендере (правило 04).
     */
    public enum Phase {
        /** Церемония не идёт. */
        IDLE(0),
        /** Успокоение: игрок садится, дыхание выравнивается, тело ещё тёмное. */
        SETTLE(50),
        /** По телу проступают жилы — от конечностей к средоточию. */
        VEINS(80),
        /** Потоки стекаются вниз, в точке под пупком собирается ядро. */
        CORE(60),
        /** Выбор основания. Ждёт игрока и сама не заканчивается. */
        CHOICE(-1),
        /** Печать: выбор врастает в тело и остаётся видимым навсегда. */
        SEAL(45);

        private final int ticks;

        Phase(int ticks) {
            this.ticks = ticks;
        }

        /** Длительность фазы; отрицательная означает ожидание игрока. */
        public int ticks() {
            return ticks;
        }

        public boolean waitsForPlayer() {
            return ticks < 0;
        }
    }

    public static final AwakeningState IDLE = new AwakeningState(Phase.IDLE, 0, null, 0.0F);

    public static final Codec<AwakeningState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("phase").forGetter(state -> state.phase.name()),
            Codec.INT.fieldOf("tick").forGetter(AwakeningState::tick),
            // Пустая строка вместо отсутствующего поля: Codec.optionalFieldOf на enum-е
            // разворачивается в куда более шумную схему ради одного значения.
            Codec.STRING.fieldOf("foundation")
                    .forGetter(state -> state.foundation == null ? "" : state.foundation.id()),
            Codec.FLOAT.fieldOf("depth").forGetter(AwakeningState::depth)
    ).apply(i, (phase, tick, foundation, depth) ->
            new AwakeningState(Phase.valueOf(phase), tick, Foundation.byId(foundation), depth)));

    public AwakeningState {
        if (tick < 0) {
            throw new IllegalArgumentException("Отрицательный тик церемонии");
        }
        if (phase == null) {
            throw new IllegalArgumentException("Фаза церемонии не задана");
        }
    }

    public boolean active() {
        return phase != Phase.IDLE;
    }

    /** Ждёт ли церемония выбора игрока прямо сейчас. */
    public boolean awaitingChoice() {
        return phase == Phase.CHOICE;
    }

    public AwakeningState advanced() {
        return new AwakeningState(phase, tick + 1, foundation, depth);
    }

    public AwakeningState withPhase(Phase next) {
        return new AwakeningState(next, 0, foundation, depth);
    }

    public AwakeningState withFoundation(Foundation chosen) {
        return new AwakeningState(phase, tick, chosen, depth);
    }

    /**
     * Игрок остановил поток на этой доле пути.
     *
     * <p>Это единственное место, где игрок влияет на СИЛУ даньтяня, а не только на его
     * природу. Раньше сцена шла сама и результат не зависел ни от чего — автор про это:
     * «ты просто смотришь, как создаётся даньтянь на рандом, где тут геймплей».
     */
    public AwakeningState stoppedAt(float where) {
        return new AwakeningState(phase, tick, foundation, Mth.clamp(where, 0.0F, 1.0F));
    }

    /**
     * Множитель силы основания по высоте остановки.
     *
     * <p>Остановишь рано — даньтянь слабый, но целый. Дотянешь до верха — сильный.
     * Прозеваешь — поток переливается, и церемония срывается совсем. Риск и награда
     * растут вместе, и решение принимает игрок, а не таймер.
     */
    public float strength() {
        return 0.55F + 0.75F * depth;
    }

    /**
     * Доля прожитой фазы от нуля до единицы.
     *
     * <p>Для фазы, ждущей игрока, возвращает единицу: у ожидания нет прогресса, и
     * показывать его как растущую полосу означало бы соврать про наличие таймера.
     */
    public float phaseProgress() {
        if (phase.waitsForPlayer() || phase.ticks() <= 0) {
            return 1.0F;
        }
        return Math.min(1.0F, tick / (float) phase.ticks());
    }

    /**
     * Насколько ярко тело светится в этой фазе, от нуля до единицы.
     *
     * <p>Кривая задана здесь, а не в рендере: она общая для меридианов, ядра и звука,
     * и разъехавшиеся копии одной кривой — источник рассинхрона между слоями.
     */
    public float glow() {
        return switch (phase) {
            case IDLE -> 0.0F;
            case SETTLE -> 0.12F * phaseProgress();
            case VEINS -> 0.12F + 0.58F * phaseProgress();
            case CORE -> 0.70F + 0.30F * phaseProgress();
            case CHOICE -> 1.0F;
            case SEAL -> 1.0F - 0.55F * phaseProgress();
        };
    }
}
