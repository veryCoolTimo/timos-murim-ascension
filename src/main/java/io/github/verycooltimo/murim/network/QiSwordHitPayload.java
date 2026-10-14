package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Сервер подтвердил мечевое действие (каст или попадание): проявить ци-клинок. Wire ID сохранён. */
public record QiSwordHitPayload(int sourceId) implements CustomPacketPayload {
    public static final Type<QiSwordHitPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "qi_sword_hit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, QiSwordHitPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, QiSwordHitPayload::sourceId, QiSwordHitPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
