package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * НАМЕРЕНИЯ игрока в медитации: сесть или встать, держать кольцо или отпустить.
 *
 * <p>Клиент присылает только нажатия. Время, окно удержания и засчитанные тики считает
 * сервер — иначе такт можно было бы пройти пакетом.
 *
 * @param action {@code TOGGLE} — сесть или встать; {@code HOLD_ON} / {@code HOLD_OFF} — клавиша удержания
 * @param filter отсеивать ли примеси (имеет смысл только при {@code TOGGLE})
 */
public record MeditationInputPayload(Action action, boolean filter) implements CustomPacketPayload {

    public enum Action { TOGGLE, HOLD_ON, HOLD_OFF }

    public static final Type<MeditationInputPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "meditation_input"));

    private static final StreamCodec<ByteBuf, Action> ACTION_CODEC =
            ByteBufCodecs.idMapper(i -> Action.values()[i], Action::ordinal);

    public static final StreamCodec<RegistryFriendlyByteBuf, MeditationInputPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ACTION_CODEC, MeditationInputPayload::action,
                    ByteBufCodecs.BOOL, MeditationInputPayload::filter,
                    MeditationInputPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
