package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Shows the goal reminder again for a few seconds: a new sect period began or the player came near the goal's person. */
public record SectGoalPulsePayload() implements CustomPacketPayload {
    public static final Type<SectGoalPulsePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_goal_pulse"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SectGoalPulsePayload> STREAM_CODEC = StreamCodec.unit(new SectGoalPulsePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
