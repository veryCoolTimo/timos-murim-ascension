package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Клиент → сервер: захваченная цель (id сущности, −1 — снять захват), 03.10. */
public record LockPayload(int entityId) implements CustomPacketPayload {
    public static final Type<LockPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "lock_on"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LockPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, LockPayload::entityId, LockPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
