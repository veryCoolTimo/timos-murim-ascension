package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Состояние церемонии создания даньтяня на клиент.
 *
 * <p>Шлётся каждый тик церемонии и только своему игроку. Сцена рисуется целиком по этому
 * состоянию, поэтому пропущенный переход фазы означает застрявший на экране эффект —
 * дешевле слать маленький пакет, чем угадывать фазу на клиенте по таймеру.
 *
 * <p>Фаза передаётся строкой, а не порядковым номером: номер молча меняется при вставке
 * значения в середину перечисления, и рассинхрон проявится далеко от места правки.
 *
 * @param phase      имя фазы из {@code AwakeningState.Phase}
 * @param tick       тик от начала фазы
 * @param foundation идентификатор основания или пустая строка, пока выбора нет
 */
public record SyncAwakeningPayload(String phase, int tick, String foundation)
        implements CustomPacketPayload {

    public static final Type<SyncAwakeningPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sync_awakening"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncAwakeningPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, SyncAwakeningPayload::phase,
                    ByteBufCodecs.VAR_INT, SyncAwakeningPayload::tick,
                    ByteBufCodecs.STRING_UTF8, SyncAwakeningPayload::foundation,
                    SyncAwakeningPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
