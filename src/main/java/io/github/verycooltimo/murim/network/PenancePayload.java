package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Forced suppression feeds the existing aura VFX even if the player's rank equals the elder's. */
public record PenancePayload(boolean pinned, int sourceId) implements CustomPacketPayload {
    public static final Type<PenancePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "penance_pin"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PenancePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, PenancePayload::pinned, ByteBufCodecs.VAR_INT, PenancePayload::sourceId, PenancePayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
