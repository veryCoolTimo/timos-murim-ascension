package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Сервер → клиентам: выпуск «Разреза» Семи Цветков Сливы — откуда (стопы), куда смотрел,
 * слой и фактическая длина коридора (обрезана первой стеной).
 */
public record PlumSlashPayload(int entityId, net.minecraft.world.phys.Vec3 origin, float yaw, int layer, float length)
        implements CustomPacketPayload {

    private static final StreamCodec<RegistryFriendlyByteBuf, net.minecraft.world.phys.Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, net.minecraft.world.phys.Vec3::x, ByteBufCodecs.DOUBLE, net.minecraft.world.phys.Vec3::y,
            ByteBufCodecs.DOUBLE, net.minecraft.world.phys.Vec3::z, net.minecraft.world.phys.Vec3::new);

    public static final Type<PlumSlashPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_slash"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlumSlashPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PlumSlashPayload::entityId,
            VEC, PlumSlashPayload::origin,
            ByteBufCodecs.FLOAT, PlumSlashPayload::yaw,
            ByteBufCodecs.VAR_INT, PlumSlashPayload::layer,
            ByteBufCodecs.FLOAT, PlumSlashPayload::length,
            PlumSlashPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
