package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Сервер → клиентам: шаг совершён — откуда, куда и на каком слое (для эффекта). */
public record StepPayload(int entityId, Vec3 from, Vec3 to, float yaw, int layer) implements CustomPacketPayload {

    public static final Type<StepPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "step"));

    private static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    public static final StreamCodec<RegistryFriendlyByteBuf, StepPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StepPayload::entityId,
            VEC, StepPayload::from,
            VEC, StepPayload::to,
            ByteBufCodecs.FLOAT, StepPayload::yaw,
            ByteBufCodecs.VAR_INT, StepPayload::layer,
            StepPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
