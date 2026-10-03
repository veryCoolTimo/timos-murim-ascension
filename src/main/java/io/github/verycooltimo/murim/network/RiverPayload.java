package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Опадающие Лепестки, Перекрывающие Реку. Стадии: 0 — прицел зафиксирован (origin — кисть,
 * centre — точка прицела, targetId — цель или −1), 1 — поток ДОШЁЛ до цели (centre — точка контакта),
 * 2 — промах (centre — где поток рассыпался), 3 — взрыв ПОПАЛ (centre — узел, targetId — основная цель).
 */
public record RiverPayload(int entityId, Vec3 origin, Vec3 centre, int layer, int stage, int targetId)
        implements CustomPacketPayload {

    public static final int AIM = 0;
    public static final int CONTACT = 1;
    public static final int MISS = 2;
    public static final int BURST = 3;

    private static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    public static final Type<RiverPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_river"));

    // composite в 1.21.1 — не больше шести полей (reference/minecraft-src/net/minecraft/network/codec/StreamCodec.java).
    public static final StreamCodec<RegistryFriendlyByteBuf, RiverPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
                VEC.encode(buf, p.origin());
                VEC.encode(buf, p.centre());
                ByteBufCodecs.VAR_INT.encode(buf, p.layer());
                ByteBufCodecs.VAR_INT.encode(buf, p.stage());
                ByteBufCodecs.VAR_INT.encode(buf, p.targetId());
            },
            buf -> new RiverPayload(ByteBufCodecs.VAR_INT.decode(buf), VEC.decode(buf), VEC.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
