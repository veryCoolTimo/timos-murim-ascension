package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Owner's one current invitation. An empty component clears it when membership ends. */
public record SectGoalPayload(Component text) implements CustomPacketPayload {
    public static final Type<SectGoalPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_goal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SectGoalPayload> STREAM_CODEC =
            ComponentSerialization.STREAM_CODEC.map(SectGoalPayload::new, SectGoalPayload::text);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
