package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * НАМЕРЕНИЕ начать создание даньтяня.
 *
 * <p>Пустой пакет: всё, что нужно серверу, — сам факт нажатия. Проверки «даньтянь ещё не
 * создан», «игрок на земле» и «нет другой сцены» делает сервер, потому что клиент своим
 * состоянием распоряжаться не вправе.
 */
public record StartAwakeningPayload() implements CustomPacketPayload {

    public static final StartAwakeningPayload INSTANCE = new StartAwakeningPayload();

    public static final Type<StartAwakeningPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "start_awakening"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StartAwakeningPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
