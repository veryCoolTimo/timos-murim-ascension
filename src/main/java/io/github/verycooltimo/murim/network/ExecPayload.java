package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Казнь Цветущей Сливы: стадия 0 — выход клонов (исходная точка, центр сбора у цели, угол «цель →
 * оригинал»), стадия 1 — шесть разрезов ПОПАЛИ (centre = точка удара).
 */
public record ExecPayload(int entityId, Vec3 origin, Vec3 centre, float base, int layer, int stage) implements CustomPacketPayload {

    private static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    public static final Type<ExecPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_exec"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExecPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ExecPayload::entityId,
            VEC, ExecPayload::origin,
            VEC, ExecPayload::centre,
            ByteBufCodecs.FLOAT, ExecPayload::base,
            ByteBufCodecs.VAR_INT, ExecPayload::layer,
            ByteBufCodecs.VAR_INT, ExecPayload::stage,
            ExecPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
