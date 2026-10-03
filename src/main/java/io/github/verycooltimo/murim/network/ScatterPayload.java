package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Рассеяние Цветущей Сливы (см. ScatterRules). Стадии: 0 — прыжок, клоны выходят (a — ступни мастера,
 * b — центр цели, arg — бит 0: сторона прыжка вправо, бит 1: цель на земле); 1 — удар клона ПОПАЛ
 * (a — точка, arg — клон × 8 + удар); 2 — взмах мастера попал; 3 — рассеяние попало (a — центр).
 */
public record ScatterPayload(int entityId, Vec3 a, Vec3 b, float base, int layer, int stage, int arg, int targetId)
        implements CustomPacketPayload {

    public static final int RELEASE = 0;
    public static final int HIT = 1;
    public static final int FINAL_HIT = 2;
    public static final int SCATTER_HIT = 3;

    private static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    public static final Type<ScatterPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_scatter"));

    // composite в 1.21.1 — не больше шести полей (reference/minecraft-src/net/minecraft/network/codec/StreamCodec.java).
    public static final StreamCodec<RegistryFriendlyByteBuf, ScatterPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
                VEC.encode(buf, p.a());
                VEC.encode(buf, p.b());
                buf.writeFloat(p.base());
                ByteBufCodecs.VAR_INT.encode(buf, p.layer());
                ByteBufCodecs.VAR_INT.encode(buf, p.stage());
                ByteBufCodecs.VAR_INT.encode(buf, p.arg());
                ByteBufCodecs.VAR_INT.encode(buf, p.targetId());
            },
            buf -> new ScatterPayload(ByteBufCodecs.VAR_INT.decode(buf), VEC.decode(buf), VEC.decode(buf), buf.readFloat(),
                    ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
