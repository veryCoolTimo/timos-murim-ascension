package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Клиент → сервер: R удержан или отпущен для Моря либо Полного Цветения.
 * Это только намерение: продлевать ли и чем платить, решает сервер (SeaExecutor / FullBloomExecutor).
 */
public record SeaHoldPayload(boolean held) implements CustomPacketPayload {

    public static final Type<SeaHoldPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_sea_hold"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SeaHoldPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, SeaHoldPayload::held,
            SeaHoldPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
