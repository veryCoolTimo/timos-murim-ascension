package io.github.verycooltimo.murim.dev;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Optional dev-only screen dismissal; carries no gameplay state. */
public record FilmScreenPayload() implements CustomPacketPayload {
    public static final Type<FilmScreenPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("murim", "film_close_screen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FilmScreenPayload> STREAM_CODEC = StreamCodec.unit(new FilmScreenPayload());
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
