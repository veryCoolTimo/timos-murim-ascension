package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Full Bloom front: origin, direction, reached distance, execution tier, lifecycle stage. */
public record BloomPayload(int entityId, Vec3 a, Vec3 b, float value, int executionTier, int stage, boolean fullMastery, int stars) implements CustomPacketPayload {
    public BloomPayload(int entityId, Vec3 a, Vec3 b, float value, int executionTier, int stage) {
        this(entityId, a, b, value, executionTier, stage, false);
    }

    public BloomPayload(int entityId, Vec3 a, Vec3 b, float value, int executionTier, int stage, boolean fullMastery) {
        this(entityId, a, b, value, executionTier, stage, fullMastery, fullMastery ? 12 : 1);
    }

    public static final int HOLD = 0, RELEASE = 1, END = 2, HIT = 3, CLASH = 4;

    private static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    public static final Type<BloomPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_full_bloom"));

    // composite в 1.21.1 — не больше шести полей (reference/minecraft-src/net/minecraft/network/codec/StreamCodec.java).
    private static final StreamCodec<RegistryFriendlyByteBuf, BloomPayload> BASE_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BloomPayload::entityId,
            VEC, BloomPayload::a,
            VEC, BloomPayload::b,
            ByteBufCodecs.FLOAT, BloomPayload::value,
            ByteBufCodecs.VAR_INT, BloomPayload::executionTier,
            ByteBufCodecs.VAR_INT, BloomPayload::stage,
            BloomPayload::new);

    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/network/codec/{StreamCodec#of,ByteBufCodecs#VAR_INT,ByteBufCodecs#BOOL}.java.
    public static final StreamCodec<RegistryFriendlyByteBuf, BloomPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> { BASE_CODEC.encode(buf, p); ByteBufCodecs.BOOL.encode(buf, p.fullMastery()); ByteBufCodecs.VAR_INT.encode(buf, p.stars()); },
            buf -> {
                var p = BASE_CODEC.decode(buf);
                return new BloomPayload(p.entityId(), p.a(), p.b(), p.value(), p.executionTier(), p.stage(), ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.VAR_INT.decode(buf));
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
