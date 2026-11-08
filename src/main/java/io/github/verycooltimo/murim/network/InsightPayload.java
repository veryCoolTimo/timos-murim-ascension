package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Озарение — техника перешла на новый слой (docs/design/19 §3г). В бою клиент даёт
 * вспышку и замирание на долю секунды; в медитации двойник чисто выполняет приём.
 *
 * <p>Прорыв вершины может нести Отклик (docs/design/research/synergy-and-fun-1910.md, A4): короткую строку о том,
 * что остальные искусства отозвались. Это единственное место, где он показывается.
 *
 * @param technique  техника
 * @param stars      новый слой
 * @param meditating случилось ли в медитации
 * @param echo       рубеж Отклика вершины, который сработал на этом прорыве (1..3), или 0 — Отклика нет
 * @param kin        выученные искусства-родня вершины, которые отозвались сильнее всего (не больше трёх)
 */
public record InsightPayload(ResourceLocation technique, int stars, boolean meditating, int echo,
                             List<ResourceLocation> kin) implements CustomPacketPayload {

    /** Сколько родни называет строка прорыва. */
    public static final int MAX_KIN = 3;

    public static final Type<InsightPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "insight"));

    public static final StreamCodec<RegistryFriendlyByteBuf, InsightPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceLocation.STREAM_CODEC, InsightPayload::technique,
                    ByteBufCodecs.VAR_INT, InsightPayload::stars,
                    ByteBufCodecs.BOOL, InsightPayload::meditating,
                    ByteBufCodecs.VAR_INT, InsightPayload::echo,
                    ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_KIN)), InsightPayload::kin,
                    InsightPayload::new);

    public InsightPayload {
        kin = List.copyOf(kin.size() > MAX_KIN ? kin.subList(0, MAX_KIN) : kin);
    }

    /** Обычное озарение без Отклика. */
    public InsightPayload(ResourceLocation technique, int stars, boolean meditating) {
        this(technique, stars, meditating, 0, List.of());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
