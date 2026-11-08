package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The sect map for the tablet: the shaded map colours of {@code w x h} world blocks from the north-west corner
 * {@code (x0, z0)}, deflated. Sent when a player opens the tablet and his copy is older than {@code version}.
 */
public record SectMapPayload(int version, int x0, int z0, int w, int h, byte[] zipped) implements CustomPacketPayload {
    public static final Type<SectMapPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_map"));
    /** Biggest picture accepted: 256 x 256 blocks. */
    public static final int MAX_SIDE = 256;
    public static final StreamCodec<RegistryFriendlyByteBuf, SectMapPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SectMapPayload::version,
            ByteBufCodecs.VAR_INT, SectMapPayload::x0,
            ByteBufCodecs.VAR_INT, SectMapPayload::z0,
            ByteBufCodecs.VAR_INT, SectMapPayload::w,
            ByteBufCodecs.VAR_INT, SectMapPayload::h,
            ByteBufCodecs.byteArray(MAX_SIDE * MAX_SIDE), SectMapPayload::zipped,
            SectMapPayload::new);

    /** The colours back out of the deflated bytes; null when the payload is malformed. */
    public byte[] colours() {
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) return null;
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(zipped);
            byte[] out = new byte[w * h];
            int n = 0;
            while (n < out.length && !inflater.finished()) {
                int got = inflater.inflate(out, n, out.length - n);
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                n += got;
            }
            return n == out.length ? out : null;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
