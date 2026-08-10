package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.profile.DantianProfile;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Профиль даньтяня на клиент: без него нечего рисовать в интерфейсе. */
public record SyncProfilePayload(DantianProfile profile) implements CustomPacketPayload {

    public static final Type<SyncProfilePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sync_profile"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncProfilePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.fromCodec(DantianProfile.CODEC),
                    SyncProfilePayload::profile,
                    SyncProfilePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
