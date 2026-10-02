package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Пакеты цингуна (Шаг Молнии). */
public final class TraversePayloads {

    /** Клиент → сервер: нажат прыжок во время бега. Что именно выйдет — решает сервер. */
    public record Jump() implements CustomPacketPayload {
        public static final Type<Jump> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "traverse_jump"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Jump> STREAM_CODEC = StreamCodec.unit(new Jump());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Сервер → наблюдателям: состояние бега игрока {@code entityId} или толчок.
     *
     * @param kind 0 — бег выключен, 1 — бег включён, 2 — длинный прыжок, 3 — от стены,
     *             4 — воздушная коррекция
     */
    public record Event(int entityId, int kind, int layer, float dirX, float dirZ) implements CustomPacketPayload {
        public static final Type<Event> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "traverse_event"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Event> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Event::entityId,
                ByteBufCodecs.VAR_INT, Event::kind,
                ByteBufCodecs.VAR_INT, Event::layer,
                ByteBufCodecs.FLOAT, Event::dirX,
                ByteBufCodecs.FLOAT, Event::dirZ,
                Event::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private TraversePayloads() {
    }
}
