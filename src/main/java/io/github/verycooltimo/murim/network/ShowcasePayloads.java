package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Showcase reel (docs/design/44-commands.md, {@code /murim dev showcase}): the server timeline tells the client what to show. */
public final class ShowcasePayloads {
    private ShowcasePayloads() { }

    public enum Op { BEGIN, END, SHOT, FREE, SNAP }

    /**
     * One cue. {@code BEGIN} hides the HUD, {@code END} brings everything back, {@code SHOT} starts the far camera, {@code FREE}
     * gives the camera back to the cutscene that follows, {@code SNAP} takes a screenshot named {@code label}.
     * Camera fields (SHOT only): focus point, the camera looks along {@code yaw} (vanilla yaw) from {@code elev} degrees above the
     * focus at {@code dist} blocks; over {@code ticks} the yaw turns by {@code sweep} and the distance is scaled by {@code dolly};
     * {@code fov} scales the player's field of view (below 1 = tighter).
     */
    public record Cue(Op op, String label, double x, double y, double z, float yaw, float elev, float dist, float sweep,
                      float dolly, float fov, int ticks) implements CustomPacketPayload {
        public static final Type<Cue> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "showcase_cue"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Cue> STREAM_CODEC = StreamCodec.of(
                (buf, c) -> {
                    buf.writeEnum(c.op);
                    buf.writeUtf(c.label, 128);
                    buf.writeDouble(c.x);
                    buf.writeDouble(c.y);
                    buf.writeDouble(c.z);
                    buf.writeFloat(c.yaw);
                    buf.writeFloat(c.elev);
                    buf.writeFloat(c.dist);
                    buf.writeFloat(c.sweep);
                    buf.writeFloat(c.dolly);
                    buf.writeFloat(c.fov);
                    buf.writeVarInt(c.ticks);
                },
                buf -> new Cue(buf.readEnum(Op.class), buf.readUtf(128), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                        buf.readVarInt()));

        public static Cue of(Op op, String label) {
            return new Cue(op, label, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0);
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
