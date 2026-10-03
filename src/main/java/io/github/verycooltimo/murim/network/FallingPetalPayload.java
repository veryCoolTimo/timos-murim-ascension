package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Меч Падающего Цветка: стадия 0 — вход (at — исходная точка, axis — ось входа, targetId — цель
 * или −1), 1..5 — удар k ПОПАЛ (at — точка контакта), 8 — колени цели (at — ноги),
 * 9 — техника оборвана (цель умерла/ушла).
 */
public record FallingPetalPayload(int entityId, int targetId, int stage, Vec3 at, Vec3 axis, int layer)
        implements CustomPacketPayload {

    private static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    public static final Type<FallingPetalPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "falling_petal"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FallingPetalPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FallingPetalPayload::entityId,
            ByteBufCodecs.INT, FallingPetalPayload::targetId,
            ByteBufCodecs.VAR_INT, FallingPetalPayload::stage,
            VEC, FallingPetalPayload::at,
            VEC, FallingPetalPayload::axis,
            ByteBufCodecs.VAR_INT, FallingPetalPayload::layer,
            FallingPetalPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
