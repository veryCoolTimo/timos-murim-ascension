package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Купол Цветущей Сливы. Стадии: 0 — барьер посажен (a — центр у ступней, b — направление,
 * value — наклон, градусы), 1 — удар ПОГАШЕН (a — точка контакта на дуге, b — откуда пришёл,
 * value — доля пула, которую он снял), 2 — пул исчерпан, сеть рвётся (a — точка контакта),
 * 9 — мастер вышел из-за щита или техника прервана: распад.
 */
public record DomePayload(int entityId, Vec3 a, Vec3 b, float value, int layer, int stage) implements CustomPacketPayload {

    public static final int BEGIN = 0;
    public static final int BLOCK = 1;
    public static final int BREAK = 2;
    public static final int LOST = 9;

    private static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    public static final Type<DomePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_dome"));

    // composite в 1.21.1 — не больше шести полей (reference/minecraft-src/net/minecraft/network/codec/StreamCodec.java).
    public static final StreamCodec<RegistryFriendlyByteBuf, DomePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DomePayload::entityId,
            VEC, DomePayload::a,
            VEC, DomePayload::b,
            ByteBufCodecs.FLOAT, DomePayload::value,
            ByteBufCodecs.VAR_INT, DomePayload::layer,
            ByteBufCodecs.VAR_INT, DomePayload::stage,
            DomePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
