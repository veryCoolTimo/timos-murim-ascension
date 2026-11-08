package io.github.verycooltimo.murim.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Server-confirmed contact/reaction cues. Kind selects presentation only; no client combat decisions. */
public record BambooPayload(int caster, int target, int stars, int kind, int duration, Vec3 at) implements CustomPacketPayload {
    public static final int CONTACT = 0, GROUND = 1, KNOCKDOWN = 2, ASIDE = 3, WALL = 4,
            JAB = 5, COLLAPSE = 6, RETURN = 7, GROVE = 8, GROVE_RING = 9, LANDING = 10, TRAIL = 11, RETURN_HIT = 12;
    public BambooPayload(int caster, int target, int stars, int kind, Vec3 at) { this(caster, target, stars, kind, 0, at); }
    public static final Type<BambooPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("murim", "bamboo_hand"));
    private static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);
    public static final StreamCodec<RegistryFriendlyByteBuf, BambooPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, BambooPayload::caster, ByteBufCodecs.INT, BambooPayload::target,
            ByteBufCodecs.VAR_INT, BambooPayload::stars, ByteBufCodecs.VAR_INT, BambooPayload::kind,
            ByteBufCodecs.VAR_INT, BambooPayload::duration, VEC, BambooPayload::at, BambooPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
