package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Озарение — техника перешла на новый слой (docs/design/19 §3г). В бою клиент даёт
 * вспышку и замирание на долю секунды; в медитации двойник чисто выполняет приём.
 *
 * @param technique  техника
 * @param layer      новый слой
 * @param meditating случилось ли в медитации
 */
public record InsightPayload(ResourceLocation technique, int layer, boolean meditating)
        implements CustomPacketPayload {

    public static final Type<InsightPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "insight"));

    public static final StreamCodec<RegistryFriendlyByteBuf, InsightPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceLocation.STREAM_CODEC, InsightPayload::technique,
                    ByteBufCodecs.VAR_INT, InsightPayload::layer,
                    ByteBufCodecs.BOOL, InsightPayload::meditating,
                    InsightPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
