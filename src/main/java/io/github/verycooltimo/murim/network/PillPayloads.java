package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Пилюли и мини-игра поглощения (docs/design/19b §1–2).
 *
 * <p>Клиент шлёт только сторону выбора ({@link Choice}); норов, исходы, напряжение и награду
 * считает сервер ({@link Sync}).
 */
public final class PillPayloads {

    private PillPayloads() {
    }

    /** Разовые события поглощения для картинки и звуковых хуков. */
    public enum Event { NONE, EXHALE, SETTLED, FINISH, BACKLASH, INTERRUPTED }

    /**
     * Снимок для клиента.
     *
     * @param pending      съеденные в открытом окне (ordinal {@code PillKind})
     * @param windowLeft   тиков до конца окна
     * @param active       идёт ли поглощение
     * @param clots        сгустки игры по порядку
     * @param clot         текущий сгусток
     * @param fork         текущая развилка
     * @param phase        фаза ({@code AbsorbGame.Phase} ordinal)
     * @param phaseTicks   тиков в фазе
     * @param choice       выбор игрока −1/0/1 (сторона экрана)
     * @param shortSide    сторона экрана короткой ветки
     * @param tookShort    сгусток пошёл короткой веткой (в фазе BRANCH)
     * @param temper       0 — норов скрыт, 1 — спокойный, 2 — бурный
     * @param strain       напряжение 0..1
     * @param outcome      исход развилки на этом тике ({@code AbsorbGame.Outcome} ordinal)
     * @param event        разовое событие
     * @param fullFive     канонический состав: полный пятицветный свет
     */
    public record Sync(int[] pending, int windowLeft, boolean active, int[] clots, int clot, int fork, int phase,
                       int phaseTicks, int choice, int shortSide, boolean tookShort, int temper, float strain,
                       int outcome, Event event, boolean fullFive) implements CustomPacketPayload {

        public static final Type<Sync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "pill_sync"));

        private static final StreamCodec<RegistryFriendlyByteBuf, int[]> INTS = StreamCodec.of((buf, a) -> {
            ByteBufCodecs.VAR_INT.encode(buf, a.length);
            for (int v : a) {
                ByteBufCodecs.VAR_INT.encode(buf, v);
            }
        }, buf -> {
            int n = Math.min(8, ByteBufCodecs.VAR_INT.decode(buf));
            int[] a = new int[n];
            for (int i = 0; i < n; i++) {
                a[i] = ByteBufCodecs.VAR_INT.decode(buf);
            }
            return a;
        });

        // Полей больше шести — composite не подходит. API: reference/minecraft-src/net/minecraft/network/codec/StreamCodec.java#of
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.of((buf, p) -> {
            INTS.encode(buf, p.pending());
            ByteBufCodecs.VAR_INT.encode(buf, Math.max(0, p.windowLeft()));
            ByteBufCodecs.BOOL.encode(buf, p.active());
            INTS.encode(buf, p.clots());
            ByteBufCodecs.VAR_INT.encode(buf, p.clot());
            ByteBufCodecs.VAR_INT.encode(buf, p.fork());
            ByteBufCodecs.VAR_INT.encode(buf, p.phase());
            ByteBufCodecs.VAR_INT.encode(buf, p.phaseTicks());
            ByteBufCodecs.VAR_INT.encode(buf, p.choice() + 1);
            ByteBufCodecs.VAR_INT.encode(buf, p.shortSide() + 1);
            ByteBufCodecs.BOOL.encode(buf, p.tookShort());
            ByteBufCodecs.VAR_INT.encode(buf, p.temper());
            ByteBufCodecs.FLOAT.encode(buf, p.strain());
            ByteBufCodecs.VAR_INT.encode(buf, p.outcome());
            ByteBufCodecs.VAR_INT.encode(buf, p.event().ordinal());
            ByteBufCodecs.BOOL.encode(buf, p.fullFive());
        }, buf -> new Sync(
                INTS.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.BOOL.decode(buf),
                INTS.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf) - 1,
                ByteBufCodecs.VAR_INT.decode(buf) - 1,
                ByteBufCodecs.BOOL.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.FLOAT.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                Event.values()[Math.min(Event.values().length - 1, ByteBufCodecs.VAR_INT.decode(buf))],
                ByteBufCodecs.BOOL.decode(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Выбор ветки: −1 — левая на экране, +1 — правая, 0 — снят. */
    public record Choice(int side) implements CustomPacketPayload {

        public static final Type<Choice> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "pill_choice"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Choice> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.map(i -> i - 1, i -> i + 1), Choice::side, Choice::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
