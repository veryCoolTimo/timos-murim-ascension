package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * Описания техник, уезжающие на клиент.
 *
 * <p>Датапак живёт на сервере, а клиенту форма дуги, цвета и тайминги нужны для отрисовки.
 * Поэтому определения синхронизируются при входе игрока и после каждого {@code /reload} —
 * тем же способом, что ваниль синхронизирует рецепты.
 *
 * <p>Кодек получен из того же {@link TechniqueDefinition#CODEC}, что читает JSON: одно описание
 * схемы на диск и на сеть. Разъехаться они не могут по построению.
 */
public record SyncTechniquesPayload(Map<ResourceLocation, TechniqueDefinition> definitions)
        implements CustomPacketPayload {

    public static final Type<SyncTechniquesPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sync_techniques"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncTechniquesPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.map(java.util.HashMap::new,
                            ResourceLocation.STREAM_CODEC,
                            ByteBufCodecs.fromCodec(TechniqueDefinition.CODEC)),
                    SyncTechniquesPayload::definitions,
                    SyncTechniquesPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
