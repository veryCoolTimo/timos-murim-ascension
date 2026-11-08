package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Море Цветущей Сливы (сервер → клиент). Стадии: 0 — раскрытие (a — ступни мастера, b — ось сектора),
 * 1 — снаряд ЗАХВАЧЕН (a — точка, b — скорость, value — id снаряда), 2 — захваченный снаряд лёг на
 * землю или исчез (a — точка, value — id), 3 — огненный шар погас в коконе (a — точка, value — id),
 * 4 — оплачен шаг продления (value — номер шага), 5 — тихий мираж (конец), 9 — прервано.
 */
public record SeaPayload(int entityId, Vec3 a, Vec3 b, int value, int executionTier, int stage, boolean fullMastery, int stars) implements CustomPacketPayload {
    public SeaPayload(int entityId, Vec3 a, Vec3 b, int value, int executionTier, int stage) {
        this(entityId, a, b, value, executionTier, stage, false);
    }

    public SeaPayload(int entityId, Vec3 a, Vec3 b, int value, int executionTier, int stage, boolean fullMastery) {
        this(entityId, a, b, value, executionTier, stage, fullMastery, fullMastery ? 12 : 1);
    }

    public static final int BEGIN = 0;
    public static final int CAPTURE = 1;
    public static final int LAND = 2;
    public static final int SNUFF = 3;
    public static final int BLEED = 4;
    public static final int MELT = 5;
    public static final int LOST = 9;

    private static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    public static final Type<SeaPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_sea"));

    // composite в 1.21.1 — не больше шести полей (reference/minecraft-src/net/minecraft/network/codec/StreamCodec.java).
    private static final StreamCodec<RegistryFriendlyByteBuf, SeaPayload> BASE_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SeaPayload::entityId,
            VEC, SeaPayload::a,
            VEC, SeaPayload::b,
            ByteBufCodecs.VAR_INT, SeaPayload::value,
            ByteBufCodecs.VAR_INT, SeaPayload::executionTier,
            ByteBufCodecs.VAR_INT, SeaPayload::stage,
            SeaPayload::new);

    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/network/codec/{StreamCodec#of,ByteBufCodecs#VAR_INT,ByteBufCodecs#BOOL}.java.
    public static final StreamCodec<RegistryFriendlyByteBuf, SeaPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> { BASE_CODEC.encode(buf, p); ByteBufCodecs.BOOL.encode(buf, p.fullMastery()); ByteBufCodecs.VAR_INT.encode(buf, p.stars()); },
            buf -> {
                var p = BASE_CODEC.decode(buf);
                return new SeaPayload(p.entityId(), p.a(), p.b(), p.value(), p.executionTier(), p.stage(), ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.VAR_INT.decode(buf));
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
