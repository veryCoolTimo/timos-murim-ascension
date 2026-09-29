package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Состояние медитации для клиента: поза, подсказка удержания, момент рождения семени.
 *
 * @param active    идёт ли сессия
 * @param beats     пройденные такты создания даньтяня (3 — семя уже есть)
 * @param ticks     длительность сессии
 * @param holdTicks сколько кольцо удерживалось
 * @param event     разовое событие: итог такта или рождение семени
 */
public record SyncMeditationPayload(boolean active, int beats, int ticks, int holdTicks, Event event)
        implements CustomPacketPayload {

    /**
     * Разовое событие. {@code SCATTER} и {@code SETTLE} — итог такта до семени: сессии
     * идут подряд, пока игрок сидит, и клиент узнаёт о смене такта только из события.
     */
    public enum Event { NONE, SEED, SCATTER, SETTLE }

    public static final Type<SyncMeditationPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sync_meditation"));

    private static final StreamCodec<ByteBuf, Event> EVENT_CODEC =
            ByteBufCodecs.idMapper(i -> Event.values()[i], Event::ordinal);

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncMeditationPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, SyncMeditationPayload::active,
                    ByteBufCodecs.VAR_INT, SyncMeditationPayload::beats,
                    ByteBufCodecs.VAR_INT, SyncMeditationPayload::ticks,
                    ByteBufCodecs.VAR_INT, SyncMeditationPayload::holdTicks,
                    EVENT_CODEC, SyncMeditationPayload::event,
                    SyncMeditationPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
