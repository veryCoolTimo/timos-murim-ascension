package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.profile.RitualState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Состояние ритуала на клиент — каждый тик медитации.
 *
 * <p>Пакет маленький и шлётся только своему игроку и только во время ритуала: постоянной
 * нагрузки он не создаёт, а полоса круга и напряжения без него не нарисуется.
 */
public record SyncRitualPayload(boolean active, int cycles, float cycleProgress, float strain)
        implements CustomPacketPayload {

    public static final Type<SyncRitualPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sync_ritual"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncRitualPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, SyncRitualPayload::active,
                    ByteBufCodecs.VAR_INT, SyncRitualPayload::cycles,
                    ByteBufCodecs.FLOAT, SyncRitualPayload::cycleProgress,
                    ByteBufCodecs.FLOAT, SyncRitualPayload::strain,
                    SyncRitualPayload::new);

    public static SyncRitualPayload of(RitualState state) {
        return new SyncRitualPayload(state.active(), state.cycles(), state.cycleProgress(),
                (float) state.strain());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
