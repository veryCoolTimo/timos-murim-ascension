package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Where Mount Hua stands (sent on login): the client draws the mist over the massif with it.
 * Only the placement — the terrain itself reaches the client as ordinary chunks.
 */
public record MountHuaSitePayload(int centerX, int centerZ, int baseY, int rotation) implements CustomPacketPayload {

    public static final Type<MountHuaSitePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "mount_hua_site"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MountHuaSitePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, MountHuaSitePayload::centerX,
                    ByteBufCodecs.VAR_INT, MountHuaSitePayload::centerZ,
                    ByteBufCodecs.VAR_INT, MountHuaSitePayload::baseY,
                    ByteBufCodecs.VAR_INT, MountHuaSitePayload::rotation,
                    MountHuaSitePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
