package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Сервер → клиент: реплика над головой человека секты (живая гора, автор 05.10). Сервер выбирает lang-ключ и аргументы
 * ({@code Component.translatable}), клиент переводит на свой язык и рисует пузырь над головой {@code ticks} тиков.
 * Рассылается только игрокам в 16 блоках от говорящего.
 * API: reference/minecraft-src/net/minecraft/network/chat/ComponentSerialization.java#STREAM_CODEC
 */
public record SectBubblePayload(int entity, Component text, int ticks) implements CustomPacketPayload {

    public static final Type<SectBubblePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_bubble"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SectBubblePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SectBubblePayload::entity,
            ComponentSerialization.STREAM_CODEC, SectBubblePayload::text,
            ByteBufCodecs.VAR_INT, SectBubblePayload::ticks,
            SectBubblePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
