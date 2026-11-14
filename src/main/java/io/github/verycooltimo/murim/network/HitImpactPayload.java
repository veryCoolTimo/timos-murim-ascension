package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** One own hit impact (flash, sparks, smoke) at a contact point; {@code dx, dz} point from the attacker to the victim. */
public record HitImpactPayload(float x, float y, float z, float dx, float dz, int kind, float power) implements CustomPacketPayload {

    public static final int HIT = 0;
    public static final int BLOCK = 1;

    public static final Type<HitImpactPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "hit_impact"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HitImpactPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeFloat(p.x);
                buf.writeFloat(p.y);
                buf.writeFloat(p.z);
                buf.writeFloat(p.dx);
                buf.writeFloat(p.dz);
                buf.writeVarInt(p.kind);
                buf.writeFloat(p.power);
            },
            buf -> new HitImpactPayload(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readVarInt(), buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
