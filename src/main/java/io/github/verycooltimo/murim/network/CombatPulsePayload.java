package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client, empty: "you are in a fight the client cannot see by itself" (a sect bout or duel,
 * or a mob that targets you within {@code CombatSignal.AGGRO_RANGE}). Sent about once a second while it holds;
 * the client keeps its combat HUD up for a moment after the last one.
 */
public record CombatPulsePayload() implements CustomPacketPayload {
    public static final Type<CombatPulsePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "combat_pulse"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CombatPulsePayload> STREAM_CODEC = StreamCodec.unit(new CombatPulsePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
