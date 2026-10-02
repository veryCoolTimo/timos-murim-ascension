package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Вихрь Цветущей Сливы: стадия 0 — Разрез вверх (центр зоны фиксирован, клиент ведёт всю шкалу
 * сам), стадия 1 — финальный проход ПОПАЛ (импакт-кадр, дрожь, дым — только по факту).
 */
public record WhirlPayload(int entityId, Vec3 centre, float yaw, int layer, int stage) implements CustomPacketPayload {

    private static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    public static final Type<WhirlPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_whirl"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WhirlPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, WhirlPayload::entityId,
            VEC, WhirlPayload::centre,
            ByteBufCodecs.FLOAT, WhirlPayload::yaw,
            ByteBufCodecs.VAR_INT, WhirlPayload::layer,
            ByteBufCodecs.VAR_INT, WhirlPayload::stage,
            WhirlPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
