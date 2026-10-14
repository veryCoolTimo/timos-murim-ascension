package io.github.verycooltimo.murim.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Server decisions only: 0 = end, 1 = maintained state, 2 = contact, 3 = leap. */
public record DemonicPayload(int entity, ResourceLocation art, int executionTier, int event, Vec3 at, int ticks)
        implements CustomPacketPayload {
    public static final Type<DemonicPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("murim", "demonic"));
    private static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);
    public static final StreamCodec<RegistryFriendlyByteBuf, DemonicPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DemonicPayload::entity, ResourceLocation.STREAM_CODEC, DemonicPayload::art,
            ByteBufCodecs.VAR_INT, DemonicPayload::executionTier, ByteBufCodecs.VAR_INT, DemonicPayload::event,
            VEC, DemonicPayload::at, ByteBufCodecs.VAR_INT, DemonicPayload::ticks, DemonicPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
