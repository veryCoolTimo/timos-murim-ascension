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
 * @param hitStopTicks длительность hit stop в тиках; осмысленно только для {@link Event#HIT}
 * @param layer       слой освоения применяющего на старте: от него зависят анимация и эффекты формы
 */
public record TechniqueEventPayload(Event event, ResourceLocation techniqueId, int sourceId, int hitStopTicks, int layer)
        implements CustomPacketPayload {

    public enum Event {
        /** Техника начата: клиент запускает анимацию с нулевого тика. */
        STARTED,
        /** Есть попадание: клиент включает hit stop и эффект удара. */
        HIT,
        /** Техника прервана досрочно: клиент гасит анимацию и эффекты. */
        CANCELLED,
        /** Техника доиграла штатно: клиент завершает анимацию, а не обрывает её. */
        FINISHED
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
            StreamCodec.composite(
                    EVENT_CODEC, TechniqueEventPayload::event,
                    ResourceLocation.STREAM_CODEC, TechniqueEventPayload::techniqueId,
                    ByteBufCodecs.VAR_INT, TechniqueEventPayload::sourceId,
                    ByteBufCodecs.VAR_INT, TechniqueEventPayload::hitStopTicks,
                    ByteBufCodecs.VAR_INT, TechniqueEventPayload::layer,
                    TechniqueEventPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
