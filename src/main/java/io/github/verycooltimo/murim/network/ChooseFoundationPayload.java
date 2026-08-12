package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * НАМЕРЕНИЕ игрока выбрать основание пути.
 *
 * <p>Именно намерение, а не результат: сервер проверяет фазу церемонии и то, что даньтянь
 * ещё не создан. Без проверки выбор можно было бы прислать в любой момент, в том числе
 * повторно, а необратимость выбора — весь его смысл.
 *
 * @param foundation идентификатор основания: {@code blood}, {@code void} или {@code mountain}
 */
public record ChooseFoundationPayload(String foundation) implements CustomPacketPayload {

    public static final Type<ChooseFoundationPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "choose_foundation"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChooseFoundationPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ChooseFoundationPayload::foundation,
                    ChooseFoundationPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
