package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * Диалог с NPC (план секты §4.3): сервер выбирает реплику и варианты, клиент только показывает
 * и сообщает номер выбранного; каждый выбор сервер проверяет заново.
 * API: reference/minecraft-src/net/minecraft/network/chat/ComponentSerialization.java#STREAM_CODEC
 */
public final class DialoguePayloads {

    /**
     * Сервер → клиент: показать реплику. {@code playerAnim} — клип игрока на этой реплике (поклон предкам),
     * пусто — без клипа.
     */
    public record Open(int npc, Component name, Component title, Component line, List<Component> options,
                       String playerAnim, Optional<Shot> shot, boolean closer) implements CustomPacketPayload {
        public Open(int npc, Component name, Component title, Component line, List<Component> options, String playerAnim) {
            this(npc, name, title, line, options, playerAnim, Optional.empty(), false);
        }

        public Open(int npc, Component name, Component title, Component line, List<Component> options, String playerAnim,
                    Optional<Shot> shot) {
            this(npc, name, title, line, options, playerAnim, shot, false);
        }

        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "dialogue_open"));
        // composite takes at most six fields in 1.21.1 (reference/minecraft-src/net/minecraft/network/codec/StreamCodec.java).
        private static final StreamCodec<RegistryFriendlyByteBuf, List<Component>> OPTIONS =
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list(24));
        private static final StreamCodec<ByteBuf, Optional<Shot>> SHOT = ByteBufCodecs.optional(Shot.STREAM_CODEC);
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> STREAM_CODEC = StreamCodec.of(
                (buf, o) -> {
                    ByteBufCodecs.VAR_INT.encode(buf, o.npc());
                    ComponentSerialization.STREAM_CODEC.encode(buf, o.name());
                    ComponentSerialization.STREAM_CODEC.encode(buf, o.title());
                    ComponentSerialization.STREAM_CODEC.encode(buf, o.line());
                    OPTIONS.encode(buf, o.options());
                    ByteBufCodecs.STRING_UTF8.encode(buf, o.playerAnim());
                    SHOT.encode(buf, o.shot());
                    ByteBufCodecs.BOOL.encode(buf, o.closer());
                },
                buf -> new Open(ByteBufCodecs.VAR_INT.decode(buf), ComponentSerialization.STREAM_CODEC.decode(buf),
                        ComponentSerialization.STREAM_CODEC.decode(buf), ComponentSerialization.STREAM_CODEC.decode(buf),
                        OPTIONS.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), SHOT.decode(buf), ByteBufCodecs.BOOL.decode(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A fixed camera shot of the newcomer tour (docs/design/39-player-path-v2.md): the eye point and the point the centre of the
     * frame looks at, in world coordinates already anchored to the live site by the server; {@code fov} is the vertical lens angle.
     * Absent: the camera stays on the speaker.
     */
    public record Shot(Vec3 camera, Vec3 aim, float fov) {
        private static final StreamCodec<ByteBuf, Vec3> VEC = StreamCodec.composite(
                ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);
        public static final StreamCodec<ByteBuf, Shot> STREAM_CODEC = StreamCodec.composite(
                VEC, Shot::camera, VEC, Shot::aim, ByteBufCodecs.FLOAT, Shot::fov, Shot::new);
    }

    /** Клиент → сервер: выбран вариант {@code index} (с нуля); −1 — игрок закрыл разговор. */
    public record Choose(int index) implements CustomPacketPayload {
        public static final Type<Choose> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "dialogue_choose"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Choose> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Choose::index, Choose::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Сервер → клиент: разговор окончен (камера возвращается). */
    public record Close() implements CustomPacketPayload {
        public static final Type<Close> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "dialogue_close"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Close> STREAM_CODEC = StreamCodec.unit(new Close());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private DialoguePayloads() {
    }
}
