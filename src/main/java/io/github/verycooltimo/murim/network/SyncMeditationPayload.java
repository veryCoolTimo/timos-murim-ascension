package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Состояние медитации для клиента: поза, ход сессии, мини-игра кольца, события.
 *
 * @param wallReason server-built blocker shared with mentor advice; empty away from a blocked wall
 * @param active идёт ли сессия
 * @param beats  пройденные такты создания даньтяня (3 — семя уже есть)
 * @param ticks  длительность сессии
 * @param event  разовое событие: итог такта, рождение семени или искажение ци
 * @param ring   мини-игра кольца; {@link Ring#NONE}, если такт её не играет
 * @param breakthrough тики сцены прорыва, {@code -1} — прорыва нет
 * @param rank   ранг после события (для титра «Третий ранг»)
 * @param echo   искусство, которое осмысляет Эхо этой сессии; пусто — идёт Очищение
 * @param breathingStars server mastery stars of the slotted breathing method (zero if absent)
 * @param moves  приёмы, которые повторяет двойник Эха (последний бой с этим искусством), по порядку
 */
public record SyncMeditationPayload(boolean active, int beats, int ticks, Event event, Ring ring,
                                    int breakthrough, int rank, Optional<ResourceLocation> echo,
                                    List<ResourceLocation> moves, int breathingStars, Optional<net.minecraft.network.chat.Component> wallReason)
        implements CustomPacketPayload {

    public SyncMeditationPayload(boolean active, int beats, int ticks, Event event, Ring ring,
                                 int breakthrough, int rank, Optional<ResourceLocation> echo,
                                 List<ResourceLocation> moves, int breathingStars) {
        this(active, beats, ticks, event, ring, breakthrough, rank, echo, moves, breathingStars, Optional.empty());
    }

    /** Сессия без Эха: Очищение (или ничего, если сессия не идёт). */
    public SyncMeditationPayload(boolean active, int beats, int ticks, Event event, Ring ring,
                                 int breakthrough, int rank) {
        this(active, beats, ticks, event, ring, breakthrough, rank, Optional.empty(), List.of(), 0);
    }

    /** Existing scene callers without a selected method. */
    public SyncMeditationPayload(boolean active, int beats, int ticks, Event event, Ring ring,
                                 int breakthrough, int rank, Optional<ResourceLocation> echo,
                                 List<ResourceLocation> moves) {
        this(active, beats, ticks, event, ring, breakthrough, rank, echo, moves, 0);
    }

    /** A multiplier only: the existing rank's full effect remains the ceiling. */
    public float auraScale() {
        return Math.max(1, Math.min(12, breathingStars)) / 12.0F;
    }

    public SyncMeditationPayload {
        moves = List.copyOf(moves);
    }

    /**
     * {@code SCATTER}/{@code SETTLE} — итог такта при продолжающемся сидении;
     * {@code BACKLASH} — искажение ци, мини-игра проиграна полностью;
     * {@code BREAKTHROUGH} — началась сцена прорыва, {@code RANK_UP} — прорыв завершён,
     * {@code BROKEN} — прорыв прерван (травма меридиан); {@code GUARDED} — страж принял первый срыв, сцена идёт дальше.
     */
    public enum Event { NONE, SEED, SCATTER, SETTLE, BACKLASH, BREAKTHROUGH, RANK_UP, BROKEN, GUARDED, ECHO_DONE }

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
    // Centre and width are authoritative values from the session, including its carried band start.
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

    // Полей семь, а StreamCodec.composite в 1.21.1 принимает не больше шести —
    // поэтому кодек собран вручную. API: reference/minecraft-src/net/minecraft/network/codec/StreamCodec.java#of
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncMeditationPayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                net.minecraft.network.chat.ComponentSerialization.OPTIONAL_STREAM_CODEC.encode(buf, p.wallReason());
                ByteBufCodecs.BOOL.encode(buf, p.active());
                ByteBufCodecs.VAR_INT.encode(buf, p.beats());
                ByteBufCodecs.VAR_INT.encode(buf, p.ticks());
                EVENT_CODEC.encode(buf, p.event());
                Ring.STREAM_CODEC.encode(buf, p.ring());
                // Сдвиг на единицу: VAR_INT не любит −1, а «нет прорыва» — это −1.
                ByteBufCodecs.VAR_INT.encode(buf, p.breakthrough() + 1);
                ByteBufCodecs.VAR_INT.encode(buf, p.rank());
                ByteBufCodecs.VAR_INT.encode(buf, p.breathingStars());
                ByteBufCodecs.BOOL.encode(buf, p.echo().isPresent());
                p.echo().ifPresent(art -> ResourceLocation.STREAM_CODEC.encode(buf, art));
                ByteBufCodecs.VAR_INT.encode(buf, p.moves().size());
                for (ResourceLocation move : p.moves()) {
                    ResourceLocation.STREAM_CODEC.encode(buf, move);
                }
            }, buf -> {
                var wallReason = net.minecraft.network.chat.ComponentSerialization.OPTIONAL_STREAM_CODEC.decode(buf);
                boolean active = ByteBufCodecs.BOOL.decode(buf);
                int beats = ByteBufCodecs.VAR_INT.decode(buf);
                int ticks = ByteBufCodecs.VAR_INT.decode(buf);
                Event event = EVENT_CODEC.decode(buf);
                Ring ring = Ring.STREAM_CODEC.decode(buf);
                int breakthrough = ByteBufCodecs.VAR_INT.decode(buf) - 1;
                int rank = ByteBufCodecs.VAR_INT.decode(buf);
                int breathingStars = ByteBufCodecs.VAR_INT.decode(buf);
                Optional<ResourceLocation> echo = ByteBufCodecs.BOOL.decode(buf)
                        ? Optional.of(ResourceLocation.STREAM_CODEC.decode(buf)) : Optional.empty();
                int count = Math.min(16, ByteBufCodecs.VAR_INT.decode(buf));
                List<ResourceLocation> moves = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    moves.add(ResourceLocation.STREAM_CODEC.decode(buf));
                }
                return new SyncMeditationPayload(active, beats, ticks, event, ring, breakthrough, rank, echo, moves, breathingStars, wallReason);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
