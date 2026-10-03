package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Ливень Цветов (см. ShowerRules): {@link #RELEASE} — толчок (a — старт, b — точка зависания,
 * extra — id цели или −1); {@link #DIVE} — пикирование (a — откуда, b — точка выхода);
 * {@link #HIT} — проход сквозь цель ПОПАЛ (a — центр цели, b — a + направление);
 * {@link #CUT} — разрез ливня попал (a — центр цели, extra — номер); {@link #LAND} — приземление
 * (a — ступни, extra — 1, если был прокол).
 */
public record ShowerPayload(int entityId, Vec3 a, Vec3 b, int layer, int stage, int extra) implements CustomPacketPayload {

    public static final int RELEASE = 0;
    public static final int DIVE = 1;
    public static final int HIT = 2;
    public static final int CUT = 3;
    public static final int LAND = 4;

    private static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    public static final Type<ShowerPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_shower"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShowerPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ShowerPayload::entityId,
            VEC, ShowerPayload::a,
            VEC, ShowerPayload::b,
            ByteBufCodecs.VAR_INT, ShowerPayload::layer,
            ByteBufCodecs.VAR_INT, ShowerPayload::stage,
            ByteBufCodecs.VAR_INT, ShowerPayload::extra,
            ShowerPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
