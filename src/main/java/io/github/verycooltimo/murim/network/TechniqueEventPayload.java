package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Сервер → клиент: что произошло с техникой. Клиент по этому пакету проигрывает анимацию,
 * эффекты и hit stop.
 *
 * <p>Один пакет на все события вместо трёх разных: событий мало, различаются они только
 * перечислением, а лишние типы payload'ов пришлось бы регистрировать и поддерживать по отдельности.
 *
 * @param event      что случилось
 * @param techniqueId какая техника
 * @param sourceId   сетевой идентификатор применяющей сущности
 * @param hitStopTicks длительность hit stop в тиках для {@link Event#HIT}; для {@link Event#STARTED} — сколько тиков
 *                    замаха кастер пропустил (старший в тяжёлом споре: замах урезан, эффекты клиента идут с того же тика)
 * @param rightStep authoritative right-side Weight Shift selection; server and client must share this codec
 * @param masteryStars actual art stars for presentation; never substitutes for executionTier
 * @param executionTier       слой освоения применяющего на старте: от него зависят анимация и эффекты формы
 */
public record TechniqueEventPayload(Event event, ResourceLocation techniqueId, int sourceId, int hitStopTicks, int executionTier, boolean rightStep, int masteryStars)
        implements CustomPacketPayload {

    /** Compatibility constructor: other techniques do not select a step side. */
    public TechniqueEventPayload(Event event, ResourceLocation techniqueId, int sourceId, int hitStopTicks, int executionTier) {
        this(event, techniqueId, sourceId, hitStopTicks, executionTier, false);
    }

    /** Existing callers retain their execution tier and step side. */
    public TechniqueEventPayload(Event event, ResourceLocation id, int source, int stop, int tier, boolean right) {
        this(event, id, source, stop, tier, right, 0);
    }

    /** 24 Plum chooses its look from this: full mastery is ★12 (one rule with PlumPresentationRules). */
    public boolean fullMastery() {
        return io.github.verycooltimo.murim.technique.PlumPresentationRules.fullMastery(masteryStars);
    }

    public enum Event {
        /** Техника начата: клиент запускает анимацию с нулевого тика. */
        STARTED,
        /** Есть попадание: клиент включает hit stop и эффект удара. */
        HIT,
        /** Техника прервана досрочно: клиент гасит анимацию и эффекты. */
        CANCELLED,
        /** Техника доиграла штатно: клиент завершает анимацию, а не обрывает её. */
        FINISHED,
        /** Sword Fingers starts the interrupted technique’s full cooldown at collapse. */
        COOLDOWN_RESET
    }

    public static final Type<TechniqueEventPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "technique_event"));

    /**
     * Перечисление по порядковому номеру.
     *
     * <p>Пакет идёт только сервер → клиент, то есть источник доверенный. Проверка границ нужна
     * не от злого умысла, а от рассинхрона версий: у клиента постарше в перечислении может
     * не оказаться нового значения, и лучше погасить технику, чем уронить обработчик.
     */
    private static final StreamCodec<ByteBuf, Event> EVENT_CODEC =
            ByteBufCodecs.VAR_INT.map(
                    id -> id >= 0 && id < Event.values().length ? Event.values()[id] : Event.CANCELLED,
                    Event::ordinal);

    public static final StreamCodec<RegistryFriendlyByteBuf, TechniqueEventPayload> STREAM_CODEC =
            // API: read-only minecraft-src/network/codec/StreamCodec.java#of; composite is limited to six fields.
            StreamCodec.of((buf, p) -> {
                EVENT_CODEC.encode(buf, p.event());
                ResourceLocation.STREAM_CODEC.encode(buf, p.techniqueId());
                ByteBufCodecs.VAR_INT.encode(buf, p.sourceId());
                ByteBufCodecs.VAR_INT.encode(buf, p.hitStopTicks());
                ByteBufCodecs.VAR_INT.encode(buf, p.executionTier());
                ByteBufCodecs.BOOL.encode(buf, p.rightStep());
                ByteBufCodecs.VAR_INT.encode(buf, p.masteryStars());
            }, buf -> new TechniqueEventPayload(EVENT_CODEC.decode(buf), ResourceLocation.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.VAR_INT.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
