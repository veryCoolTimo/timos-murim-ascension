package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * ПОПАДАНИЕ техники: куда пришёлся удар.
 *
 * <p>Появился по прямому замечанию автора: «когда он ударил человека? если он не ударил,
 * откуда это? трейл берётся из воздуха». Раньше клиент рисовал выброс всегда и вслепую,
 * потому что о попадании ничего не знал. Теперь брызги яда возникают там, где удар
 * действительно состоялся, и не возникают, если он прошёл мимо.
 *
 * @param sourceId применивший технику
 * @param x        точка контакта в мире
 * @param y        точка контакта в мире
 * @param z        точка контакта в мире
 * @param height   высота цели: по ней брызги растекаются по телу, а не по точке
 */
public record TechniqueHitPayload(int sourceId, double x, double y, double z, float height)
        implements CustomPacketPayload {

    public static final Type<TechniqueHitPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "technique_hit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TechniqueHitPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, TechniqueHitPayload::sourceId,
                    ByteBufCodecs.DOUBLE, TechniqueHitPayload::x,
                    ByteBufCodecs.DOUBLE, TechniqueHitPayload::y,
                    ByteBufCodecs.DOUBLE, TechniqueHitPayload::z,
                    ByteBufCodecs.FLOAT, TechniqueHitPayload::height,
                    TechniqueHitPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
