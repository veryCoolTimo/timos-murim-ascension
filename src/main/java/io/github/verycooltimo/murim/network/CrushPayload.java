package io.github.verycooltimo.murim.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Server facts: START, frozen AIM, planted STEP, and STRIKE (including a real miss).
 * Position is caster/foot/contact respectively; axis is frozen at AIM. Stars are exact, never inferred from tier.
 */
public record CrushPayload(int entityId, int executionTier, int stars, int stage,
                           int targetId, Vec3 position, Vec3 axis, boolean hit, int deflected)
        implements CustomPacketPayload {
    public static final int START = 0, AIM = 1, STEP = 2, STRIKE = 3;
    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/{resources/ResourceLocation.java#fromNamespaceAndPath,
    // network/codec/StreamCodec.java#of, network/codec/ByteBufCodecs.java#VAR_INT/DOUBLE/BOOL,
    // world/phys/Vec3.java#Vec3, network/protocol/common/custom/CustomPacketPayload.java#Type}.
    public static final Type<CrushPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("murim", "plum_crush"));
    private static void vector(RegistryFriendlyByteBuf b, Vec3 p) {
        ByteBufCodecs.DOUBLE.encode(b, p.x); ByteBufCodecs.DOUBLE.encode(b, p.y); ByteBufCodecs.DOUBLE.encode(b, p.z);
    }
    private static Vec3 vector(RegistryFriendlyByteBuf b) {
        return new Vec3(ByteBufCodecs.DOUBLE.decode(b), ByteBufCodecs.DOUBLE.decode(b), ByteBufCodecs.DOUBLE.decode(b));
    }
    public static final StreamCodec<RegistryFriendlyByteBuf, CrushPayload> STREAM_CODEC = StreamCodec.of((b, p) -> {
        ByteBufCodecs.VAR_INT.encode(b, p.entityId); ByteBufCodecs.VAR_INT.encode(b, p.executionTier);
        ByteBufCodecs.VAR_INT.encode(b, p.stars); ByteBufCodecs.VAR_INT.encode(b, p.stage);
        ByteBufCodecs.VAR_INT.encode(b, p.targetId); vector(b, p.position); vector(b, p.axis);
        ByteBufCodecs.BOOL.encode(b, p.hit); ByteBufCodecs.VAR_INT.encode(b, p.deflected);
    }, b -> new CrushPayload(ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b),
            ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b),
            vector(b), vector(b), ByteBufCodecs.BOOL.decode(b), ByteBufCodecs.VAR_INT.decode(b)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
