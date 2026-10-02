package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Натиск Цветущей Сливы: стадия 0 — выпуск урагана (origin — исходная точка, centre — не используется),
 * 1 — ураган завернул цель (centre — точка обволакивания), 2 — укол ПОПАЛ (centre — точка удара).
 */
public record RushPayload(int entityId, Vec3 origin, Vec3 centre, float yaw, int layer, int stage) implements CustomPacketPayload {

    private static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    public static final Type<RushPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_rush"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RushPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RushPayload::entityId,
            VEC, RushPayload::origin,
            VEC, RushPayload::centre,
            ByteBufCodecs.FLOAT, RushPayload::yaw,
            ByteBufCodecs.VAR_INT, RushPayload::layer,
            ByteBufCodecs.VAR_INT, RushPayload::stage,
            RushPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
