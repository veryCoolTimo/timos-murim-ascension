package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Взрыв Цветущей Сливы. Стадии: 0 — стена заложена (origin — ступни мастера, centre — центр стены
 * на земле, yaw — нормаль стены), 1 — прыжок-удар (origin — старт, centre — точка остановки),
 * 2 — меч вошёл в стену, взрыв (origin — точка удара, centre — origin + ось выброса), 3 — выброс
 * ПОПАЛ (centre — точка попадания, targetId — задетый).
 */
public record ExplosionPayload(int entityId, Vec3 origin, Vec3 centre, float yaw, int layer, int stage, int targetId)
        implements CustomPacketPayload {

    public static final int WALL = 0;
    public static final int LUNGE = 1;
    public static final int BLAST = 2;
    public static final int HIT = 3;

    private static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    public static final Type<ExplosionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_explosion"));

    // composite в 1.21.1 — не больше шести полей (reference/minecraft-src/net/minecraft/network/codec/StreamCodec.java).
    public static final StreamCodec<RegistryFriendlyByteBuf, ExplosionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
                VEC.encode(buf, p.origin());
                VEC.encode(buf, p.centre());
                buf.writeFloat(p.yaw());
                ByteBufCodecs.VAR_INT.encode(buf, p.layer());
                ByteBufCodecs.VAR_INT.encode(buf, p.stage());
                ByteBufCodecs.VAR_INT.encode(buf, p.targetId());
            },
            buf -> new ExplosionPayload(ByteBufCodecs.VAR_INT.decode(buf), VEC.decode(buf), VEC.decode(buf), buf.readFloat(),
                    ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
