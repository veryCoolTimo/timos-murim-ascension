package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Transient scene ownership: server begins/ends; client can only request an eligible skip. */
public final class HeadIntroPayloads {
    private HeadIntroPayloads() { }
    public record View(int head, long started) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "head_intro_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, View> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, View::head, ByteBufCodecs.VAR_LONG, View::started, View::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Skip(long started) implements CustomPacketPayload {
        public static final Type<Skip> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "head_intro_skip"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Skip> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Skip::started, Skip::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
