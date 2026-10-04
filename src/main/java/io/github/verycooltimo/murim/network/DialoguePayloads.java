package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

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
                       String playerAnim) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "dialogue_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Open::npc,
                ComponentSerialization.STREAM_CODEC, Open::name,
                ComponentSerialization.STREAM_CODEC, Open::title,
                ComponentSerialization.STREAM_CODEC, Open::line,
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list(4)), Open::options,
                ByteBufCodecs.STRING_UTF8, Open::playerAnim,
                Open::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
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
