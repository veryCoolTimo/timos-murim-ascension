package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Сервер → клиент: сущность оглушена ещё {@code ticks} тиков (0 — оглушение снято); рисуются звёзды над головой. */
public record StunPayload(int entityId, int ticks) implements CustomPacketPayload {
    public static final Type<StunPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "stun"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StunPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StunPayload::entityId, ByteBufCodecs.VAR_INT, StunPayload::ticks, StunPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
