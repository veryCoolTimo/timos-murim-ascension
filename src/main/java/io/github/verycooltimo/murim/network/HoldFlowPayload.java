package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * НАМЕРЕНИЕ остановить поток при создании даньтяня.
 *
 * <p>Пустой пакет: важен сам момент нажатия. Высоту, на которой поток остановлен, считает
 * сервер по своему тику — клиент её не присылает и присылать не должен, иначе силу
 * даньтяня можно было бы назначить пакетом.
 */
public record HoldFlowPayload() implements CustomPacketPayload {

    public static final HoldFlowPayload INSTANCE = new HoldFlowPayload();

    public static final Type<HoldFlowPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "hold_flow"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HoldFlowPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
