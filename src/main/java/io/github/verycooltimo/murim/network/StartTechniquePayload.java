package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Клиент → сервер: игрок нажал клавишу техники.
 *
 * <p>Это <b>запрос, а не команда</b>. Сервер сам решает, можно ли начать: проверяет, не идёт ли
 * уже другая техника и существует ли такая вообще. Клиент не присылает ни урон, ни цель.
 */
public record StartTechniquePayload(ResourceLocation techniqueId) implements CustomPacketPayload {

    public static final Type<StartTechniquePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "start_technique"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StartTechniquePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceLocation.STREAM_CODEC, StartTechniquePayload::techniqueId,
                    StartTechniquePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
