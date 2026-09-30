package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Состояние медитации для клиента: поза, ход сессии, мини-игра кольца, события.
 *
 * @param active идёт ли сессия
 * @param beats  пройденные такты создания даньтяня (3 — семя уже есть)
 * @param ticks  длительность сессии
 * @param event  разовое событие: итог такта, рождение семени или искажение ци
 * @param ring   мини-игра кольца; {@link Ring#NONE}, если такт её не играет
 */
public record SyncMeditationPayload(boolean active, int beats, int ticks, Event event, Ring ring)
        implements CustomPacketPayload {

    /**
     * {@code SCATTER}/{@code SETTLE} — итог такта при продолжающемся сидении;
     * {@code BACKLASH} — искажение ци, мини-игра проиграна полностью.
     */
    public enum Event { NONE, SEED, SCATTER, SETTLE, BACKLASH }

    /**
     * Снимок мини-игры: всё в долях радиуса, 0 — точка, 1 — широкое кольцо.
     *
     * @param active    идёт ли мини-игра
     * @param radius    радиус кольца
     * @param centre    центр светлой полосы
     * @param halfWidth полуширина полосы
     * @param stability устойчивость, 1 — такт пройден
     * @param strain    напряжение, 1 — искажение ци
     */
    public record Ring(boolean active, float radius, float centre, float halfWidth, float stability, float strain) {

        public static final Ring NONE = new Ring(false, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);

        public static final StreamCodec<ByteBuf, Ring> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Ring::active,
                ByteBufCodecs.FLOAT, Ring::radius,
                ByteBufCodecs.FLOAT, Ring::centre,
                ByteBufCodecs.FLOAT, Ring::halfWidth,
                ByteBufCodecs.FLOAT, Ring::stability,
                ByteBufCodecs.FLOAT, Ring::strain,
                Ring::new);

        /** Промах относительно полосы: 0 — в полосе, знак — сторона (минус — перетянуто). */
        public float miss() {
            float d = radius - centre;
            if (Math.abs(d) <= halfWidth) {
                return 0.0F;
            }
            return d > 0 ? d - halfWidth : d + halfWidth;
        }
    }

    public static final Type<SyncMeditationPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sync_meditation"));

    private static final StreamCodec<ByteBuf, Event> EVENT_CODEC =
            ByteBufCodecs.idMapper(i -> Event.values()[i], Event::ordinal);

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncMeditationPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, SyncMeditationPayload::active,
                    ByteBufCodecs.VAR_INT, SyncMeditationPayload::beats,
                    ByteBufCodecs.VAR_INT, SyncMeditationPayload::ticks,
                    EVENT_CODEC, SyncMeditationPayload::event,
                    Ring.STREAM_CODEC.cast(), SyncMeditationPayload::ring,
                    SyncMeditationPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
