package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Пилюли и игра «Провести поток» (docs/design/19b; author 25.10).
 *
 * <p>The server decides everything that matters: the rank rule, the load, the seed and the dissolve time ({@link FlowStart}), and
 * what the result is worth. The client only plays the game from the server's seed and reports how it ended ({@link FlowReport});
 * the server derives the quality from the report itself and clamps it.
 */
public final class PillPayloads {

    private PillPayloads() {
    }

    /** Разовые события поглощения для картинки и звуковых хуков. */
    public enum Event { NONE, EXHALE, FINISH, BURST, INTERRUPTED, LANDED, DEVIATION }

    /**
     * Снимок для клиента.
     *
     * @param pending     съеденные в открытом окне (ordinal {@code PillKind})
     * @param windowLeft  тиков до конца окна
     * @param active      идёт ли игра
     * @param clots       пилюли потока (ordinal {@code PillKind}); на FINISH - те, что усвоены
     * @param event       разовое событие
     * @param fullFive    канонический состав: полный пятицветный свет
     * @param quality     the result of the game, 0..1 (on FINISH, BURST and INTERRUPTED)
     * @param perfect     безупречное усвоение (на FINISH)
     * @param liftHeight  высота подлёта (блоки); 0 - подлёта нет (на FINISH)
     * @param bump        низкий потолок: подлёт кончается стуком (на FINISH)
     * @param reserve     старый резерв, ещё не усвоенный, долей стены запаса (видимая янтарная нить у шкалы ци)
     * @param taken       lifetime intake counts by PillKind ordinal, for the next-pill tooltip (UX #30)
     * @param streakKind  the pill eaten alone several times in a row (ordinal), -1 - none
     * @param streakCount how many times in a row; legacy last-kind snapshot; tolerance[] holds steps and ticks until recovery for every kind
     */
    public record Sync(int[] pending, int windowLeft, boolean active, int[] clots, Event event, boolean fullFive, float quality,
                       boolean perfect, float liftHeight, boolean bump, float reserve, int[] taken, int streakKind,
                       int streakCount, int[] tolerance) implements CustomPacketPayload {

        public Sync(int[] pending, int windowLeft, boolean active, int[] clots, Event event, boolean fullFive,
                    float quality, boolean perfect, float liftHeight, boolean bump, float reserve, int[] taken,
                    int streakKind, int streakCount) {
            this(pending, windowLeft, active, clots, event, fullFive, quality, perfect, liftHeight, bump, reserve,
                    taken, streakKind, streakCount, new int[0]);
        }

        public static final Type<Sync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "pill_sync"));

        private static final StreamCodec<RegistryFriendlyByteBuf, int[]> INTS = StreamCodec.of((buf, a) -> {
            ByteBufCodecs.VAR_INT.encode(buf, a.length);
            for (int v : a) {
                ByteBufCodecs.VAR_INT.encode(buf, v);
            }
        }, buf -> {
            // The cap used to be 8: with more than eight pill kinds the `taken` array was cut and the packet was "5 bytes too long", which
            // disconnected the client at login (found by the first film of the pill box, 24.10). Never cut below the number of kinds.
            int n = Math.min(64, ByteBufCodecs.VAR_INT.decode(buf));
            int[] a = new int[n];
            for (int i = 0; i < n; i++) {
                a[i] = ByteBufCodecs.VAR_INT.decode(buf);
            }
            return a;
        });

        // Полей больше шести - composite не подходит. API: reference/minecraft-src/net/minecraft/network/codec/StreamCodec.java#of
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.of((buf, p) -> {
            INTS.encode(buf, p.pending());
            ByteBufCodecs.VAR_INT.encode(buf, Math.max(0, p.windowLeft()));
            ByteBufCodecs.BOOL.encode(buf, p.active());
            INTS.encode(buf, p.clots());
            ByteBufCodecs.VAR_INT.encode(buf, p.event().ordinal());
            ByteBufCodecs.BOOL.encode(buf, p.fullFive());
            ByteBufCodecs.FLOAT.encode(buf, p.quality());
            ByteBufCodecs.BOOL.encode(buf, p.perfect());
            ByteBufCodecs.FLOAT.encode(buf, p.liftHeight());
            ByteBufCodecs.BOOL.encode(buf, p.bump());
            ByteBufCodecs.FLOAT.encode(buf, p.reserve());
            INTS.encode(buf, p.taken());
            ByteBufCodecs.VAR_INT.encode(buf, p.streakKind() + 1);
            ByteBufCodecs.VAR_INT.encode(buf, p.streakCount());
            INTS.encode(buf, p.tolerance());
        }, buf -> new Sync(
                INTS.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.BOOL.decode(buf),
                INTS.decode(buf),
                Event.values()[Math.min(Event.values().length - 1, ByteBufCodecs.VAR_INT.decode(buf))],
                ByteBufCodecs.BOOL.decode(buf),
                ByteBufCodecs.FLOAT.decode(buf),
                ByteBufCodecs.BOOL.decode(buf),
                ByteBufCodecs.FLOAT.decode(buf),
                ByteBufCodecs.BOOL.decode(buf),
                ByteBufCodecs.FLOAT.decode(buf),
                INTS.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf) - 1,
                ByteBufCodecs.VAR_INT.decode(buf), INTS.decode(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * The game begins (the player sat with the pills in the window): everything the client needs to play the same stream.
     *
     * @param id           session number: a report for an older session is ignored
     * @param kinds        the pills of the stream (ordinal {@code PillKind})
     * @param seed         seed of the stream (spots, forks, surges)
     * @param hard         a pill is one rank above the player: faster spots, more surges
     * @param torn         the body does not endure: no game, only the tearing (the server has already applied it)
     * @param overload     with {@code torn}: because of the total load, not a single pill
     * @param poisonNature the player's method has the poison nature (poison pills are easier)
     */
    public record FlowStart(int id, int[] kinds, long seed, boolean hard, boolean torn, boolean overload, boolean poisonNature, boolean automatic)
            implements CustomPacketPayload {

        public FlowStart(int id, int[] kinds, long seed, boolean hard, boolean torn, boolean overload, boolean poisonNature) {
            this(id, kinds, seed, hard, torn, overload, poisonNature, false);
        }

        public static final Type<FlowStart> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "pill_flow_start"));

        public static final StreamCodec<RegistryFriendlyByteBuf, FlowStart> STREAM_CODEC = StreamCodec.of((buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.id());
            Sync.INTS.encode(buf, p.kinds());
            ByteBufCodecs.VAR_LONG.encode(buf, p.seed());
            ByteBufCodecs.BOOL.encode(buf, p.hard());
            ByteBufCodecs.BOOL.encode(buf, p.torn());
            ByteBufCodecs.BOOL.encode(buf, p.overload());
            ByteBufCodecs.BOOL.encode(buf, p.poisonNature());
            ByteBufCodecs.BOOL.encode(buf, p.automatic());
        }, buf -> new FlowStart(ByteBufCodecs.VAR_INT.decode(buf), Sync.INTS.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf),
                ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.BOOL.decode(buf),
                ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.BOOL.decode(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** How the game stands, reported by the client. */
    public enum Outcome { RUNNING, DONE, TIMEOUT, BURST }

    /**
     * The client's report: every half second while the game runs (so an interrupted sitting is paid by the progress), and once at the
     * end. The server takes the outcome, the progress and the lost share and works the quality out itself.
     *
     * @param id       session number
     * @param outcome  {@link Outcome} ordinal
     * @param progress progress along the way, 0..1000
     * @param lost     lost share, tenths of a percent (0..1000)
     */
    public record FlowReport(int id, int outcome, int progress, int lost) implements CustomPacketPayload {

        public static final Type<FlowReport> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "pill_flow_report"));

        public static final StreamCodec<RegistryFriendlyByteBuf, FlowReport> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, FlowReport::id, ByteBufCodecs.VAR_INT, FlowReport::outcome,
                ByteBufCodecs.VAR_INT, FlowReport::progress, ByteBufCodecs.VAR_INT, FlowReport::lost, FlowReport::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
