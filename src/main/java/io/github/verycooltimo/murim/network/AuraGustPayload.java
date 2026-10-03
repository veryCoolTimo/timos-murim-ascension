package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Порыв давления: сильный толкнул придавленного (или потянул обратно). Сервер уже сдвинул
 * игрока; клиенту нужен момент и направление — для тряски, волны и наклона пламени.
 *
 * @param sourceId  кто давит
 * @param victimId  кого швырнуло
 * @param strength  сила порыва 0..1
 * @param pull      {@code true} — обратная тяга к источнику, {@code false} — толчок от него
 */
public record AuraGustPayload(int sourceId, int victimId, float strength, boolean pull) implements CustomPacketPayload {

    public static final Type<AuraGustPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "aura_gust"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AuraGustPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, AuraGustPayload::sourceId,
                    ByteBufCodecs.VAR_INT, AuraGustPayload::victimId,
                    ByteBufCodecs.FLOAT, AuraGustPayload::strength,
                    ByteBufCodecs.BOOL, AuraGustPayload::pull,
                    AuraGustPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
