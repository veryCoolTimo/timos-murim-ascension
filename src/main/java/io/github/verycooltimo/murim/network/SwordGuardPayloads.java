package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Inputs carry no timing, target or result. Visual state: 0 open, 1 block, 2 window, 3 parry, 4 reset. */
public final class SwordGuardPayloads {
    public record Input(int action) implements CustomPacketPayload {
        public static final Type<Input> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sword_guard_input"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Input> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Input::action, Input::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record View(int entityId, int state) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sword_guard_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, View> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, View::entityId, ByteBufCodecs.VAR_INT, View::state, View::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    private SwordGuardPayloads() {}
}
