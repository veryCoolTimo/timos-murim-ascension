package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.AuraState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Аура существа на клиент: её рисуют и по ней считают давление на экране. */
public record SyncAuraPayload(int entityId, int rank, boolean demonic) implements CustomPacketPayload {

    public static final Type<SyncAuraPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sync_aura"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncAuraPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, SyncAuraPayload::entityId,
                    ByteBufCodecs.VAR_INT, SyncAuraPayload::rank,
                    ByteBufCodecs.BOOL, SyncAuraPayload::demonic,
                    SyncAuraPayload::new);

    public AuraState aura() {
        return new AuraState(Math.max(0, Math.min(AuraState.MAX_RANK, rank)), demonic);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
